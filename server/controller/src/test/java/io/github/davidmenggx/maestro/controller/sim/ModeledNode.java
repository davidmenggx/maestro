package io.github.davidmenggx.maestro.controller.sim;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import maestro.fbs.CellDelta;
import maestro.fbs.CellRun;
import maestro.fbs.ChunkSnapshot;
import maestro.fbs.ControlFrame;
import maestro.fbs.ControlMsg;
import maestro.fbs.ControlReplyFrame;
import maestro.fbs.ControlReplyMsg;
import maestro.fbs.Heartbeat;
import maestro.fbs.Hello;
import maestro.fbs.MobClass;
import maestro.fbs.PathCancel;
import maestro.fbs.PathNode;
import maestro.fbs.PathRequest;
import maestro.fbs.PathRequestBatch;
import maestro.fbs.PathResponse;
import maestro.fbs.PathType;
import maestro.fbs.Pathfinder;
import maestro.fbs.RegionAssignment;
import maestro.fbs.RequestFrame;
import maestro.fbs.RequestMsg;
import maestro.fbs.ResponseFrame;
import maestro.fbs.ResponseMsg;
import maestro.fbs.UnknownProfile;
import maestro.fbs.Vec2i;
import maestro.fbs.Vec3s;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;

/**
 * Model of a C++ compute node. It decodes real frames with the generated bindings, holds the region
 * state a stateful node would, and answers requests at a fixed rate. Paths are straight lines,
 * because the controller and the wire are under test, not A*.
 *
 * <p>It refuses exactly the world frames the real node's {@code WorldStore} refuses, and reacts the
 * same way: nothing in the frame is applied, all held state is dropped, and the link is reset so
 * the node reconnects reporting nothing held. A correct controller never sends such a frame, so
 * each refusal is recorded in {@link #violations()} instead of thrown, and one run reports every
 * problem it hit. Like the real node, it forgets its profiles and requests with its link, and keeps
 * its world state.
 */
public final class ModeledNode {

  private static final int MAX_PATH_NODES = 32;

  /** The node's own interval. The controller neither sets nor requests it. */
  private static final int HEARTBEAT_TICKS = 20;

  /** Positions are relative to the region, as received. */
  private record Pending(long requestId, Pos start, Pos target) {}

  private final Simulation sim;
  private final SimTransport transport;
  private final NodeId id;
  private final int capacityPerTick;
  private final Deque<Pending> queue = new ArrayDeque<>();
  private final Set<Long> queued = new LinkedHashSet<>();

  /**
   * Answers kept for retries. The real node keeps them for a while, but a link is short enough here
   * not to.
   */
  private final Map<Long, byte[]> answered = new LinkedHashMap<>();

  /** Requests canceled before they were answered. A later copy of one is dropped. */
  private final Set<Long> canceled = new LinkedHashSet<>();

  private final Set<Short> knownClasses = new LinkedHashSet<>();
  private final List<String> violations = new ArrayList<>();

  private long run;
  private long regionId;
  private int size;
  private int baseY;
  private int height;
  private byte[] cells = new byte[0];
  private byte[] floors = new byte[0];
  private long lastSequence;
  private long duplicateRequests;
  private long canceledRequests;
  private int servicedTicks;
  private boolean backlogged;

  ModeledNode(Simulation sim, SimTransport transport, NodeId id, int capacityPerTick) {
    if (capacityPerTick < 1) {
      throw new IllegalArgumentException("a node serves at least one request a tick");
    }
    this.sim = sim;
    this.transport = transport;
    this.id = id;
    this.capacityPerTick = capacityPerTick;
  }

  // Accessors for harness assertions. Everything below this point is behavior.

  public NodeId id() {
    return id;
  }

  /** Mob profiles this node has received. */
  public int knownClassCount() {
    return knownClasses.size();
  }

  public long lastSequence() {
    return lastSequence;
  }

  public long duplicateRequests() {
    return duplicateRequests;
  }

  /** Requests a cancel removed from the queue before they were served. */
  public long canceledRequests() {
    return canceledRequests;
  }

  /** Whether a service ever ended with requests still queued, because more came than it serves. */
  public boolean backlogged() {
    return backlogged;
  }

  public List<String> violations() {
    return List.copyOf(violations);
  }

  public boolean hasState() {
    return cells.length > 0 && lastSequence > 0;
  }

  /** Holds an assigned area, possibly still all air because no chunk has arrived since. */
  public boolean assigned() {
    return cells.length > 0;
  }

  /** Region state only. Two nodes with the same world have equal state, however they got there. */
  public long stateDigest() {
    return digest(regionId, size, baseY, height, cells, floors);
  }

  /**
   * The digest of a region's state, with cells and floors indexed {@code (x * size + z) * height +
   * (y - baseY)}, as a node holds them.
   */
  static long digest(long regionId, int size, int baseY, int height, byte[] cells, byte[] floors) {
    return new Digest()
        .mix(regionId)
        .mix(size)
        .mix(baseY)
        .mix(height)
        .mix(cells)
        .mix(floors)
        .value();
  }

  /** Process death, or a full reset after a refused frame: all held state is lost. */
  void kill() {
    run = 0;
    regionId = 0;
    cells = new byte[0];
    floors = new byte[0];
    lastSequence = 0;
    linkDown();
  }

  /**
   * The link is gone. Profiles and request ids belong to it, since the next link may come from
   * another controller run, so they are forgotten. The world state stays, to be resumed.
   */
  void linkDown() {
    queue.clear();
    queued.clear();
    answered.clear();
    canceled.clear();
    knownClasses.clear();
  }

  /**
   * Opens a new connection by reporting what this node still holds. A restarted process reports
   * sequence 0 and gets a snapshot. A node that only lost its socket reports its sequence and the
   * controller replays from there.
   */
  void linkUp() {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int hello =
        hasState()
            ? Hello.createHello(builder, run, regionId, lastSequence, Pathfinder.VANILLA)
            : Hello.createHello(builder, 0, 0, 0, Pathfinder.VANILLA);
    builder.finish(
        ControlReplyFrame.createControlReplyFrame(builder, ControlReplyMsg.Hello, hello));
    transport.toController(id, Channel.CONTROL, ByteBuffer.wrap(builder.sizedByteArray()));
  }

  void receive(Channel channel, ByteBuffer frame) {
    switch (channel) {
      case WORLD -> onWorld(frame);
      case REQUEST -> onRequest(frame);
      case CONTROL -> onControl(frame);
    }
  }

  void service() {
    if (++servicedTicks % HEARTBEAT_TICKS == 0) {
      heartbeat();
    }
    int served = 0;
    while (!queue.isEmpty() && served < capacityPerTick) {
      Pending pending = queue.poll();
      queued.remove(pending.requestId());
      byte[] response = buildResponse(pending);
      answered.put(pending.requestId(), response);
      transport.toController(id, Channel.REQUEST, ByteBuffer.wrap(response));
      served++;
    }
    backlogged |= !queue.isEmpty();
    sim.metrics().gauge("node.queue.depth", queue.size(), "node", id.toString());
  }

  private void onWorld(ByteBuffer frame) {
    String refusal = applyWorld(WorldFrame.getRootAsWorldFrame(frame));
    if (refusal == null) {
      return;
    }
    violations.add(id + ": " + refusal);
    kill();
    transport.resetLink(id);
  }

  /** Applies one world frame, or returns the reason the real node would refuse it. */
  private String applyWorld(WorldFrame world) {
    return switch (world.bodyType()) {
      case WorldMsg.ChunkSnapshot ->
          applyChunkSnapshot((ChunkSnapshot) world.body(new ChunkSnapshot()));
      case WorldMsg.WorldDelta -> applyDelta((WorldDelta) world.body(new WorldDelta()));
      case WorldMsg.RegionAssignment ->
          assign((RegionAssignment) world.body(new RegionAssignment()));
      default -> "unknown world body " + world.bodyType();
    };
  }

  private void onRequest(ByteBuffer frame) {
    RequestFrame request = RequestFrame.getRootAsRequestFrame(frame);
    switch (request.bodyType()) {
      case RequestMsg.PathRequestBatch -> {
        PathRequestBatch batch = (PathRequestBatch) request.body(new PathRequestBatch());
        PathRequest scratch = new PathRequest();
        for (int i = 0; i < batch.requestsLength(); i++) {
          enqueue(batch.requests(scratch, i));
        }
      }
      case RequestMsg.PathCancel -> {
        PathCancel cancel = (PathCancel) request.body(new PathCancel());
        for (int i = 0; i < cancel.requestIdsLength(); i++) {
          cancel(cancel.requestIds(i));
        }
      }
      default -> violations.add(id + ": unknown request body " + request.bodyType());
    }
  }

  private void onControl(ByteBuffer frame) {
    ControlFrame control = ControlFrame.getRootAsControlFrame(frame);
    switch (control.bodyType()) {
      case ControlMsg.MobClass -> registerClass((MobClass) control.body(new MobClass()));
      default -> violations.add(id + ": unknown control body " + control.bodyType());
    }
  }

  private void registerClass(MobClass profile) {
    if (profile.malusLength() != PathTypes.count()) {
      violations.add(
          id + ": class " + profile.classId() + " malus covers " + profile.malusLength());
    }
    knownClasses.add((short) profile.classId());
  }

  private String assign(RegionAssignment assignment) {
    int newSize = assignment.size();
    int newBaseY = assignment.minY();
    int newHeight = assignment.height();
    // Wire positions are shorts, so the area must fit in one.
    if (newSize <= 0
        || newSize % Chunks.SIZE != 0
        || newSize > Short.MAX_VALUE
        || newHeight <= 0
        || newBaseY < Short.MIN_VALUE
        || (long) newBaseY + newHeight - 1 > Short.MAX_VALUE) {
      return "assigned an area the wire cannot address: size "
          + newSize
          + ", y "
          + newBaseY
          + " + "
          + newHeight;
    }

    if (assignment.resume()) {
      // Resuming keeps the held state, so it must be the stream the controller
      // is continuing, at the position it holds.
      if (!hasState()
          || assignment.run() != run
          || assignment.regionId() != regionId
          || assignment.sequence() != lastSequence
          || newSize != size
          || newBaseY != baseY
          || newHeight != height) {
        return "told to resume a stream it does not hold";
      }
      return null;
    }

    // Everything is air until a chunk says otherwise, as vanilla reads a chunk the
    // server does not hold.
    run = assignment.run();
    regionId = assignment.regionId();
    size = newSize;
    baseY = newBaseY;
    height = newHeight;
    cells = new byte[size * size * height];
    floors = new byte[cells.length];
    Arrays.fill(cells, (byte) PathType.OPEN);
    // The controller's sequence continues across a reassignment, so the
    // assignment says where it stands.
    lastSequence = assignment.sequence();
    return null;
  }

  /** The reason {@code sequence} cannot follow the held state, or null if it can. */
  private String admit(long sequence) {
    if (sequence == 0) {
      return "world frame with sequence 0";
    }
    if (sequence != lastSequence + 1) {
      return "world sequence gap, expected " + (lastSequence + 1) + " got " + sequence;
    }
    return null;
  }

  private void advance(long sequence) {
    lastSequence = sequence;
    sim.applied(id, regionId, sequence);
  }

  private String applyChunkSnapshot(ChunkSnapshot snapshot) {
    if (cells.length == 0) {
      return "chunk before region assignment";
    }
    Vec2i position = snapshot.chunkXz();
    int perSide = size / Chunks.SIZE;
    if (position == null
        || position.x() < 0
        || position.x() >= perSide
        || position.z() < 0
        || position.z() >= perSide) {
      return "chunk "
          + (position == null ? "without a position" : position.x() + "," + position.z())
          + " falls outside the assigned area";
    }

    String broken = checkChunkContract(snapshot);
    if (broken != null) {
      return broken;
    }
    String refused = admit(snapshot.sequence());
    if (refused != null) {
      return refused;
    }
    advance(snapshot.sequence());

    int originX = position.x() * Chunks.SIZE;
    int originZ = position.z() * Chunks.SIZE;
    // No columns means the server does not hold the chunk, so it is air.
    if (snapshot.runCountsLength() == 0) {
      fillChunk(originX, originZ, (byte) PathType.OPEN, (byte) 0);
      return null;
    }
    CellRun cellRun = new CellRun();
    int runIndex = 0;
    for (int column = 0; column < snapshot.runCountsLength(); column++) {
      int base = index(originX + column / Chunks.SIZE, baseY, originZ + column % Chunks.SIZE);
      int y = 0;
      for (int r = snapshot.runCounts(column); r > 0; r--) {
        snapshot.runs(cellRun, runIndex++);
        for (int i = 0; i < cellRun.length(); i++) {
          cells[base + y] = (byte) cellRun.nodeType();
          floors[base + y] = (byte) cellRun.floor();
          y++;
        }
      }
    }
    return null;
  }

  /**
   * The reason a chunk's runs break the contract, or null if they do not: one column per position,
   * every column at full height in runs of at least one block, only per-block types, and no extra
   * runs. A chunk with no columns is cleared and has no runs.
   */
  private String checkChunkContract(ChunkSnapshot snapshot) {
    int columns = snapshot.runCountsLength();
    if (columns == 0) {
      return snapshot.runsLength() == 0 ? null : "cleared chunk carries runs";
    }
    if (columns != Chunks.SIZE * Chunks.SIZE) {
      return "chunk covers " + columns + " columns";
    }
    CellRun cellRun = new CellRun();
    int runIndex = 0;
    for (int column = 0; column < columns; column++) {
      int count = snapshot.runCounts(column);
      if (count == 0 || runIndex + count > snapshot.runsLength()) {
        return "column " + column + " has " + count + " runs";
      }
      int y = 0;
      for (int r = 0; r < count; r++) {
        snapshot.runs(cellRun, runIndex++);
        y += cellRun.length();
        if (cellRun.length() == 0 || y > height) {
          return "column " + column + " overflows the region height";
        }
        if (!PathTypes.isPerBlock((byte) cellRun.nodeType())) {
          return "chunk run carries derived type " + PathTypes.name((byte) cellRun.nodeType());
        }
        if (cellRun.floor() > Cells.MAX_FLOOR) {
          return "chunk run has floor " + cellRun.floor() + ", past " + Cells.MAX_FLOOR;
        }
      }
      if (y != height) {
        return "column " + column + " tiles " + y + " of " + height + " blocks";
      }
    }
    return runIndex == snapshot.runsLength() ? null : "chunk carries runs past its last column";
  }

  private void fillChunk(int originX, int originZ, byte type, byte floor) {
    for (int dx = 0; dx < Chunks.SIZE; dx++) {
      for (int dz = 0; dz < Chunks.SIZE; dz++) {
        int base = index(originX + dx, baseY, originZ + dz);
        for (int y = 0; y < height; y++) {
          cells[base + y] = type;
          floors[base + y] = floor;
        }
      }
    }
  }

  private String applyDelta(WorldDelta delta) {
    if (cells.length == 0) {
      return "delta " + delta.sequence() + " before any assignment";
    }
    CellDelta cell = new CellDelta();
    for (int i = 0; i < delta.cellsLength(); i++) {
      delta.cells(cell, i);
      byte type = (byte) cell.nodeType();
      if (!PathTypes.isPerBlock(type)) {
        return "delta cell carries derived type " + PathTypes.name(type);
      }
      if (cell.floor() > Cells.MAX_FLOOR) {
        return "delta cell has floor " + cell.floor() + ", past " + Cells.MAX_FLOOR;
      }
      Vec3s pos = cell.pos();
      if (index(pos.x(), pos.y(), pos.z()) < 0) {
        return "delta cell " + pos.x() + "," + pos.y() + "," + pos.z() + " outside the area";
      }
    }
    String refused = admit(delta.sequence());
    if (refused != null) {
      return refused;
    }
    advance(delta.sequence());

    for (int i = 0; i < delta.cellsLength(); i++) {
      delta.cells(cell, i);
      Vec3s pos = cell.pos();
      int index = index(pos.x(), pos.y(), pos.z());
      cells[index] = (byte) cell.nodeType();
      floors[index] = (byte) cell.floor();
    }
    return null;
  }

  private void enqueue(PathRequest request) {
    long requestId = request.requestId();
    if (!knownClasses.contains((short) request.classId())) {
      // The profile has not arrived yet, so tell the controller immediately instead of
      // letting the request time out.
      transport.toController(
          id, Channel.REQUEST, ByteBuffer.wrap(unknownProfile(requestId, request.classId())));
      return;
    }
    byte[] prior = answered.get(requestId);
    if (prior != null) {
      // At-least-once delivery: answer the retry with the byte-identical response.
      duplicateRequests++;
      transport.toController(id, Channel.REQUEST, ByteBuffer.wrap(prior));
      return;
    }
    if (canceled.contains(requestId)) {
      return;
    }
    if (!queued.add(requestId)) {
      duplicateRequests++;
      return;
    }
    queue.add(
        new Pending(
            requestId,
            new Pos(request.start().x(), request.start().y(), request.start().z()),
            new Pos(request.target().x(), request.target().y(), request.target().z())));
  }

  /**
   * Drops a request that has not been served, and any copy that arrives later. One already answered
   * is left alone, as the real node leaves one it has started.
   */
  private void cancel(long requestId) {
    if (answered.containsKey(requestId)) {
      return;
    }
    canceled.add(requestId);
    if (queued.remove(requestId)) {
      queue.removeIf(pending -> pending.requestId() == requestId);
      canceledRequests++;
    }
  }

  private void heartbeat() {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int heartbeat = Heartbeat.createHeartbeat(builder, queue.size());
    builder.finish(
        ControlReplyFrame.createControlReplyFrame(builder, ControlReplyMsg.Heartbeat, heartbeat));
    transport.toController(id, Channel.CONTROL, ByteBuffer.wrap(builder.sizedByteArray()));
  }

  private static byte[] unknownProfile(long requestId, int classId) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int reply = UnknownProfile.createUnknownProfile(builder, requestId, classId);
    builder.finish(ResponseFrame.createResponseFrame(builder, ResponseMsg.UnknownProfile, reply));
    return builder.sizedByteArray();
  }

  private byte[] buildResponse(Pending pending) {
    List<Pos> path = straightLine(pending.start(), pending.target());
    boolean reached = !path.isEmpty() && path.get(path.size() - 1).equals(pending.target());

    FlatBufferBuilder builder = new FlatBufferBuilder(256);
    PathResponse.startNodesVector(builder, path.size());
    for (int i = path.size() - 1; i >= 0; i--) {
      Pos node = path.get(i);
      PathNode.createPathNode(
          builder, (short) node.x(), (short) node.y(), (short) node.z(), PathType.WALKABLE);
    }
    int nodes = builder.endVector();

    PathResponse.startPathResponse(builder);
    PathResponse.addRequestId(builder, pending.requestId());
    PathResponse.addReached(builder, reached);
    PathResponse.addNodes(builder, nodes);
    PathResponse.addCost(builder, path.size());
    PathResponse.addWorldSequence(builder, lastSequence);
    int response = PathResponse.endPathResponse(builder);

    builder.finish(ResponseFrame.createResponseFrame(builder, ResponseMsg.PathResponse, response));
    return builder.sizedByteArray();
  }

  private static List<Pos> straightLine(Pos start, Pos target) {
    List<Pos> path = new ArrayList<>();
    int x = start.x();
    int y = start.y();
    int z = start.z();
    while (path.size() < MAX_PATH_NODES
        && (x != target.x() || y != target.y() || z != target.z())) {
      x += Integer.signum(target.x() - x);
      z += Integer.signum(target.z() - z);
      y += Integer.signum(target.y() - y);
      path.add(new Pos(x, y, z));
    }
    return path;
  }

  private int index(int x, int y, int z) {
    int dy = y - baseY;
    if (x < 0 || x >= size || z < 0 || z >= size || dy < 0 || dy >= height) {
      return -1;
    }
    return (x * size + z) * height + dy;
  }
}
