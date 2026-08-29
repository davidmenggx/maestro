package io.github.davidmenggx.maestro.controller.sim;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.world.MobClass;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import maestro.fbs.CellDelta;
import maestro.fbs.CellRun;
import maestro.fbs.ControlFrame;
import maestro.fbs.ControlMsg;
import maestro.fbs.ControlReplyFrame;
import maestro.fbs.ControlReplyMsg;
import maestro.fbs.Hello;
import maestro.fbs.MobClassTable;
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
 * failover and the failure detector are absent: those are the logic this harness exists to test.
 *
 * <p>Encoding is offloaded and can finish out of order, but the world stream must still leave in
 * sequence order, so finished frames wait in an outbox until the gap ahead of them is filled.
 */
public final class ProbeController implements WorldInput, Transport.Listener {

  /**
   * A request that cannot be answered within this many ticks is not worth sending or waiting for.
   */
  public static final int MAX_TICKS_LATE = 10;

  private static final int HEARTBEAT_TICKS = 20;

  /**
   * World frames kept per node so a reconnecting node can be replayed forward instead of
   * resnapshotted. A node that fell further behind than this gets a fresh snapshot.
   */
  static final int RETENTION_FRAMES = 48;

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

  /** Connected and past the {@code Hello} handshake, so world frames may flow. */
  private final Set<NodeId> greeted = new LinkedHashSet<>();

  private final Set<Integer> snapshotted = new LinkedHashSet<>();

  /** Insertion ordered, and request ids only grow, so this is also issue-tick order. */
  private final Map<Long, Outstanding> outstanding = new LinkedHashMap<>();

  private final Map<Integer, Long> requestByMob = new LinkedHashMap<>();
  private final Map<NodeId, Integer> inFlight = new LinkedHashMap<>();
  private final Map<NodeId, Integer> queueDepth = new LinkedHashMap<>();
  private final Map<NodeId, Long> lastSequence = new LinkedHashMap<>();
  private final Map<NodeId, Long> nextToSend = new LinkedHashMap<>();

  /** Sequence of the snapshot each node's retained frames continue from. */
  private final Map<NodeId, Long> logBase = new LinkedHashMap<>();

  /** Highest sequence dropped from a node's outbox, past which resume is impossible. */
  private final Map<NodeId, Long> evicted = new LinkedHashMap<>();

  private final Map<NodeId, TreeMap<Long, byte[]>> outbox = new LinkedHashMap<>();
  private final List<WorldEvent.BlockChanged> pendingCells = new ArrayList<>();
  private final Map<NodeId, List<Queued>> pendingRequests = new LinkedHashMap<>();

  private final List<String> selfViolations = new ArrayList<>();

  private long nextRequestId = 1;
  private long pingSequence;
  private int requestsIssued;

  public ProbeController(
      ControllerEnv env, WorldSource world, WorldOutput out, RegionMap regions, int nodeCapacity) {
    if (nodeCapacity < 1) {
      throw new IllegalArgumentException("a node serves at least one request a tick");
    }
    this.env = Objects.requireNonNull(env, "env");
    this.world = Objects.requireNonNull(world, "world");
    this.out = Objects.requireNonNull(out, "out");
    this.regions = Objects.requireNonNull(regions, "regions");
    this.nodeCapacity = nodeCapacity;
    env.transport().listen(this);
  }

  // Assertion surface for the harness. Everything below this point is behavior.

  public int requestsIssued() {
    return requestsIssued;
  }

  public int outstandingCount() {
    return outstanding.size();
  }

  /**
   * Internal bookkeeping that has to agree: one mob mapping per outstanding request, per-node
   * in-flight counters that sum to the total, and an admission bound that was never exceeded.
   */
  public List<String> selfCheck() {
    List<String> found = new ArrayList<>(selfViolations);
    for (NodeId node : greeted) {
      if (!connected.contains(node)) {
        found.add(node + " is greeted but not connected");
      }
    }
    for (Map.Entry<NodeId, TreeMap<Long, byte[]>> entry : outbox.entrySet()) {
      long held = entry.getValue().size();
      if (held > RETENTION_FRAMES) {
        found.add(entry.getKey() + " retains " + held + " frames, past " + RETENTION_FRAMES);
      }
    }
    if (requestByMob.size() != outstanding.size()) {
      found.add(
          "mob index holds "
              + requestByMob.size()
              + " but "
              + outstanding.size()
              + " requests are outstanding");
    }
    for (Map.Entry<Integer, Long> entry : requestByMob.entrySet()) {
      Outstanding request = outstanding.get(entry.getValue());
      if (request == null || request.mobId() != entry.getKey()) {
        found.add("mob " + entry.getKey() + " points at a request that is not outstanding");
      }
    }
    int counted = inFlight.values().stream().mapToInt(Integer::intValue).sum();
    if (counted != outstanding.size()) {
      found.add("in-flight counters sum to " + counted + ", outstanding is " + outstanding.size());
    }
    for (Map.Entry<NodeId, Integer> entry : inFlight.entrySet()) {
      if (entry.getValue() < 0) {
        found.add(entry.getKey() + " has a negative in-flight count: " + entry.getValue());
      }
    }
    return found;
  }

  @Override
  public void accept(WorldEvent event) {
    switch (event) {
      case WorldEvent.TickBegan e -> onTickBegan(e.tick());
      case WorldEvent.TickEnded ignored -> onTickEnded();
      case WorldEvent.BlockChanged e -> pendingCells.add(e);
      case WorldEvent.ChunkLoaded e -> loadedChunks.add(chunkKey(e.chunkX(), e.chunkZ()));
      case WorldEvent.ChunkUnloaded e -> onChunkUnloaded(e.chunkX(), e.chunkZ());
      case WorldEvent.MobSpawned e -> mobs.put(e.mobId(), e.classId());
      case WorldEvent.MobRemoved e -> onMobRemoved(e.mobId());
      case WorldEvent.PathRequested e -> onPathRequested(e);
      case WorldEvent.PathCanceled e -> onPathCanceled(e.mobId());
      // Consumed once shedding and failover priorities exist; both order by
      // distance to the nearest player.
      case WorldEvent.PlayerMoved ignored -> {}
      case WorldEvent.PlayerLeft ignored -> {}
    }
  }

  @Override
  public void onConnected(NodeId node) {
    connected.add(node);
    // Nothing is sent yet. Until the node says how far its world stream got,
    // the controller cannot tell a replay from a resnapshot.
    resetConnection(node);
  }

  @Override
  public void onDisconnected(NodeId node) {
    connected.remove(node);
    greeted.remove(node);
    resetConnection(node);
    dropWhere(node, DropReason.NODE_LOST);
  }

  @Override
  public void onFrame(NodeId node, Channel channel, ByteBuffer frame) {
    switch (channel) {
      case REQUEST -> onResponse(frame);
      case CONTROL -> onControlReply(node, frame);
      case WORLD -> selfViolations.add(node + " sent a frame on the world channel");
    }
  }

  private void onResponse(ByteBuffer frame) {
    ResponseFrame response = ResponseFrame.getRootAsResponseFrame(frame);
    switch (response.bodyType()) {
      case ResponseMsg.PathResponse ->
          onPathResponse((PathResponse) response.body(new PathResponse()));
      default -> env.metrics().count("controller.unknown.response", 1);
    }
  }

  private void onControlReply(NodeId node, ByteBuffer frame) {
    ControlReplyFrame reply = ControlReplyFrame.getRootAsControlReplyFrame(frame);
    switch (reply.bodyType()) {
      case ControlReplyMsg.Hello -> onHello(node, (Hello) reply.body(new Hello()));
      case ControlReplyMsg.Pong -> {
        Pong pong = (Pong) reply.body(new Pong());
        queueDepth.put(node, (int) pong.queueDepth());
      }
      default -> env.metrics().count("controller.unknown.control.reply", 1);
    }
  }

  /**
   * Decides between replaying the world stream and starting it over. Replay needs the node to still
   * hold the region it was assigned, at a sequence this controller has not yet dropped from the
   * node's outbox and that sits at or after the snapshot the retained frames continue from.
   */
  private void onHello(NodeId node, Hello hello) {
    int region = regions.indexOf(node);
    long nodeSequence = hello.lastSequence();
    long produced = lastSequence.getOrDefault(node, 0L);
    boolean resume =
        nodeSequence > 0
            && region >= 0
            && hello.regionId() == region
            && snapshotted.contains(region)
            && nodeSequence <= produced
            && nodeSequence >= logBase.getOrDefault(node, Long.MAX_VALUE)
            && nodeSequence >= evicted.getOrDefault(node, 0L);

    if (resume) {
      env.metrics().count("world.resume", 1, "node", node.toString());
      nextToSend.put(node, nodeSequence + 1);
    } else {
      env.metrics().count("world.resnapshot", 1, "node", node.toString());
      nodeSequence = 0;
      outbox.remove(node);
      evicted.remove(node);
      logBase.remove(node);
      nextToSend.put(node, produced + 1);
      if (region >= 0) {
        snapshotted.remove(region);
      }
    }

    greeted.add(node);
    if (region >= 0) {
      // On the world channel, not the control one: the assignment has to reach the
      // node ahead of the frames it frames, and only one channel orders them.
      send(node, Channel.WORLD, encodeAssignment(regions, region, nodeSequence));
    }
    // Ordered channel, because a request naming a class the node has not been
    // told about cannot be answered.
    send(node, Channel.CONTROL, encodeMobClasses(world.mobClasses()));
    drainOutbox(node);
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

  /** The region's state is unknown until the chunk returns, so the stream stops and resnapshots. */
  private void onChunkUnloaded(int chunkX, int chunkZ) {
    loadedChunks.remove(chunkKey(chunkX, chunkZ));
    for (int region = 0; region < regions.count(); region++) {
      if (snapshotted.contains(region) && !regionLoaded(region)) {
        snapshotted.remove(region);
      }
    }
  }

  private void onPathCanceled(int mobId) {
    Long requestId = requestByMob.get(mobId);
    if (requestId != null) {
      drop(requestId, DropReason.CANCELED);
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
    if (!greeted.contains(node)) {
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
    int nowInFlight = inFlight.merge(node, 1, Integer::sum);
    if (nowInFlight > MAX_TICKS_LATE * nodeCapacity) {
      selfViolations.add(
          node
              + " holds "
              + nowInFlight
              + " requests, past the admission bound of "
              + (MAX_TICKS_LATE * nodeCapacity));
    }
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
      if (snapshotted.contains(region) || !greeted.contains(node) || !regionLoaded(region)) {
        continue;
      }
      snapshotted.add(region);
      // Read on the main thread, encode off it: `WorldSource` is only safe here.
      int columns = regions.regionSize() * regions.regionSize();
      int[] runCounts = new int[columns];
      Runs runs = readRegionRuns(world, regions, region, runCounts);
      int baseY = world.minY();
      long sequence = lastSequence.merge(node, 1L, Long::sum);
      // Frames before a snapshot describe a stream nobody can resume into now.
      logBase.put(node, sequence);
      TreeMap<Long, byte[]> pending = outbox.get(node);
      if (pending != null) {
        pending.headMap(sequence).clear();
      }
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
      // Produced even while the node is away: the stream belongs to the region, and
      // a node that reconnects inside the retention window is replayed from here.
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
      if (batch.isEmpty() || !greeted.contains(node)) {
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
   * Stores the finished frame under its sequence number, then sends every frame from the lowest
   * unsent sequence up to the first missing one. Encoding runs off the controller thread and can
   * finish out of order, so sending each frame as it finishes would leave gaps in the sequence.
   */
  private void offload(NodeId node, long sequence, String job, Supplier<byte[]> work) {
    env.offload()
        .submit(
            job,
            work,
            frame -> {
              outbox.computeIfAbsent(node, k -> new TreeMap<>()).put(sequence, frame);
              drainOutbox(node);
            });
  }

  /**
   * Advances the send pointer over every frame now present, transmitting only once the node has
   * been greeted. Frames stay behind the pointer as the replay window until they age out.
   */
  private void drainOutbox(NodeId node) {
    TreeMap<Long, byte[]> pending = outbox.get(node);
    if (pending == null) {
      return;
    }
    boolean sending = greeted.contains(node);
    long next = nextToSend.getOrDefault(node, 1L);
    while (pending.containsKey(next)) {
      if (sending) {
        send(node, Channel.WORLD, pending.get(next));
      }
      next++;
    }
    nextToSend.put(node, next);

    long lowest = next - RETENTION_FRAMES;
    while (!pending.isEmpty() && pending.firstKey() < lowest) {
      evicted.merge(node, pending.pollFirstEntry().getKey(), Math::max);
    }
  }

  /**
   * Clears what belonged to the connection. The world stream and its replay window belong to the
   * region, so both outlive a reconnect and the {@code Hello} handshake decides their fate.
   */
  private void resetConnection(NodeId node) {
    queueDepth.remove(node);
    pendingRequests.remove(node);
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

  static byte[] encodeAssignment(RegionMap regions, int region, long resumeFrom) {
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
    RegionAssignment.addResumeFromSequence(builder, resumeFrom);
    int assignment = RegionAssignment.endRegionAssignment(builder);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.RegionAssignment, assignment));
    return builder.sizedByteArray();
  }

  private static byte[] encodeMobClasses(List<MobClass> classes) {
    FlatBufferBuilder builder = new FlatBufferBuilder(512);
    int[] offsets = new int[classes.size()];
    for (int i = 0; i < classes.size(); i++) {
      MobClass profile = classes.get(i);
      int malus = maestro.fbs.MobClass.createMalusVector(builder, profile.malus());
      maestro.fbs.MobClass.startMobClass(builder);
      maestro.fbs.MobClass.addClassId(builder, profile.classId());
      maestro.fbs.MobClass.addWidth(builder, profile.width());
      maestro.fbs.MobClass.addHeight(builder, profile.height());
      maestro.fbs.MobClass.addMalus(builder, malus);
      maestro.fbs.MobClass.addMaxUpStep(builder, profile.maxUpStep());
      maestro.fbs.MobClass.addCanPassDoors(builder, profile.canPassDoors());
      maestro.fbs.MobClass.addCanOpenDoors(builder, profile.canOpenDoors());
      maestro.fbs.MobClass.addCanFloat(builder, profile.canFloat());
      maestro.fbs.MobClass.addCanWalkOverFences(builder, profile.canWalkOverFences());
      maestro.fbs.MobClass.addMaxFollowRange(builder, profile.maxFollowRange());
      offsets[i] = maestro.fbs.MobClass.endMobClass(builder);
    }
    int vector = MobClassTable.createClassesVector(builder, offsets);
    MobClassTable.startMobClassTable(builder);
    MobClassTable.addClasses(builder, vector);
    int table = MobClassTable.endMobClassTable(builder);
    builder.finish(ControlFrame.createControlFrame(builder, ControlMsg.MobClassTable, table));
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
