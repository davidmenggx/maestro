package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import maestro.fbs.ControlReplyFrame;
import maestro.fbs.ControlReplyMsg;
import maestro.fbs.Hello;
import maestro.fbs.PathNode;
import maestro.fbs.PathResponse;
import maestro.fbs.Pong;
import maestro.fbs.ResponseFrame;
import maestro.fbs.ResponseMsg;

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

  /** {@code target} is kept so the result can be handed back as a complete {@code Path}. */
  private record Outstanding(int mobId, NodeId node, long issuedTick, Pos target) {}

  /**
   * What the controller tracks about one node. The connection is only part of it: the world stream
   * and its replay window belong to the region and outlive a reconnect.
   */
  private static final class NodeState {

    private boolean connected;

    /** Past the {@code Hello} handshake, so world frames may flow. */
    private boolean greeted;

    private int inFlight;

    /** Depth the node last reported, which heartbeats make a lower bound. */
    private int reportedDepth;

    /** Highest world sequence produced for the node, sent or not. */
    private long lastSequence;

    /** Lowest sequence the outbox has not handed to the transport. */
    private long nextToSend = 1;

    /** Sequence of the snapshot the retained frames continue from. */
    private long logBase = Long.MAX_VALUE;

    /** Highest sequence dropped from the outbox, past which resume is impossible. */
    private long evicted;

    private final TreeMap<Long, byte[]> outbox = new TreeMap<>();
    private List<WorldEvent.BlockChanged> cells = new ArrayList<>();
    private List<Frames.Request> requests = new ArrayList<>();

    /** Forgets what belonged to the connection alone. */
    private void resetConnection() {
      reportedDepth = 0;
      requests.clear();
    }
  }

  private final ControllerEnv env;
  private final WorldSource world;
  private final WorldOutput out;
  private final RegionMap regions;
  private final int nodeCapacity;

  private final Map<Integer, Short> mobs = new LinkedHashMap<>();
  private final Set<Long> loadedChunks = new LinkedHashSet<>();
  private final Map<NodeId, NodeState> nodes = new LinkedHashMap<>();
  private final boolean[] snapshotted;

  /** Insertion ordered, and request ids only grow, so this is also issue-tick order. */
  private final Map<Long, Outstanding> outstanding = new LinkedHashMap<>();

  private final Map<Integer, Long> requestByMob = new LinkedHashMap<>();
  private final List<WorldEvent.BlockChanged> pendingCells = new ArrayList<>();
  private final List<String> selfViolations = new ArrayList<>();

  /** Identifies this controller process to the fleet; nothing from another run is resumed. */
  private final long run;

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
    this.run = env.rng().nextLong();
    this.snapshotted = new boolean[regions.count()];
    // Seeded in region order, so anything that iterates the fleet does so the
    // same way whatever order the nodes first spoke in.
    for (NodeId node : regions.nodes()) {
      nodes.put(node, new NodeState());
    }
    env.transport().listen(this);
  }

  // Assertion surface for the harness. Everything below this point is behavior.

  public int requestsIssued() {
    return requestsIssued;
  }

  public long run() {
    return run;
  }

  public int outstandingCount() {
    return outstanding.size();
  }

  /**
   * Highest world sequence produced for a node, whether or not it has been sent or acknowledged.
   */
  public long producedSequence(NodeId node) {
    return state(node).lastSequence;
  }

  /**
   * Internal bookkeeping that has to agree: one mob mapping per outstanding request, per-node
   * in-flight counters that sum to the total, and an admission bound that was never exceeded.
   */
  public List<String> selfCheck() {
    List<String> violations = new ArrayList<>(selfViolations);
    int counted = 0;
    for (Map.Entry<NodeId, NodeState> entry : nodes.entrySet()) {
      NodeId node = entry.getKey();
      NodeState state = entry.getValue();
      if (state.greeted && !state.connected) {
        violations.add(node + " is greeted but not connected");
      }
      if (state.outbox.size() > RETENTION_FRAMES) {
        violations.add(
            node + " retains " + state.outbox.size() + " frames, past " + RETENTION_FRAMES);
      }
      if (state.inFlight < 0) {
        violations.add(node + " has a negative in-flight count: " + state.inFlight);
      }
      counted += state.inFlight;
    }
    if (requestByMob.size() != outstanding.size()) {
      violations.add(
          "mob index holds "
              + requestByMob.size()
              + " but "
              + outstanding.size()
              + " requests are outstanding");
    }
    for (Map.Entry<Integer, Long> entry : requestByMob.entrySet()) {
      Outstanding request = outstanding.get(entry.getValue());
      if (request == null || request.mobId() != entry.getKey()) {
        violations.add("mob " + entry.getKey() + " points at a request that is not outstanding");
      }
    }
    if (counted != outstanding.size()) {
      violations.add(
          "in-flight counters sum to " + counted + ", outstanding is " + outstanding.size());
    }
    return violations;
  }

  @Override
  public void accept(WorldEvent event) {
    switch (event) {
      case WorldEvent.TickBegan began -> onTickBegan(began.tick());
      case WorldEvent.TickEnded ignored -> onTickEnded();
      case WorldEvent.BlockChanged changed -> pendingCells.add(changed);
      case WorldEvent.ChunkLoaded loaded ->
          loadedChunks.add(Chunks.key(loaded.chunkX(), loaded.chunkZ()));
      case WorldEvent.ChunkUnloaded unloaded ->
          onChunkUnloaded(unloaded.chunkX(), unloaded.chunkZ());
      case WorldEvent.MobSpawned spawned -> mobs.put(spawned.mobId(), spawned.classId());
      case WorldEvent.MobRemoved removed -> onMobRemoved(removed.mobId());
      case WorldEvent.PathRequested requested -> onPathRequested(requested);
      case WorldEvent.PathCanceled canceled -> onPathCanceled(canceled.mobId());
      // Consumed once shedding and failover priorities exist; both order by
      // distance to the nearest player.
      case WorldEvent.PlayerMoved ignored -> {}
      case WorldEvent.PlayerLeft ignored -> {}
    }
  }

  @Override
  public void onConnected(NodeId node) {
    NodeState state = state(node);
    state.connected = true;
    // Nothing is sent yet. Until the node says how far its world stream got,
    // the controller cannot tell a replay from a resnapshot.
    state.resetConnection();
  }

  @Override
  public void onDisconnected(NodeId node) {
    NodeState state = state(node);
    state.connected = false;
    state.greeted = false;
    state.resetConnection();
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
        state(node).reportedDepth = (int) pong.queueDepth();
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
    NodeState state = state(node);
    int region = regions.indexOf(node);
    long nodeSequence = hello.lastSequence();
    boolean resume =
        nodeSequence > 0
            && hello.run() == run
            && region >= 0
            && hello.regionId() == region
            && snapshotted[region]
            && nodeSequence <= state.lastSequence
            && nodeSequence >= state.logBase
            && nodeSequence >= state.evicted;

    if (resume) {
      env.metrics().count("world.resume", 1, "node", node.toString());
      state.nextToSend = nodeSequence + 1;
    } else {
      env.metrics().count("world.resnapshot", 1, "node", node.toString());
      state.outbox.clear();
      state.evicted = 0;
      state.logBase = Long.MAX_VALUE;
      state.nextToSend = state.lastSequence + 1;
      if (region >= 0) {
        snapshotted[region] = false;
      }
    }

    state.greeted = true;
    if (region >= 0) {
      // On the world channel, not the control one: the assignment has to arrive
      // ahead of the frames it applies to, and only one channel orders them.
      send(node, Channel.WORLD, Frames.assignment(regions, region, resume, run));
    }
    // Ordered channel, because a request naming a class the node has not been
    // told about cannot be answered.
    send(node, Channel.CONTROL, Frames.mobClasses(world.mobClasses()));
    drainOutbox(node, state);
  }

  private void onTickBegan(long tick) {
    expireOutstanding(tick);
    if (tick % HEARTBEAT_TICKS != 0) {
      return;
    }
    for (Map.Entry<NodeId, NodeState> entry : nodes.entrySet()) {
      NodeId node = entry.getKey();
      if (entry.getValue().connected) {
        send(node, Channel.CONTROL, Frames.ping(++pingSequence, env.clock().nowNanos() / 1000));
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
      Outstanding request = entries.next().getValue();
      if (tick - request.issuedTick() <= MAX_TICKS_LATE) {
        return;
      }
      entries.remove();
      forget(request);
      env.metrics().count("path.expired", 1);
      out.pathDropped(request.mobId(), DropReason.SHED);
    }
  }

  /** The region's state is unknown until the chunk returns, so the stream stops and resnapshots. */
  private void onChunkUnloaded(int chunkX, int chunkZ) {
    loadedChunks.remove(Chunks.key(chunkX, chunkZ));
    int region = regions.regionAt(chunkX * Chunks.SIZE, chunkZ * Chunks.SIZE);
    if (region >= 0) {
      snapshotted[region] = false;
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

    int region = regions.regionAt(event.start().x(), event.start().z());
    if (region < 0 || !snapshotted[region]) {
      out.pathDropped(mobId, DropReason.NO_NODE);
      return;
    }
    NodeId node = regions.owner(region);
    NodeState state = state(node);
    if (!state.greeted) {
      out.pathDropped(mobId, DropReason.NO_NODE);
      return;
    }

    // Heartbeats are far apart, so a reported depth is only a lower bound. What
    // the controller already has in flight to that node is the better estimate.
    long admitted = (long) MAX_TICKS_LATE * nodeCapacity;
    if (Math.max(state.reportedDepth, state.inFlight) + 1 > admitted) {
      env.metrics().count("path.rejected", 1, "reason", "queue_depth");
      out.pathDropped(mobId, DropReason.QUEUE_DEPTH);
      return;
    }

    // One outstanding request per mob. The older one is reported dropped rather
    // than left dangling, so every request still ends in exactly one callback.
    Long previousRequest = requestByMob.get(mobId);
    if (previousRequest != null) {
      drop(previousRequest, DropReason.SUPERSEDED);
    }

    long requestId = nextRequestId++;
    requestByMob.put(mobId, requestId);
    outstanding.put(requestId, new Outstanding(mobId, node, event.tick(), event.target()));
    if (++state.inFlight > admitted) {
      selfViolations.add(
          node + " holds " + state.inFlight + " requests, past the admission bound of " + admitted);
    }
    state.requests.add(
        new Frames.Request(requestId, classId, event.start(), event.target(), event.search()));
  }

  private void onPathResponse(PathResponse response) {
    Outstanding request = outstanding.remove(response.requestId());
    if (request == null) {
      // A retry, or the answer to a request already superseded or given up on.
      env.metrics().count("path.response.stale", 1);
      return;
    }
    forget(request);

    // Positions come back relative to the region the request went to.
    int region = regions.indexOf(request.node());
    int originX = regions.minX(region);
    int originZ = regions.minZ(region);
    List<WorldOutput.Node> nodes = new ArrayList<>(response.nodesLength());
    PathNode scratch = new PathNode();
    for (int i = 0; i < response.nodesLength(); i++) {
      response.nodes(scratch, i);
      Pos pos =
          new Pos(scratch.pos().x() + originX, scratch.pos().y(), scratch.pos().z() + originZ);
      nodes.add(new WorldOutput.Node(pos, (byte) scratch.type()));
    }
    env.metrics().record("path.latency.ticks", env.clock().tick() - request.issuedTick());
    out.pathComputed(
        new WorldOutput.PathResult(
            request.mobId(),
            request.target(),
            response.reached(),
            nodes,
            response.cost(),
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
    Iterator<Map.Entry<Long, Outstanding>> entries = outstanding.entrySet().iterator();
    while (entries.hasNext()) {
      Outstanding request = entries.next().getValue();
      if (!request.node().equals(node)) {
        continue;
      }
      entries.remove();
      forget(request);
      out.pathDropped(request.mobId(), reason);
    }
  }

  /**
   * Releases the per-mob and per-node bookkeeping for a request already out of {@code outstanding}.
   */
  private void forget(Outstanding request) {
    requestByMob.remove(request.mobId());
    state(request.node()).inFlight--;
  }

  private void snapshotReadyRegions() {
    for (int region = 0; region < regions.count(); region++) {
      NodeId node = regions.owner(region);
      NodeState state = state(node);
      if (snapshotted[region] || !state.greeted || !regionLoaded(region)) {
        continue;
      }
      snapshotted[region] = true;
      // Read on the main thread, encode off it: `WorldSource` is only safe here.
      Frames.RegionRuns runs = Frames.readRegion(world, regions, region);
      int baseY = world.minY();
      long sequence = ++state.lastSequence;
      // Frames before a snapshot describe a stream nobody can resume into now.
      state.logBase = sequence;
      state.outbox.headMap(sequence).clear();
      offload(node, sequence, "snapshot", () -> Frames.snapshot(sequence, baseY, runs));
    }
  }

  private void flushCells() {
    for (WorldEvent.BlockChanged change : pendingCells) {
      int region = regions.regionAt(change.pos().x(), change.pos().z());
      // A region snapshotted this tick already carries the change.
      if (region >= 0 && snapshotted[region]) {
        state(regions.owner(region)).cells.add(change);
      }
    }
    pendingCells.clear();

    for (Map.Entry<NodeId, NodeState> entry : nodes.entrySet()) {
      NodeState state = entry.getValue();
      if (state.cells.isEmpty()) {
        continue;
      }
      // Produced even while the node is away: the stream belongs to the region, and
      // a node that reconnects inside the retention window is replayed from here.
      List<WorldEvent.BlockChanged> cells = state.cells;
      state.cells = new ArrayList<>();
      long sequence = ++state.lastSequence;
      int region = regions.indexOf(entry.getKey());
      offload(
          entry.getKey(), sequence, "delta", () -> Frames.delta(sequence, cells, regions, region));
    }
  }

  private void flushRequests() {
    for (Map.Entry<NodeId, NodeState> entry : nodes.entrySet()) {
      NodeState state = entry.getValue();
      if (state.requests.isEmpty()) {
        continue;
      }
      List<Frames.Request> batch = state.requests;
      state.requests = new ArrayList<>();
      if (!state.greeted) {
        continue;
      }
      NodeId node = entry.getKey();
      int region = regions.indexOf(node);
      env.offload()
          .submit(
              "requests",
              () -> Frames.requests(batch, regions, region),
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
              NodeState state = state(node);
              state.outbox.put(sequence, frame);
              drainOutbox(node, state);
            });
  }

  /**
   * Advances the send pointer over every frame now present, transmitting only once the node has
   * been greeted. Frames stay behind the pointer as the replay window until they age out.
   */
  private void drainOutbox(NodeId node, NodeState state) {
    while (state.outbox.containsKey(state.nextToSend)) {
      if (state.greeted) {
        send(node, Channel.WORLD, state.outbox.get(state.nextToSend));
      }
      state.nextToSend++;
    }

    long lowest = state.nextToSend - RETENTION_FRAMES;
    while (!state.outbox.isEmpty() && state.outbox.firstKey() < lowest) {
      state.evicted = Math.max(state.evicted, state.outbox.pollFirstEntry().getKey());
    }
  }

  private NodeState state(NodeId node) {
    return nodes.computeIfAbsent(node, id -> new NodeState());
  }

  private boolean regionLoaded(int region) {
    int minX = regions.minX(region);
    int minZ = regions.minZ(region);
    int regionSize = regions.topology().regionSize();
    for (int x = minX; x < minX + regionSize; x += Chunks.SIZE) {
      for (int z = minZ; z < minZ + regionSize; z += Chunks.SIZE) {
        if (!loadedChunks.contains(Chunks.key(Chunks.of(x), Chunks.of(z)))) {
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
}
