package io.github.davidmenggx.maestro.controller.sim;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.world.Pos;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import maestro.fbs.CellDelta;
import maestro.fbs.CellRun;
import maestro.fbs.ControlFrame;
import maestro.fbs.ControlMsg;
import maestro.fbs.PathRequest;
import maestro.fbs.PathRequestBatch;
import maestro.fbs.PathResponse;
import maestro.fbs.Ping;
import maestro.fbs.Pong;
import maestro.fbs.RegionAssignment;
import maestro.fbs.RequestFrame;
import maestro.fbs.RequestMsg;
import maestro.fbs.ResponseFrame;
import maestro.fbs.ResponseMsg;
import maestro.fbs.Vec3i;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;
import maestro.fbs.WorldSnapshot;

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

  private record Pending(long requestId, int mobId, Pos start, Pos target, long worldSequence) {}

  private final Simulation sim;
  private final SimTransport transport;
  private final NodeId id;
  private final int capacityPerTick;
  private final Deque<Pending> queue = new ArrayDeque<>();
  private final Set<Long> queued = new LinkedHashSet<>();
  private final Map<Long, byte[]> answered = new LinkedHashMap<>();
  private final List<String> violations = new ArrayList<>();

  private boolean assigned;
  private int minX;
  private int minZ;
  private int size;
  private int baseY;
  private int height;
  private byte[] cells = new byte[0];
  private long lastSequence;
  private long duplicateRequests;

  ModeledNode(Simulation sim, SimTransport transport, NodeId id, int capacityPerTick) {
    this.sim = sim;
    this.transport = transport;
    this.id = id;
    this.capacityPerTick = capacityPerTick;
  }

  public NodeId id() {
    return id;
  }

  public int queueDepth() {
    return queue.size();
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
    return cells.length > 0;
  }

  /** Region state only. Two nodes holding the same world agree on this whatever path they took. */
  public long stateDigest() {
    return new Digest().mix(minX).mix(minZ).mix(size).mix(baseY).mix(height).mix(cells).value();
  }

  /** Process death. Everything in memory goes, which is what forces a resnapshot on recovery. */
  void kill() {
    assigned = false;
    cells = new byte[0];
    lastSequence = 0;
    queue.clear();
    queued.clear();
    answered.clear();
  }

  void receive(Channel channel, ByteBuffer frame) {
    switch (channel) {
      case WORLD -> onWorld(frame);
      case REQUEST -> onRequest(frame);
      case CONTROL -> onControl(frame);
    }
  }

  void service(long tick) {
    int served = 0;
    while (!queue.isEmpty() && served < capacityPerTick) {
      Pending pending = queue.poll();
      queued.remove(pending.requestId());
      byte[] response = buildResponse(pending, tick);
      answered.put(pending.requestId(), response);
      transport.toController(id, Channel.REQUEST, ByteBuffer.wrap(response));
      served++;
    }
    sim.metrics().gauge("node.queue.depth", queue.size(), "node", id.toString());
  }

  private void onWorld(ByteBuffer frame) {
    WorldFrame world = WorldFrame.getRootAsWorldFrame(frame);
    switch (world.bodyType()) {
      case WorldMsg.WorldSnapshot -> applySnapshot((WorldSnapshot) world.body(new WorldSnapshot()));
      case WorldMsg.WorldDelta -> applyDelta((WorldDelta) world.body(new WorldDelta()));
      case WorldMsg.RegionAssignment ->
          assign((RegionAssignment) world.body(new RegionAssignment()));
      default -> violations.add(id + ": unknown world body " + world.bodyType());
    }
  }

  private void onRequest(ByteBuffer frame) {
    RequestFrame request = RequestFrame.getRootAsRequestFrame(frame);
    switch (request.bodyType()) {
      case RequestMsg.PathRequest -> enqueue((PathRequest) request.body(new PathRequest()));
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
      case ControlMsg.Ping -> pong((Ping) control.body(new Ping()));
      case ControlMsg.RegionAssignment ->
          assign((RegionAssignment) control.body(new RegionAssignment()));
      case ControlMsg.Hello -> {}
      default -> violations.add(id + ": unknown control body " + control.bodyType());
    }
  }

  private void assign(RegionAssignment assignment) {
    minX = assignment.minXz().x();
    minZ = assignment.minXz().z();
    size = assignment.maxXz().x() - minX + 1;
    assigned = true;
  }

  private void applySnapshot(WorldSnapshot snapshot) {
    if (!assigned) {
      violations.add(id + ": snapshot before region assignment");
      return;
    }
    int columns = size * size;
    if (snapshot.runCountsLength() != columns) {
      violations.add(
          id
              + ": snapshot covers "
              + snapshot.runCountsLength()
              + " columns, region has "
              + columns);
      return;
    }

    CellRun run = new CellRun();
    int columnHeight = 0;
    for (int r = 0; r < snapshot.runCounts(0); r++) {
      columnHeight += snapshot.runs(run, r).length();
    }

    baseY = snapshot.baseY();
    height = columnHeight;
    cells = new byte[columns * height];
    int runIndex = 0;
    for (int column = 0; column < columns; column++) {
      int y = 0;
      for (int r = 0; r < snapshot.runCounts(column); r++) {
        snapshot.runs(run, runIndex++);
        for (int i = 0; i < run.length(); i++) {
          if (y >= height) {
            violations.add(id + ": column " + column + " overflows region height");
            return;
          }
          cells[column * height + y++] = (byte) run.nodeType();
        }
      }
      if (y != height) {
        violations.add(id + ": column " + column + " tiles " + y + " of " + height + " blocks");
        return;
      }
    }
    lastSequence = snapshot.sequence();
  }

  private void applyDelta(WorldDelta delta) {
    if (cells.length == 0) {
      violations.add(id + ": delta " + delta.sequence() + " before any snapshot");
      return;
    }
    if (delta.sequence() != lastSequence + 1) {
      violations.add(
          id + ": world sequence gap, expected " + (lastSequence + 1) + " got " + delta.sequence());
    }
    lastSequence = Math.max(lastSequence, delta.sequence());

    CellDelta cell = new CellDelta();
    for (int i = 0; i < delta.cellsLength(); i++) {
      delta.cells(cell, i);
      Vec3i pos = cell.pos();
      int index = index(pos.x(), pos.y(), pos.z());
      if (index >= 0) {
        cells[index] = (byte) cell.nodeType();
      }
    }
  }

  private void enqueue(PathRequest request) {
    long requestId = request.requestId();
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
            (int) request.mobId(),
            new Pos(request.start().x(), request.start().y(), request.start().z()),
            new Pos(request.target().x(), request.target().y(), request.target().z()),
            request.worldSequence()));
  }

  private void pong(Ping ping) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int pong = Pong.createPong(builder, ping.sequence(), ping.sendMicros(), queue.size());
    builder.finish(ResponseFrame.createResponseFrame(builder, ResponseMsg.Pong, pong));
    transport.toController(id, Channel.CONTROL, ByteBuffer.wrap(builder.sizedByteArray()));
  }

  private byte[] buildResponse(Pending pending, long tick) {
    List<Pos> path = straightLine(pending.start(), pending.target());
    boolean reached = !path.isEmpty() && path.get(path.size() - 1).equals(pending.target());

    FlatBufferBuilder builder = new FlatBufferBuilder(256);
    PathResponse.startNodesVector(builder, path.size());
    for (int i = path.size() - 1; i >= 0; i--) {
      Pos node = path.get(i);
      Vec3i.createVec3i(builder, node.x(), node.y(), node.z());
    }
    int nodes = builder.endVector();

    PathResponse.startPathResponse(builder);
    PathResponse.addRequestId(builder, pending.requestId());
    PathResponse.addReached(builder, reached);
    PathResponse.addNodes(builder, nodes);
    PathResponse.addCost(builder, path.size());
    PathResponse.addComputedTick(builder, tick);
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
    int dx = x - minX;
    int dz = z - minZ;
    int dy = y - baseY;
    if (dx < 0 || dx >= size || dz < 0 || dz >= size || dy < 0 || dy >= height) {
      return -1;
    }
    return (dx * size + dz) * height + dy;
  }
}
