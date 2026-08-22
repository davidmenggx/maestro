package io.github.davidmenggx.maestro.controller.sim;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
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
import maestro.fbs.Vec2i;
import maestro.fbs.Vec3i;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;
import maestro.fbs.WorldSnapshot;

/**
 * Minimal controller used to prove the seams carry a real workload: it assigns regions, snapshots
 * them, streams deltas in sequence, batches requests per tick and applies responses. Sharding,
 * failover and the failure detector are deliberately absent, since those are the logic this harness
 * exists to test.
 *
 * <p>The one non-obvious part is the outbox. Encoding is offloaded, completions come back in an
 * order the controller does not choose, and the world stream must still leave in sequence order, so
 * frames are parked by sequence and drained in order.
 */
public final class ProbeController implements WorldInput, Transport.Listener {

  /**
   * A request that cannot be answered within this many ticks is not worth sending or waiting for.
   */
  public static final int MAX_TICKS_LATE = 10;

  private static final int HEARTBEAT_TICKS = 20;
  private static final int CHUNK_SIZE = 16;

  private record Outstanding(int mobId, NodeId node, long issuedTick) {}

  private record Queued(long requestId, int mobId, short classId, Pos start, Pos target) {}

  private final ControllerEnv env;
  private final WorldSource world;
  private final WorldOutput out;
  private final RegionMap regions;
  private final int nodeCapacity;

  private final Map<Integer, Short> mobs = new LinkedHashMap<>();
  private final Set<Long> loadedChunks = new LinkedHashSet<>();
  private final Set<NodeId> connected = new LinkedHashSet<>();
  private final Set<Integer> snapshotted = new LinkedHashSet<>();

  /** Insertion ordered, and request ids only grow, so this is also issue-tick order. */
  private final Map<Long, Outstanding> outstanding = new LinkedHashMap<>();

  private final Map<Integer, Long> requestByMob = new LinkedHashMap<>();
  private final Map<NodeId, Integer> inFlight = new LinkedHashMap<>();
  private final Map<NodeId, Integer> queueDepth = new LinkedHashMap<>();
  private final Map<NodeId, Long> lastSequence = new LinkedHashMap<>();
  private final Map<NodeId, Long> nextToSend = new LinkedHashMap<>();
  private final Map<NodeId, Integer> generation = new LinkedHashMap<>();
  private final Map<NodeId, TreeMap<Long, byte[]>> outbox = new LinkedHashMap<>();
  private final List<WorldEvent.BlockChanged> pendingCells = new ArrayList<>();
  private final Map<NodeId, List<Queued>> pendingRequests = new LinkedHashMap<>();

  private long nextRequestId = 1;
  private long pingSequence;
  private int requestsIssued;

  public ProbeController(
      ControllerEnv env, WorldSource world, WorldOutput out, RegionMap regions, int nodeCapacity) {
    this.env = env;
    this.world = world;
    this.out = out;
    this.regions = regions;
    this.nodeCapacity = nodeCapacity;
    env.transport().listen(this);
  }

  // Assertion surface for the harness. Everything below this point is behaviour.

  public int requestsIssued() {
    return requestsIssued;
  }

  public int outstandingCount() {
    return outstanding.size();
  }

  @Override
  public void accept(WorldEvent event) {
    switch (event) {
      case WorldEvent.TickBegan e -> onTickBegan(e.tick());
      case WorldEvent.TickEnded ignored -> onTickEnded();
      case WorldEvent.BlockChanged e -> pendingCells.add(e);
      case WorldEvent.ChunkLoaded e -> loadedChunks.add(chunkKey(e.chunkX(), e.chunkZ()));
      case WorldEvent.ChunkUnloaded e -> loadedChunks.remove(chunkKey(e.chunkX(), e.chunkZ()));
      case WorldEvent.MobSpawned e -> mobs.put(e.mobId(), e.classId());
      case WorldEvent.MobRemoved e -> onMobRemoved(e.mobId());
      case WorldEvent.PathRequested e -> onPathRequested(e);
      // Consumed once shedding and failover priorities exist; both order by
      // distance to the nearest player.
      case WorldEvent.PlayerMoved ignored -> {}
      case WorldEvent.PlayerLeft ignored -> {}
    }
  }

  @Override
  public void onConnected(NodeId node) {
    connected.add(node);
    reset(node);
    // On the world channel, not the control one: the assignment has to reach the
    // node ahead of the snapshot it frames, and only one channel orders them.
    send(node, Channel.WORLD, encodeAssignment(regions, regions.indexOf(node)));
  }

  @Override
  public void onDisconnected(NodeId node) {
    connected.remove(node);
    reset(node);
    dropWhere(node, DropReason.NODE_LOST);
  }

  @Override
  public void onFrame(NodeId node, Channel channel, ByteBuffer frame) {
    ResponseFrame response = ResponseFrame.getRootAsResponseFrame(frame);
    switch (response.bodyType()) {
      case ResponseMsg.PathResponse ->
          onPathResponse((PathResponse) response.body(new PathResponse()));
      case ResponseMsg.Pong -> {
        Pong pong = (Pong) response.body(new Pong());
        queueDepth.put(node, (int) pong.queueDepth());
      }
      default -> env.metrics().count("controller.unknown.response", 1);
    }
  }

  private void onTickBegan(long tick) {
    expireOutstanding(tick);
    if (tick % HEARTBEAT_TICKS != 0) {
      return;
    }
    for (NodeId node : regions.nodes()) {
      if (connected.contains(node)) {
        send(node, Channel.CONTROL, encodePing(++pingSequence));
      } else {
        env.transport().connect(node, "sim://" + node);
      }
    }
  }

  private void onTickEnded() {
    snapshotReadyRegions();
    flushCells();
    flushRequests();
    env.metrics().gauge("controller.outstanding", outstanding.size());
  }

  /**
   * A request nobody answered within the budget is given up on. A lost frame on the request stream
   * ends here, and so does a request to a node that went silent without resetting its connection.
   * {@code outstanding} is in issue order, so the scan stops at the first live entry.
   */
  private void expireOutstanding(long tick) {
    Iterator<Map.Entry<Long, Outstanding>> entries = outstanding.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<Long, Outstanding> entry = entries.next();
      if (tick - entry.getValue().issuedTick() <= MAX_TICKS_LATE) {
        return;
      }
      entries.remove();
      forget(entry.getValue());
      env.metrics().count("path.expired", 1);
      out.pathDropped(entry.getValue().mobId(), DropReason.SHED);
    }
  }

  private void onMobRemoved(int mobId) {
    mobs.remove(mobId);
    Long requestId = requestByMob.get(mobId);
    if (requestId != null) {
      drop(requestId, DropReason.MOB_GONE);
    }
  }

  private void onPathRequested(WorldEvent.PathRequested event) {
    requestsIssued++;
    int mobId = event.mobId();
    Short classId = mobs.get(mobId);
    if (classId == null) {
      out.pathDropped(mobId, DropReason.MOB_GONE);
      return;
    }

    int region = regions.regionIndex(event.start());
    if (region < 0 || !snapshotted.contains(region)) {
      out.pathDropped(mobId, DropReason.NO_NODE);
      return;
    }
    NodeId node = regions.owner(region);
    if (!connected.contains(node)) {
      out.pathDropped(mobId, DropReason.NO_NODE);
      return;
    }

    // Heartbeats are far apart, so a reported depth is only a lower bound. What
    // the controller already has in flight to that node is the better estimate.
    long depth = Math.max(queueDepth.getOrDefault(node, 0), inFlight.getOrDefault(node, 0)) + 1;
    if (depth > (long) MAX_TICKS_LATE * nodeCapacity) {
      env.metrics().count("path.rejected", 1, "reason", "queue_depth");
      out.pathDropped(mobId, DropReason.QUEUE_DEPTH);
      return;
    }

    // One outstanding request per mob. The older one is reported dropped rather
    // than left dangling, so every request still ends in exactly one callback.
    Long previous = requestByMob.get(mobId);
    if (previous != null) {
      drop(previous, DropReason.SUPERSEDED);
    }

    long requestId = nextRequestId++;
    requestByMob.put(mobId, requestId);
    outstanding.put(requestId, new Outstanding(mobId, node, event.tick()));
    inFlight.merge(node, 1, Integer::sum);
    pendingRequests
        .computeIfAbsent(node, k -> new ArrayList<>())
        .add(new Queued(requestId, mobId, classId, event.start(), event.target()));
  }

  private void onPathResponse(PathResponse response) {
    Outstanding request = outstanding.remove(response.requestId());
    if (request == null) {
      // A retry, or the answer to a request already superseded or given up on.
      env.metrics().count("path.response.stale", 1);
      return;
    }
    forget(request);

    List<Pos> nodes = new ArrayList<>(response.nodesLength());
    Vec3i scratch = new Vec3i();
    for (int i = 0; i < response.nodesLength(); i++) {
      response.nodes(scratch, i);
      nodes.add(new Pos(scratch.x(), scratch.y(), scratch.z()));
    }
    env.metrics().record("path.latency.ticks", env.clock().tick() - request.issuedTick());
    out.pathComputed(
        new WorldOutput.PathResult(
            request.mobId(),
            response.reached(),
            nodes,
            response.cost(),
            response.computedTick(),
            request.issuedTick()));
  }

  /** Drops one known request id. */
  private void drop(long requestId, DropReason reason) {
    Outstanding request = outstanding.remove(requestId);
    if (request == null) {
      return;
    }
    forget(request);
    out.pathDropped(request.mobId(), reason);
  }

  private void dropWhere(NodeId node, DropReason reason) {
    List<Long> matching = new ArrayList<>();
    for (Map.Entry<Long, Outstanding> entry : outstanding.entrySet()) {
      if (entry.getValue().node().equals(node)) {
        matching.add(entry.getKey());
      }
    }
    for (Long requestId : matching) {
      drop(requestId, reason);
    }
  }

  /**
   * Releases the per-mob and per-node bookkeeping for a request already out of {@code outstanding}.
   */
  private void forget(Outstanding request) {
    requestByMob.remove(request.mobId());
    inFlight.merge(request.node(), -1, Integer::sum);
  }

  private void snapshotReadyRegions() {
    for (int region = 0; region < regions.count(); region++) {
      NodeId node = regions.owner(region);
      if (snapshotted.contains(region) || !connected.contains(node) || !regionLoaded(region)) {
        continue;
      }
      snapshotted.add(region);
      // Read on the main thread, encode off it: `WorldSource` is only safe here.
      int columns = regions.regionSize() * regions.regionSize();
      int[] runCounts = new int[columns];
      Runs runs = readRegionRuns(world, regions, region, runCounts);
      int baseY = world.minY();
      long sequence = lastSequence.merge(node, 1L, Long::sum);
      offload(node, sequence, "snapshot", () -> encodeSnapshot(sequence, baseY, runCounts, runs));
    }
  }

  private void flushCells() {
    if (pendingCells.isEmpty()) {
      return;
    }
    Map<NodeId, List<WorldEvent.BlockChanged>> byNode = new LinkedHashMap<>();
    for (WorldEvent.BlockChanged change : pendingCells) {
      int region = regions.regionIndex(change.pos());
      if (region < 0 || !snapshotted.contains(region)) {
        continue;
      }
      byNode.computeIfAbsent(regions.owner(region), k -> new ArrayList<>()).add(change);
    }
    pendingCells.clear();

    for (Map.Entry<NodeId, List<WorldEvent.BlockChanged>> entry : byNode.entrySet()) {
      NodeId node = entry.getKey();
      if (!connected.contains(node)) {
        continue;
      }
      List<WorldEvent.BlockChanged> cells = entry.getValue();
      long sequence = lastSequence.merge(node, 1L, Long::sum);
      offload(node, sequence, "delta", () -> encodeDelta(sequence, cells));
    }
  }

  private void flushRequests() {
    for (Map.Entry<NodeId, List<Queued>> entry : pendingRequests.entrySet()) {
      NodeId node = entry.getKey();
      List<Queued> batch = List.copyOf(entry.getValue());
      entry.getValue().clear();
      if (batch.isEmpty() || !connected.contains(node)) {
        continue;
      }
      long sequence = lastSequence.getOrDefault(node, 0L);
      env.offload()
          .submit(
              "requests",
              () -> encodeRequests(batch, sequence),
              frame -> send(node, Channel.REQUEST, frame));
    }
  }

  /**
   * Parks the encoded frame under its sequence and sends whatever prefix is now contiguous. Without
   * this, two encodes finishing out of order would put a gap on an ordered stream.
   */
  private void offload(NodeId node, long sequence, String job, Supplier<byte[]> work) {
    int epoch = generation.getOrDefault(node, 0);
    env.offload()
        .submit(
            job,
            work,
            frame -> {
              if (generation.getOrDefault(node, 0) != epoch) {
                return;
              }
              outbox.computeIfAbsent(node, k -> new TreeMap<>()).put(sequence, frame);
              drainOutbox(node);
            });
  }

  private void drainOutbox(NodeId node) {
    TreeMap<Long, byte[]> pending = outbox.get(node);
    long next = nextToSend.getOrDefault(node, 1L);
    while (pending != null && pending.containsKey(next)) {
      send(node, Channel.WORLD, pending.remove(next));
      next++;
    }
    nextToSend.put(node, next);
  }

  private void reset(NodeId node) {
    generation.merge(node, 1, Integer::sum);
    lastSequence.put(node, 0L);
    nextToSend.put(node, 1L);
    outbox.remove(node);
    queueDepth.remove(node);
    pendingRequests.remove(node);
    int region = regions.indexOf(node);
    if (region >= 0) {
      snapshotted.remove(region);
    }
  }

  private boolean regionLoaded(int region) {
    int size = regions.regionSize();
    for (int x = regions.minX(region); x < regions.minX(region) + size; x += CHUNK_SIZE) {
      for (int z = regions.minZ(region); z < regions.minZ(region) + size; z += CHUNK_SIZE) {
        if (!loadedChunks.contains(chunkKey(x / CHUNK_SIZE, z / CHUNK_SIZE))) {
          return false;
        }
      }
    }
    return true;
  }

  private void send(NodeId node, Channel channel, byte[] frame) {
    env.metrics().record("frame.bytes", frame.length, "channel", channel.name());
    env.transport().send(node, channel, ByteBuffer.wrap(frame));
  }

  /** Region snapshot as the controller would build it, exposed so tests can recompute one. */
  static byte[] snapshotFrame(WorldSource world, RegionMap regions, int region, long sequence) {
    int[] runCounts = new int[regions.regionSize() * regions.regionSize()];
    Runs runs = readRegionRuns(world, regions, region, runCounts);
    return encodeSnapshot(sequence, world.minY(), runCounts, runs);
  }

  /**
   * Column runs packed one per int as {@code (length << 8) | pathType}, which keeps a full-height
   * region's worth of runs out of the allocator.
   */
  private static final class Runs {

    private int[] packed = new int[64];
    private int count;

    void add(int length, byte pathType) {
      if (count == packed.length) {
        packed = Arrays.copyOf(packed, count * 2);
      }
      packed[count++] = (length << 8) | (pathType & 0xFF);
    }

    int length(int index) {
      return packed[index] >>> 8;
    }

    int pathType(int index) {
      return packed[index] & 0xFF;
    }
  }

  private static Runs readRegionRuns(
      WorldSource world, RegionMap regions, int region, int[] runCounts) {
    int size = regions.regionSize();
    int minX = regions.minX(region);
    int minZ = regions.minZ(region);
    int height = world.height();
    Runs runs = new Runs();
    for (int dx = 0; dx < size; dx++) {
      for (int dz = 0; dz < size; dz++) {
        int count = 0;
        int runLength = 0;
        byte runType = 0;
        for (int dy = 0; dy < height; dy++) {
          byte type = world.pathTypeAt(minX + dx, world.minY() + dy, minZ + dz);
          if (runLength > 0 && type == runType) {
            runLength++;
            continue;
          }
          if (runLength > 0) {
            runs.add(runLength, runType);
            count++;
          }
          runType = type;
          runLength = 1;
        }
        runs.add(runLength, runType);
        runCounts[dx * size + dz] = count + 1;
      }
    }
    return runs;
  }

  private static byte[] encodeSnapshot(long sequence, int baseY, int[] runCounts, Runs runs) {
    FlatBufferBuilder builder = new FlatBufferBuilder(4096);
    int counts = WorldSnapshot.createRunCountsVector(builder, runCounts);
    WorldSnapshot.startRunsVector(builder, runs.count);
    for (int i = runs.count - 1; i >= 0; i--) {
      CellRun.createCellRun(builder, runs.length(i), runs.pathType(i));
    }
    int runsVector = builder.endVector();

    WorldSnapshot.startWorldSnapshot(builder);
    WorldSnapshot.addSequence(builder, sequence);
    WorldSnapshot.addBaseY(builder, baseY);
    WorldSnapshot.addRunCounts(builder, counts);
    WorldSnapshot.addRuns(builder, runsVector);
    int snapshot = WorldSnapshot.endWorldSnapshot(builder);

    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.WorldSnapshot, snapshot));
    return builder.sizedByteArray();
  }

  static byte[] encodeDelta(long sequence, List<WorldEvent.BlockChanged> cells) {
    FlatBufferBuilder builder = new FlatBufferBuilder(256);
    WorldDelta.startCellsVector(builder, cells.size());
    for (int i = cells.size() - 1; i >= 0; i--) {
      WorldEvent.BlockChanged cell = cells.get(i);
      CellDelta.createCellDelta(
          builder, cell.pos().x(), cell.pos().y(), cell.pos().z(), cell.pathType());
    }
    int vector = builder.endVector();
    int delta = WorldDelta.createWorldDelta(builder, sequence, vector);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.WorldDelta, delta));
    return builder.sizedByteArray();
  }

  private static byte[] encodeRequests(List<Queued> batch, long worldSequence) {
    FlatBufferBuilder builder = new FlatBufferBuilder(512);
    int[] offsets = new int[batch.size()];
    for (int i = 0; i < batch.size(); i++) {
      Queued queued = batch.get(i);
      PathRequest.startPathRequest(builder);
      PathRequest.addRequestId(builder, queued.requestId());
      PathRequest.addMobId(builder, queued.mobId());
      PathRequest.addClassId(builder, queued.classId());
      PathRequest.addStart(
          builder,
          Vec3i.createVec3i(builder, queued.start().x(), queued.start().y(), queued.start().z()));
      PathRequest.addTarget(
          builder,
          Vec3i.createVec3i(
              builder, queued.target().x(), queued.target().y(), queued.target().z()));
      PathRequest.addWorldSequence(builder, worldSequence);
      offsets[i] = PathRequest.endPathRequest(builder);
    }
    int vector = PathRequestBatch.createRequestsVector(builder, offsets);
    PathRequestBatch.startPathRequestBatch(builder);
    PathRequestBatch.addRequests(builder, vector);
    int batchOffset = PathRequestBatch.endPathRequestBatch(builder);
    builder.finish(
        RequestFrame.createRequestFrame(builder, RequestMsg.PathRequestBatch, batchOffset));
    return builder.sizedByteArray();
  }

  static byte[] encodeAssignment(RegionMap regions, int region) {
    FlatBufferBuilder builder = new FlatBufferBuilder(128);
    int size = regions.regionSize();
    RegionAssignment.startRegionAssignment(builder);
    RegionAssignment.addRegionId(builder, region);
    RegionAssignment.addMinXz(
        builder, Vec2i.createVec2i(builder, regions.minX(region), regions.minZ(region)));
    RegionAssignment.addMaxXz(
        builder,
        Vec2i.createVec2i(
            builder, regions.minX(region) + size - 1, regions.minZ(region) + size - 1));
    RegionAssignment.addResumeFromSequence(builder, 0);
    int assignment = RegionAssignment.endRegionAssignment(builder);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.RegionAssignment, assignment));
    return builder.sizedByteArray();
  }

  private byte[] encodePing(long sequence) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    Ping.startPing(builder);
    Ping.addSequence(builder, sequence);
    Ping.addSendMicros(builder, env.clock().nowNanos() / 1000);
    int ping = Ping.endPing(builder);
    builder.finish(ControlFrame.createControlFrame(builder, ControlMsg.Ping, ping));
    return builder.sizedByteArray();
  }

  private static long chunkKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
  }
}
