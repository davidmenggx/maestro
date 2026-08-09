package com.smpler.maestro.controller.sim;

import com.google.flatbuffers.FlatBufferBuilder;
import com.smpler.maestro.controller.env.ControllerEnv;
import com.smpler.maestro.controller.env.NodeId;
import com.smpler.maestro.controller.env.Transport;
import com.smpler.maestro.controller.env.Transport.Channel;
import com.smpler.maestro.controller.world.Pos;
import com.smpler.maestro.controller.world.WorldEvent;
import com.smpler.maestro.controller.world.WorldInput;
import com.smpler.maestro.controller.world.WorldOutput;
import com.smpler.maestro.controller.world.WorldOutput.DropReason;
import com.smpler.maestro.controller.world.WorldSource;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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
 * exists to test later.
 *
 * <p>The one non-obvious part is the outbox. Encoding is offloaded, completions come back in an
 * order the controller does not choose, and the world stream must still leave in sequence order, so
 * frames are parked by sequence and drained in order.
 */
public final class ProbeController implements WorldInput, Transport.Listener {

  private static final int HEARTBEAT_TICKS = 20;

  /** A request that cannot be answered within this many ticks is not worth sending. */
  public static final int MAX_TICKS_LATE = 10;

  private record Outstanding(int mobId, NodeId node, long issuedTick) {}

  private record Queued(long requestId, int mobId, short classId, Pos start, Pos target) {}

  private final ControllerEnv env;
  private final WorldSource world;
  private final WorldOutput out;
  private final RegionMap regions;
  private final int nodeCapacityPerTick;

  private final Map<Integer, Short> mobs = new LinkedHashMap<>();
  private final Map<Integer, Pos> players = new LinkedHashMap<>();
  private final Map<Long, Outstanding> outstanding = new LinkedHashMap<>();
  private final Map<Integer, Long> requestByMob = new LinkedHashMap<>();
  private final Set<Long> loadedChunks = new LinkedHashSet<>();
  private final Set<NodeId> connected = new LinkedHashSet<>();
  private final Set<Integer> snapshotted = new LinkedHashSet<>();
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
      ControllerEnv env,
      WorldSource world,
      WorldOutput out,
      RegionMap regions,
      int nodeCapacityPerTick) {
    this.env = env;
    this.world = world;
    this.out = out;
    this.regions = regions;
    this.nodeCapacityPerTick = nodeCapacityPerTick;
    env.transport().listen(this);
  }

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
      case WorldEvent.PlayerMoved e -> players.put(e.playerId(), e.pos());
      case WorldEvent.PlayerLeft e -> players.remove(e.playerId());
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
    List<Long> lost =
        outstanding.entrySet().stream()
            .filter(e -> e.getValue().node().equals(node))
            .map(Map.Entry::getKey)
            .toList();
    for (Long requestId : lost) {
      Outstanding dropped = outstanding.remove(requestId);
      requestByMob.remove(dropped.mobId());
      out.pathDropped(dropped.mobId(), DropReason.NODE_LOST);
    }
  }

  @Override
  public void onFrame(NodeId node, Channel channel, ByteBuffer frame) {
    ResponseFrame response = ResponseFrame.getRootAsResponseFrame(frame);
    switch (response.bodyType()) {
      case ResponseMsg.PathResponse ->
          onPathResponse((PathResponse) response.body(new PathResponse()));
      case ResponseMsg.Pong ->
          queueDepth.put(node, (int) ((Pong) response.body(new Pong())).queueDepth());
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
   */
  private void expireOutstanding(long tick) {
    List<Long> expired =
        outstanding.entrySet().stream()
            .filter(e -> tick - e.getValue().issuedTick() > MAX_TICKS_LATE)
            .map(Map.Entry::getKey)
            .toList();
    for (Long requestId : expired) {
      Outstanding dropped = outstanding.remove(requestId);
      requestByMob.remove(dropped.mobId());
      env.metrics().count("path.expired", 1);
      out.pathDropped(dropped.mobId(), DropReason.SHED);
    }
  }

  private void onMobRemoved(int mobId) {
    mobs.remove(mobId);
    Long requestId = requestByMob.remove(mobId);
    if (requestId != null && outstanding.remove(requestId) != null) {
      out.pathDropped(mobId, DropReason.MOB_GONE);
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

    // Heartbeats are far apart, so the reported depth is only a lower bound. What
    // the controller already has in flight to that node is the better estimate.
    List<Queued> queued = pendingRequests.computeIfAbsent(node, k -> new ArrayList<>());
    long inFlight = outstanding.values().stream().filter(o -> o.node().equals(node)).count();
    long depth = Math.max(queueDepth.getOrDefault(node, 0), inFlight) + 1;
    if (depth > (long) MAX_TICKS_LATE * nodeCapacityPerTick) {
      env.metrics().count("path.rejected", 1, "reason", "queue_depth");
      out.pathDropped(mobId, DropReason.QUEUE_DEPTH);
      return;
    }

    // One outstanding request per mob. The older one is reported dropped rather
    // than left dangling, so every request still ends in exactly one callback.
    Long previous = requestByMob.get(mobId);
    if (previous != null && outstanding.remove(previous) != null) {
      out.pathDropped(mobId, DropReason.SUPERSEDED);
    }

    long requestId = nextRequestId++;
    requestByMob.put(mobId, requestId);
    outstanding.put(requestId, new Outstanding(mobId, node, event.tick()));
    queued.add(new Queued(requestId, mobId, classId, event.start(), event.target()));
  }

  private void onPathResponse(PathResponse response) {
    Outstanding request = outstanding.remove(response.requestId());
    if (request == null) {
      env.metrics().count("path.response.stale", 1);
      return;
    }
    requestByMob.remove(request.mobId());

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

  private void snapshotReadyRegions() {
    for (int region = 0; region < regions.count(); region++) {
      NodeId node = regions.owner(region);
      if (snapshotted.contains(region) || !connected.contains(node) || !regionLoaded(region)) {
        continue;
      }
      snapshotted.add(region);
      // Read on the main thread, encode off it: `WorldSource` is only safe here.
      int[] runCounts = new int[regions.regionSize() * regions.regionSize()];
      List<int[]> runs = readRegionRuns(world, regions, region, runCounts);
      long sequence = lastSequence.merge(node, 1L, Long::sum);
      offload(node, sequence, "snapshot", () -> encodeSnapshot(sequence, runCounts, runs));
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
    for (int x = regions.minX(region); x < regions.minX(region) + size; x += 16) {
      for (int z = regions.minZ(region); z < regions.minZ(region) + size; z += 16) {
        if (!loadedChunks.contains(chunkKey(x >> 4, z >> 4))) {
          return false;
        }
      }
    }
    return true;
  }

  /** Region snapshot as the controller would build it, exposed so tests can recompute one. */
  static byte[] snapshotFrame(WorldSource world, RegionMap regions, int region, long sequence) {
    int[] runCounts = new int[regions.regionSize() * regions.regionSize()];
    return encodeSnapshot(sequence, runCounts, readRegionRuns(world, regions, region, runCounts));
  }

  private static List<int[]> readRegionRuns(
      WorldSource world, RegionMap regions, int region, int[] runCounts) {
    int size = regions.regionSize();
    int minX = regions.minX(region);
    int minZ = regions.minZ(region);
    List<int[]> runs = new ArrayList<>();
    for (int dx = 0; dx < size; dx++) {
      for (int dz = 0; dz < size; dz++) {
        int column = dx * size + dz;
        int count = 0;
        int runLength = 0;
        byte runType = 0;
        for (int dy = 0; dy < world.height(); dy++) {
          byte type = world.pathTypeAt(minX + dx, world.minY() + dy, minZ + dz);
          if (runLength > 0 && type == runType) {
            runLength++;
            continue;
          }
          if (runLength > 0) {
            runs.add(new int[] {runLength, runType});
            count++;
          }
          runType = type;
          runLength = 1;
        }
        runs.add(new int[] {runLength, runType});
        runCounts[column] = count + 1;
      }
    }
    return runs;
  }

  private void send(NodeId node, Channel channel, byte[] frame) {
    env.metrics().record("frame.bytes", frame.length, "channel", channel.name());
    env.transport().send(node, channel, ByteBuffer.wrap(frame));
  }

  private static byte[] encodeSnapshot(long sequence, int[] runCounts, List<int[]> runs) {
    FlatBufferBuilder builder = new FlatBufferBuilder(4096);
    int counts = WorldSnapshot.createRunCountsVector(builder, runCounts);
    WorldSnapshot.startRunsVector(builder, runs.size());
    for (int i = runs.size() - 1; i >= 0; i--) {
      CellRun.createCellRun(builder, runs.get(i)[0], runs.get(i)[1]);
    }
    int runsVector = builder.endVector();

    WorldSnapshot.startWorldSnapshot(builder);
    WorldSnapshot.addSequence(builder, sequence);
    WorldSnapshot.addBaseY(builder, SimWorld.MIN_Y);
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
