package io.github.davidmenggx.maestro.controller.sim;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
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
import maestro.fbs.Vec3s;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;

/**
 * Stand-in for a C++ compute node: it decodes real frames with the generated bindings, holds the
 * region state a stateful node would hold, and answers requests at a fixed rate. Paths are straight
 * lines, because what is under test here is the controller and the wire, not A*.
 *
 * <p>Everything it refuses to accept is recorded in {@link #violations()} rather than thrown, so
 * one run reports every problem it hit.
 */
public final class ModeledNode {

  private static final int MAX_PATH_NODES = 32;

  /** The node's own cadence, which the controller neither sets nor solicits. */
  private static final int HEARTBEAT_TICKS = 20;

  /** Positions are region-relative, as received. */
  private record Pending(long requestId, Pos start, Pos target) {}

  private final Simulation sim;
  private final SimTransport transport;
  private final NodeId id;
  private final int capacityPerTick;
  private final Deque<Pending> queue = new ArrayDeque<>();
  private final Set<Long> queued = new LinkedHashSet<>();

  /**
   * Answers kept for retries. A real node would bound this by tick; a run is short enough not to.
   */
  private final Map<Long, byte[]> answered = new LinkedHashMap<>();

  private final Set<Short> knownClasses = new LinkedHashSet<>();
  private final List<String> violations = new ArrayList<>();

  private boolean assigned;

  /** Set by a fresh assignment: the next frame's sequence is whatever the controller is up to. */
  private boolean awaitingBaseline;

  private long run;
  private int regionId;
  private int size;
  private int baseY;
  private int height;
  private byte[] cells = new byte[0];
  private byte[] floors = new byte[0];
  private long lastSequence;
  private long duplicateRequests;
  private int servicedTicks;

  ModeledNode(Simulation sim, SimTransport transport, NodeId id, int capacityPerTick) {
    if (capacityPerTick < 1) {
      throw new IllegalArgumentException("a node serves at least one request a tick");
    }
    this.sim = sim;
    this.transport = transport;
    this.id = id;
    this.capacityPerTick = capacityPerTick;
  }

  // Assertion surface for the harness. Everything below this point is behavior.

  public NodeId id() {
    return id;
  }

  /** Mob profiles this node has been told about. */
  public int knownClassCount() {
    return knownClasses.size();
  }

  public long lastSequence() {
    return lastSequence;
  }

  public long duplicateRequests() {
    return duplicateRequests;
  }

  public List<String> violations() {
    return List.copyOf(violations);
  }

  public boolean hasState() {
    return cells.length > 0 && lastSequence > 0;
  }

  /** Region state only. Two nodes holding the same world agree on this whatever path they took. */
  public long stateDigest() {
    return new Digest()
        .mix(regionId)
        .mix(size)
        .mix(baseY)
        .mix(height)
        .mix(cells)
        .mix(floors)
        .value();
  }

  void kill() {
    assigned = false;
    awaitingBaseline = false;
    run = 0;
    regionId = 0;
    cells = new byte[0];
    floors = new byte[0];
    lastSequence = 0;
    queue.clear();
    queued.clear();
    answered.clear();
    knownClasses.clear();
  }

  /**
   * Opens a new connection by saying what this node still holds. A restarted process reports
   * sequence 0 and gets a snapshot; one that only lost its socket reports where it got to and the
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
    sim.metrics().gauge("node.queue.depth", queue.size(), "node", id.toString());
  }

  private void onWorld(ByteBuffer frame) {
    WorldFrame world = WorldFrame.getRootAsWorldFrame(frame);
    switch (world.bodyType()) {
      case WorldMsg.ChunkSnapshot ->
          applyChunkSnapshot((ChunkSnapshot) world.body(new ChunkSnapshot()));
      case WorldMsg.WorldDelta -> applyDelta((WorldDelta) world.body(new WorldDelta()));
      case WorldMsg.RegionAssignment ->
          assign((RegionAssignment) world.body(new RegionAssignment()));
      default -> violations.add(id + ": unknown world body " + world.bodyType());
    }
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

  private void assign(RegionAssignment assignment) {
    if (assignment.resume() && (!hasState() || assignment.run() != run)) {
      violations.add(id + ": told to resume a stream it does not hold");
      return;
    }
    run = assignment.run();
    regionId = (int) assignment.regionId();
    assigned = true;
    if (assignment.resume()) {
      return;
    }
    // Everything the node holds is air until a chunk says otherwise, which is what
    // vanilla reads for a chunk the server is not holding.
    size = assignment.size();
    baseY = assignment.minY();
    height = assignment.height();
    cells = new byte[size * size * height];
    floors = new byte[cells.length];
    Arrays.fill(cells, (byte) PathType.OPEN);
    lastSequence = 0;
    awaitingBaseline = true;
  }

  /**
   * Records a gap if the frame is not the one expected next. The first frame after a fresh
   * assignment sets the baseline, since the controller's sequence carries on across a reset and the
   * node cannot know where it got to.
   */
  private void advanceSequence(long sequence) {
    if (awaitingBaseline) {
      awaitingBaseline = false;
      lastSequence = sequence;
      return;
    }
    if (sequence != lastSequence + 1) {
      violations.add(
          id + ": world sequence gap, expected " + (lastSequence + 1) + " got " + sequence);
    }
    lastSequence = Math.max(lastSequence, sequence);
  }

  private void applyChunkSnapshot(ChunkSnapshot snapshot) {
    if (!assigned || cells.length == 0) {
      violations.add(id + ": chunk before region assignment");
      return;
    }
    advanceSequence(snapshot.sequence());

    int originX = snapshot.chunkXz().x() * Chunks.SIZE;
    int originZ = snapshot.chunkXz().z() * Chunks.SIZE;
    if (originX < 0
        || originZ < 0
        || originX + Chunks.SIZE > size
        || originZ + Chunks.SIZE > size) {
      violations.add(
          id
              + ": chunk "
              + snapshot.chunkXz().x()
              + ","
              + snapshot.chunkXz().z()
              + " falls outside the assigned area");
      return;
    }

    // No columns means the server is not holding the chunk, so it reads as air.
    if (snapshot.runCountsLength() == 0) {
      fillChunk(originX, originZ, (byte) PathType.OPEN, (byte) 0);
      return;
    }
    if (snapshot.runCountsLength() != Chunks.SIZE * Chunks.SIZE) {
      violations.add(id + ": chunk covers " + snapshot.runCountsLength() + " columns");
      return;
    }

    CellRun cellRun = new CellRun();
    int runIndex = 0;
    for (int column = 0; column < snapshot.runCountsLength(); column++) {
      int base = index(originX + column / Chunks.SIZE, baseY, originZ + column % Chunks.SIZE);
      int y = 0;
      for (int r = snapshot.runCounts(column); r > 0; r--) {
        snapshot.runs(cellRun, runIndex++);
        byte type = (byte) cellRun.nodeType();
        if (!PathTypes.isPerBlock(type)) {
          violations.add(id + ": chunk run carries derived type " + PathTypes.name(type));
          return;
        }
        byte floor = (byte) cellRun.floor();
        for (int i = 0; i < cellRun.length(); i++) {
          if (y >= height) {
            violations.add(id + ": column " + column + " overflows the region height");
            return;
          }
          cells[base + y] = type;
          floors[base + y] = floor;
          y++;
        }
      }
      if (y != height) {
        violations.add(id + ": column " + column + " tiles " + y + " of " + height + " blocks");
        return;
      }
    }
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

  private void applyDelta(WorldDelta delta) {
    if (cells.length == 0) {
      violations.add(id + ": delta " + delta.sequence() + " before any assignment");
      return;
    }
    advanceSequence(delta.sequence());

    CellDelta cell = new CellDelta();
    for (int i = 0; i < delta.cellsLength(); i++) {
      delta.cells(cell, i);
      byte type = (byte) cell.nodeType();
      if (!PathTypes.isPerBlock(type)) {
        violations.add(id + ": delta cell carries derived type " + PathTypes.name(type));
        continue;
      }
      Vec3s pos = cell.pos();
      int index = index(pos.x(), pos.y(), pos.z());
      if (index >= 0) {
        cells[index] = type;
        floors[index] = (byte) cell.floor();
      }
    }
  }

  private void enqueue(PathRequest request) {
    long requestId = request.requestId();
    if (!knownClasses.contains((short) request.classId())) {
      violations.add(id + ": request " + requestId + " names unknown class " + request.classId());
    }
    byte[] prior = answered.get(requestId);
    if (prior != null) {
      // At-least-once delivery: answer the retry with the byte-identical response.
      duplicateRequests++;
      transport.toController(id, Channel.REQUEST, ByteBuffer.wrap(prior));
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

  private void heartbeat() {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int heartbeat = Heartbeat.createHeartbeat(builder, queue.size());
    builder.finish(
        ControlReplyFrame.createControlReplyFrame(builder, ControlReplyMsg.Heartbeat, heartbeat));
    transport.toController(id, Channel.CONTROL, ByteBuffer.wrap(builder.sizedByteArray()));
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
