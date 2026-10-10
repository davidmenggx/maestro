package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
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
import java.util.function.Consumer;
import java.util.function.Supplier;
import maestro.fbs.ControlReplyFrame;
import maestro.fbs.ControlReplyMsg;
import maestro.fbs.Heartbeat;
import maestro.fbs.Hello;
import maestro.fbs.PathNode;
import maestro.fbs.PathResponse;
import maestro.fbs.ResponseFrame;
import maestro.fbs.ResponseMsg;
import maestro.fbs.UnknownProfile;

/**
 * Minimal controller used to check that the seams can carry a real workload. It assigns regions,
 * snapshots them, streams deltas in sequence, batches requests per tick and applies responses.
 * Sharding, failover and the failure detector are absent, because that is the logic this harness is
 * meant to test.
 *
 * <p>Encoding is offloaded and can finish out of order, but the world stream must leave in sequence
 * order, so finished frames wait in an outbox until the earlier gap is filled. A failed encode is
 * run again under the same sequence, so every gap is eventually filled.
 */
public final class ProbeController implements WorldInput, Transport.Listener {

  /**
   * A request that cannot be answered within this many ticks is not sent, and is not waited for.
   */
  public static final int MAX_TICKS_LATE = 10;

  /** How often a disconnected node is reconnected. */
  private static final int RECONNECT_TICKS = 20;

  /**
   * World frames kept per node so a reconnecting node can be replayed forward instead of sent a
   * new snapshot. A node that fell further behind than this gets a new snapshot.
   */
  static final int RETENTION_FRAMES = 48;

  /** {@code target} is kept so the result can be returned as a complete {@code Path}. */
  private record Outstanding(int mobId, NodeId node, long issuedTick, Pos target) {}

  /**
   * What the controller tracks about one node. The connection is only part of this: the world stream
   * and its replay window belong to the region and survive a reconnect.
   */
  private static final class NodeState {

    private boolean connected;

    /** Counts connections, so a request batch built for one connection is never sent on a later one. */
    private long connection;

    /** Past the {@code Hello} handshake, so world frames may flow. */
    private boolean greeted;

    private int inFlight;

    /** Depth the node last reported. Heartbeats are infrequent, so this is a lower bound. */
    private int reportedDepth;

    /** Search the node runs, reported on connect. */
    private int pathfinder;

    /** Highest world sequence produced for the node, sent or not. */
    private long lastSequence;

    /** Lowest sequence the outbox has not handed to the transport. */
    private long nextToSend = 1;

    /** Sequence of the snapshot the retained frames continue from. */
    private long logBase = Long.MAX_VALUE;

    /** Highest sequence dropped from the outbox. Resuming before this is impossible. */
    private long evicted;

    private final TreeMap<Long, byte[]> outbox = new TreeMap<>();
    private List<WorldEvent.BlockChanged> cells = new ArrayList<>();
    private List<Frames.Request> requests = new ArrayList<>();

    /** Profiles this node has been sent on the current connection. */
    private final Set<Short> registeredClasses = new LinkedHashSet<>();

    /** Chunks whose state has been sent to this node, resident or cleared. */
    private final Set<Long> sentChunks = new LinkedHashSet<>();

    /** Chunks whose state changed and has not been sent. */
    private final Set<Long> dirtyChunks = new LinkedHashSet<>();

    /** Clears state that belongs only to the connection. */
    private void resetConnection() {
      reportedDepth = 0;
      requests.clear();
      registeredClasses.clear();
    }
  }

  private final ControllerEnv env;
  private final WorldSource world;
  private final WorldOutput out;
  private final RegionMap regions;
  private final int nodeCapacity;

  private final Set<Integer> mobs = new LinkedHashSet<>();
  private final Set<Long> loadedChunks = new LinkedHashSet<>();
  private final Map<NodeId, NodeState> nodes = new LinkedHashMap<>();

  /** Insertion-ordered, and request ids only increase, so this is also in issue-tick order. */
  private final Map<Long, Outstanding> outstanding = new LinkedHashMap<>();

  private final Map<Integer, Long> requestByMob = new LinkedHashMap<>();
  private final List<WorldEvent.BlockChanged> pendingCells = new ArrayList<>();
  private final List<String> selfViolations = new ArrayList<>();

  /** Identifies this controller process to the fleet. */
  final long run;

  private long nextRequestId = 1;
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
    // Seeded in region order, so iterating the fleet always has the same order,
    // whatever order the nodes first connected in.
    for (NodeId node : regions.nodes()) {
      nodes.put(node, new NodeState());
    }
    env.transport().listen(this);
  }

  // Accessors for harness assertions. Everything below this point is behavior.

  public int requestsIssued() {
    return requestsIssued;
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
   * Internal bookkeeping that must be consistent: one mob mapping per outstanding request,
   * per-node in-flight counters that sum to the total, and an admission bound that was never
   * exceeded.
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
    // One search across the fleet, so every answer in a comparison run is attributed to it.
    int fleetPathfinder = -1;
    for (Map.Entry<NodeId, NodeState> entry : nodes.entrySet()) {
      NodeState state = entry.getValue();
      if (!state.greeted) {
        continue;
      }
      if (fleetPathfinder < 0) {
        fleetPathfinder = state.pathfinder;
      } else if (state.pathfinder != fleetPathfinder) {
        violations.add(
            entry.getKey()
                + " runs pathfinder "
                + state.pathfinder
                + " but the fleet runs "
                + fleetPathfinder);
      }
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
          onChunkResidency(loaded.chunkX(), loaded.chunkZ(), true);
      case WorldEvent.ChunkUnloaded unloaded ->
          onChunkResidency(unloaded.chunkX(), unloaded.chunkZ(), false);
      case WorldEvent.MobSpawned spawned -> mobs.add(spawned.mobId());
      case WorldEvent.MobRemoved removed -> onMobRemoved(removed.mobId());
      case WorldEvent.PathRequested requested -> onPathRequested(requested);
      case WorldEvent.PathCanceled canceled -> onPathCanceled(canceled.mobId());
      // Used for shedding and failover priorities, which both order by distance
      // to the nearest player.
      case WorldEvent.PlayerMoved ignored -> {}
      case WorldEvent.PlayerLeft ignored -> {}
    }
  }

  @Override
  public void onConnected(NodeId node) {
    NodeState state = state(node);
    state.connected = true;
    state.connection++;
    // Nothing is sent yet. Until the node reports how far its world stream got,
    // the controller cannot tell whether to replay or send a new snapshot.
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
      case REQUEST -> onResponse(node, frame);
      case CONTROL -> onControlReply(node, frame);
      case WORLD -> selfViolations.add(node + " sent a frame on the world channel");
    }
  }

  private void onResponse(NodeId node, ByteBuffer frame) {
    ResponseFrame response = ResponseFrame.getRootAsResponseFrame(frame);
    switch (response.bodyType()) {
      case ResponseMsg.PathResponse ->
          onPathResponse((PathResponse) response.body(new PathResponse()));
      case ResponseMsg.UnknownProfile ->
          onUnknownProfile(node, (UnknownProfile) response.body(new UnknownProfile()));
      default -> env.metrics().count("controller.unknown.response", 1);
    }
  }

  /**
   * The request arrived before the profile it names, which is still on the control channel. That
   * channel either delivers the profile or breaks the connection, so nothing is resent. The request
   * is dropped sooner than its timeout would drop it.
   */
  private void onUnknownProfile(NodeId node, UnknownProfile reply) {
    env.metrics().count("path.profile.unknown", 1, "node", node.toString());
    // Only a profile already sent on this connection can be overtaken. A profile that was
    // never sent is a controller bug, which the node cannot distinguish from a delay.
    if (!state(node).registeredClasses.contains((short) reply.classId())) {
      selfViolations.add(node + " was asked to use profile " + reply.classId() + " never sent");
    }
    if (outstanding.containsKey(reply.requestId())) {
      drop(reply.requestId(), DropReason.CLASS_UNREGISTERED);
    } else {
      env.metrics().count("path.response.stale", 1);
    }
  }

  private void onControlReply(NodeId node, ByteBuffer frame) {
    ControlReplyFrame reply = ControlReplyFrame.getRootAsControlReplyFrame(frame);
    switch (reply.bodyType()) {
      case ControlReplyMsg.Hello -> onHello(node, (Hello) reply.body(new Hello()));
      case ControlReplyMsg.Heartbeat -> {
        Heartbeat heartbeat = (Heartbeat) reply.body(new Heartbeat());
        state(node).reportedDepth = (int) heartbeat.queueDepth();
        // The only view of a node's backlog in production, and the arrivals a
        // failure detector reads.
        env.metrics()
            .record("node.queue.reported", heartbeat.queueDepth(), "node", node.toString());
      }
      default -> env.metrics().count("controller.unknown.control.reply", 1);
    }
  }

  /**
   * Decides whether to replay the world stream or start it over. Replay needs the node to still
   * hold its assigned region, at a sequence that the controller has not dropped from the node's
   * outbox and that is at or after the point the retained frames continue from.
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
      // The node drops its state, so there is nothing to resume before the chunks
      // queued below.
      state.logBase = state.lastSequence + 1;
      state.nextToSend = state.lastSequence + 1;
      state.sentChunks.clear();
      state.dirtyChunks.clear();
      if (region >= 0) {
        markRegionDirty(region, state);
      }
    }

    state.greeted = true;
    state.pathfinder = hello.pathfinder();
    if (region >= 0) {
      // Sent on the world channel, not the control channel. The assignment must
      // arrive before the frames it applies to, and only one channel orders them.
      send(node, Channel.WORLD, Frames.assignment(regions, region, resume, run, world));
    }
    drainOutbox(node, state);
  }

  private void onTickBegan(long tick) {
    expireOutstanding(tick);
    if (tick % RECONNECT_TICKS != 0) {
      return;
    }
    for (Map.Entry<NodeId, NodeState> entry : nodes.entrySet()) {
      if (!entry.getValue().connected) {
        env.transport().connect(entry.getKey(), "sim://" + entry.getKey());
      }
    }
  }

  private void onTickEnded() {
    flushChunks();
    flushCells();
    flushRequests();
    env.metrics().gauge("controller.outstanding", outstanding.size());
  }

  /**
   * A request not answered within the budget is abandoned. This handles a lost frame on the request
   * stream, and a request to a node that went silent without resetting its connection.
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

  /**
   * A chunk that leaves is treated as air on the node, as vanilla does for a chunk it could not
   * fetch, so the region keeps serving and only that chunk is resent.
   */
  private void onChunkResidency(int chunkX, int chunkZ, boolean resident) {
    long key = Chunks.key(chunkX, chunkZ);
    boolean changed = resident ? loadedChunks.add(key) : loadedChunks.remove(key);
    if (!changed) {
      return;
    }
    int region = regions.regionAt(chunkX * Chunks.SIZE, chunkZ * Chunks.SIZE);
    if (region >= 0) {
      state(regions.owner(region)).dirtyChunks.add(key);
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
    if (!mobs.contains(mobId)) {
      out.pathDropped(mobId, DropReason.MOB_GONE);
      return;
    }
    short classId = event.classId();

    int region = regions.regionAt(event.start().x(), event.start().z());
    if (region < 0) {
      out.pathDropped(mobId, DropReason.NO_NODE);
      return;
    }
    NodeId node = regions.owner(region);
    NodeState state = state(node);
    // The chunk the search starts in must have reached the node. Chunk state is
    // produced at the tick boundary and a request is admitted within the tick, so
    // the world frame is always a tick ahead of the first request that needs it.
    long startChunk = Chunks.key(Chunks.of(event.start().x()), Chunks.of(event.start().z()));
    if (!state.greeted || !state.sentChunks.contains(startChunk)) {
      out.pathDropped(mobId, DropReason.NO_NODE);
      return;
    }

    // A profile is interned when a mob first needs it, so the node may not have it
    // yet. Registering it abandons this request. This keeps the two channels safe
    // to use together: a profile is always sent a whole tick before any request
    // naming it, so a request cannot overtake it on the unordered stream.
    if (state.registeredClasses.add(classId)) {
      MobClass profile = world.mobClass(classId);
      if (profile == null) {
        state.registeredClasses.remove(classId);
        selfViolations.add("request named profile " + classId + ", which the world does not hold");
        out.pathDropped(mobId, DropReason.MOB_GONE);
        return;
      }
      send(node, Channel.CONTROL, Frames.mobClass(profile));
      env.metrics().count("mobclass.registered", 1, "node", node.toString());
      out.pathDropped(mobId, DropReason.CLASS_UNREGISTERED);
      return;
    }

    // Heartbeats are infrequent, so a reported depth is only a lower bound. The
    // number of requests already in flight to that node is a better estimate.
    long admitted = (long) MAX_TICKS_LATE * nodeCapacity;
    if (Math.max(state.reportedDepth, state.inFlight) + 1 > admitted) {
      env.metrics().count("path.rejected", 1, "reason", "queue_depth");
      out.pathDropped(mobId, DropReason.QUEUE_DEPTH);
      return;
    }

    // One outstanding request per mob. The older one is reported as dropped, so
    // every request still ends in exactly one callback.
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
      // A retry, or the answer to a request that was already replaced or abandoned.
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
   * Releases the per-mob and per-node bookkeeping for a request already removed from {@code outstanding}.
   */
  private void forget(Outstanding request) {
    requestByMob.remove(request.mobId());
    state(request.node()).inFlight--;
  }

  /** One frame per chunk whose residency or content changed, to the node that owns it. */
  private void flushChunks() {
    for (Map.Entry<NodeId, NodeState> entry : nodes.entrySet()) {
      NodeState state = entry.getValue();
      if (state.dirtyChunks.isEmpty() || !state.greeted) {
        continue;
      }
      NodeId node = entry.getKey();
      int region = regions.indexOf(node);
      int originChunkX = Chunks.of(regions.minX(region));
      int originChunkZ = Chunks.of(regions.minZ(region));
      for (long key : state.dirtyChunks) {
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;
        // Wire positions, including chunk indices, are relative to the region.
        int localX = chunkX - originChunkX;
        int localZ = chunkZ - originChunkZ;
        long sequence = ++state.lastSequence;
        state.sentChunks.add(key);
        if (loadedChunks.contains(key)) {
          // Read on the main thread and encode on another: `WorldSource` is only safe here.
          Frames.ChunkRuns runs = Frames.readChunk(world, chunkX, chunkZ);
          offload(
              node, sequence, "chunk", () -> Frames.chunkSnapshot(sequence, localX, localZ, runs));
        } else {
          offload(node, sequence, "chunk", () -> Frames.chunkCleared(sequence, localX, localZ));
        }
      }
      state.dirtyChunks.clear();
    }
  }

  private void flushCells() {
    for (WorldEvent.BlockChanged change : pendingCells) {
      int region = regions.regionAt(change.pos().x(), change.pos().z());
      if (region < 0) {
        continue;
      }
      NodeState state = state(regions.owner(region));
      long key = Chunks.key(Chunks.of(change.pos().x()), Chunks.of(change.pos().z()));
      // A chunk sent this tick was read after the change, so it already includes it.
      if (state.sentChunks.contains(key) && !state.dirtyChunks.contains(key)) {
        state.cells.add(change);
      }
    }
    pendingCells.clear();

    for (Map.Entry<NodeId, NodeState> entry : nodes.entrySet()) {
      NodeState state = entry.getValue();
      if (state.cells.isEmpty()) {
        continue;
      }
      // Produced even while the node is away. The stream belongs to the region, and
      // a node that reconnects within the retention window is replayed from here.
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
      long connection = state.connection;
      encode(
          "requests",
          () -> Frames.requests(batch, regions, region),
          frame -> {
            // A retried encode can finish after the connection it was built for ended. Its
            // requests were dropped with that connection, and the profiles they name may not
            // be registered on this one.
            if (state(node).connection != connection) {
              env.metrics().count("requests.discarded", 1, "node", node.toString());
              return;
            }
            send(node, Channel.REQUEST, frame);
          });
    }
  }

  /**
   * Stores the finished frame under its sequence number, then sends every frame from the lowest
   * unsent sequence up to the first missing one. Encoding runs off the controller thread and can
   * finish out of order, so sending each frame as it finishes would leave gaps in the sequence.
   */
  private void offload(NodeId node, long sequence, String job, Supplier<byte[]> work) {
    encode(
        job,
        work,
        frame -> {
          NodeState state = state(node);
          state.outbox.put(sequence, frame);
          drainOutbox(node, state);
        });
  }

  /**
   * Runs {@code work} off the controller thread, and runs it again at the next tick boundary if it
   * fails. The work captured everything it reads, so a retry builds the same bytes. A world frame
   * keeps its sequence number, because skipping a number would leave a gap for the node.
   */
  private void encode(String job, Supplier<byte[]> work, Consumer<byte[]> completion) {
    env.offload()
        .submit(
            job,
            work,
            completion,
            () -> {
              env.metrics().count("offload.retried", 1, "job", job);
              encode(job, work, completion);
            });
  }

  /**
   * Advances the send pointer over every frame now present, sending only once the node has sent
   * its {@code Hello}. Frames behind the pointer stay as the replay window until they age out.
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

  /** Every chunk of a region the server holds. The rest are already air on the node. */
  private void markRegionDirty(int region, NodeState state) {
    int minX = regions.minX(region);
    int minZ = regions.minZ(region);
    int regionSize = regions.topology().regionSize();
    for (int x = minX; x < minX + regionSize; x += Chunks.SIZE) {
      for (int z = minZ; z < minZ + regionSize; z += Chunks.SIZE) {
        long key = Chunks.key(Chunks.of(x), Chunks.of(z));
        if (loadedChunks.contains(key)) {
          state.dirtyChunks.add(key);
        }
      }
    }
  }

  private void send(NodeId node, Channel channel, byte[] frame) {
    env.metrics().record("frame.bytes", frame.length, "channel", channel.name());
    env.transport().send(node, channel, ByteBuffer.wrap(frame));
  }
}
