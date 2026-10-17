package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.wire.Frames;
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
import java.util.TreeSet;
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
 * The controller. It assigns regions, snapshots them, streams deltas in sequence, batches requests
 * per tick into datagram-sized frames, resends the ones left unanswered, cancels the ones no longer
 * wanted and applies responses. Sharding, failover and the failure detector are not built.
 *
 * <p>Encoding is offloaded and can finish out of order, but the world stream must leave in sequence
 * order, so finished frames wait in an outbox until the earlier gap is filled. A failed encode is
 * run again under the same sequence, so a gap is filled, and a world frame whose encode keeps
 * failing starts its region's stream over.
 */
public final class Controller implements WorldInput, Transport.Listener {

  /**
   * A request that cannot be answered within this many ticks is not sent, and is not waited for.
   */
  public static final int MAX_TICKS_LATE = 10;

  /**
   * Ticks from a request to the earliest tick its node usually serves it: the batch is encoded off
   * the main thread and leaves at the end of the next tick, and the node serves it in the tick
   * after that.
   */
  static final int SEND_TICKS = 2;

  /**
   * A request is sent again under the same id once its answer is this many ticks late, at most
   * {@link #MAX_RESENDS} times. An answer is due once the node has served the requests it held
   * ahead of it when it left. A node answers a request it has seen with the same bytes, so a resend
   * that crosses a slow answer costs only bandwidth.
   */
  static final int RESEND_TICKS = 1;

  static final int MAX_RESENDS = 2;

  /** How often a disconnected node is reconnected. */
  private static final int RECONNECT_TICKS = 20;

  /**
   * World frames kept per region so a reconnecting holder can be replayed forward instead of sent a
   * new snapshot. A holder that fell further behind than this gets a new snapshot.
   */
  static final int RETENTION_FRAMES = 48;

  /**
   * Tries at one encode. A batch of requests given up on times out. A world frame given up on
   * leaves a gap no later frame can cross, so its region's stream starts over.
   */
  static final int MAX_ENCODE_ATTEMPTS = 5;

  /**
   * Resident chunks read per tick, across all regions. A read runs on the main thread, so a region
   * sent again in full is spread over several ticks, and its chunks become usable as they arrive.
   */
  static final int MAX_CHUNK_READS_PER_TICK = 16;

  /**
   * {@code request} is kept so it can be resent, and so the result can be returned as a complete
   * {@code Path}. {@code chunkSequence} is the world sequence from which the node holds the chunk
   * the search starts in. {@code dueTick} is the tick by whose end the latest copy should be
   * answered, or {@link #NOT_SENT} while that copy waits to be encoded. An {@code abandoned}
   * request has already been reported dropped, but its node may still be working on it, so it still
   * counts against the node.
   */
  private record Outstanding(
      int mobId,
      NodeId node,
      long issuedTick,
      Frames.Request request,
      long chunkSequence,
      long dueTick,
      int resends,
      boolean abandoned) {

    Outstanding sent(long due) {
      return new Outstanding(
          mobId, node, issuedTick, request, chunkSequence, due, resends, abandoned);
    }

    Outstanding resent() {
      return new Outstanding(
          mobId, node, issuedTick, request, chunkSequence, NOT_SENT, resends + 1, abandoned);
    }

    Outstanding abandon() {
      return new Outstanding(
          mobId, node, issuedTick, request, chunkSequence, dueTick, resends, true);
    }
  }

  private static final long NOT_SENT = -1;

  /** A frame from a node, read in full before any of it is used. */
  private sealed interface Reply {}

  /** A {@code PathResponse}. Positions are relative to the node's area. */
  private record Answer(
      long requestId, boolean reached, List<WorldOutput.Node> nodes, float cost, long worldSequence)
      implements Reply {}

  private record Refusal(long requestId, int classId) implements Reply {}

  private record Greeting(long run, long regionId, long lastSequence, int pathfinder)
      implements Reply {}

  private record Depth(long queueDepth) implements Reply {}

  /** A response meant for another controller run. */
  private record Foreign() implements Reply {}

  /** What the controller tracks about one node's connection. */
  private static final class NodeState {

    private boolean connected;

    /** A {@link Transport#connect} is pending, so another must not start. */
    private boolean connecting;

    /**
     * Counts connections, so a request batch built for one connection is never sent on a later one.
     */
    private long connection;

    /** Past the {@code Hello} handshake, so world frames may flow. */
    private boolean greeted;

    /** Requests that count against the node: outstanding ones, abandoned ones included. */
    private int inFlight;

    /**
     * Depth the node reported in its last heartbeat, less the answers seen since. It can still be
     * above or below the true depth, so admission takes the larger of it and {@link #inFlight}.
     * Answers come slowly from a node slower than assumed, so it stays deep.
     */
    private int reportedDepth;

    /** Search the node runs, reported on connect. */
    private int pathfinder;

    /** Requests waiting for the next batch, by id. */
    private Map<Long, Frames.Request> requests = new LinkedHashMap<>();

    /** Requests abandoned since the last flush whose copies may still wait on the node. */
    private List<Long> cancels = new ArrayList<>();

    /** Profiles this node has been sent on the current connection. */
    private final Set<Short> registeredClasses = new LinkedHashSet<>();

    /** Sent requests by the tick their answer is due, for {@link #expectedQueue}. */
    private final TreeMap<Long, Integer> dueCounts = new TreeMap<>();

    /** Clears state that belongs only to the connection. */
    private void resetConnection() {
      reportedDepth = 0;
      requests.clear();
      cancels.clear();
      registeredClasses.clear();
    }
  }

  /**
   * The world stream of one region: its sequence, the frames kept for replay, and what the region's
   * holder has been sent. It belongs to the region, not to the node holding it, so it survives a
   * reconnect and its sequence keeps rising across resnapshots.
   */
  private static final class RegionStream {

    /** Highest sequence produced, sent or not. */
    private long lastSequence;

    /** Lowest sequence the outbox has not handed to the transport. */
    private long nextToSend = 1;

    /**
     * Sequence of the snapshot the retained frames continue from. A frame before it belongs to a
     * stream the holder no longer continues.
     */
    private long logBase = Long.MAX_VALUE;

    /** Highest sequence dropped from the outbox. Resuming before this is impossible. */
    private long evicted;

    private final TreeMap<Long, byte[]> outbox = new TreeMap<>();

    /**
     * Chunks the holder has been sent as resident, each with the sequence of the frame that made it
     * resident. A chunk sent as cleared is absent, because the holder has it as air.
     */
    private final Map<Long, Long> resident = new LinkedHashMap<>();

    /** Chunks whose state changed and has not been sent. */
    private final Set<Long> dirtyChunks = new LinkedHashSet<>();
  }

  private final ControllerEnv env;
  private final WorldSource world;
  private final WorldOutput out;
  private final RegionMap regions;
  private final int nodeCapacity;

  private final Set<Integer> mobs = new LinkedHashSet<>();
  private final Set<Long> loadedChunks = new LinkedHashSet<>();
  private final Map<NodeId, NodeState> nodes = new LinkedHashMap<>();

  /** By region id, in region order. */
  private final Map<Integer, RegionStream> streams = new LinkedHashMap<>();

  /**
   * Every request that counts against a node, abandoned ones included. Insertion-ordered, and
   * request ids only increase, so this is also in issue-tick order. Changed only through {@link
   * #put} and {@link #remove}, which keep the indexes below in step.
   */
  private final Map<Long, Outstanding> outstanding = new LinkedHashMap<>();

  /**
   * Request ids by the tick their answer is due. An entry goes stale when its request changes, so
   * each is checked against {@link #outstanding} when read.
   */
  private final TreeMap<Long, List<Long>> dueIndex = new TreeMap<>();

  /** Each mob's live request. */
  private final Map<Integer, Long> requestByMob = new LinkedHashMap<>();

  private final List<WorldEvent.BlockChanged> pendingCells = new ArrayList<>();
  private final List<String> selfViolations = new ArrayList<>();

  /** Identifies this controller process to the fleet. */
  final long run;

  private long nextRequestId = 1;
  private int requestsIssued;

  /** The region {@link #flushChunks} starts from, so each gets a turn at the read budget. */
  private int chunkCursor;

  public Controller(
      ControllerEnv env, WorldSource world, WorldOutput out, RegionMap regions, int nodeCapacity) {
    if (nodeCapacity < 1) {
      throw new IllegalArgumentException("a node serves at least one request a tick");
    }
    this.env = Objects.requireNonNull(env, "env");
    this.world = Objects.requireNonNull(world, "world");
    this.out = Objects.requireNonNull(out, "out");
    this.regions = Objects.requireNonNull(regions, "regions");
    this.nodeCapacity = nodeCapacity;
    this.run = env.run();
    // Seeded in region order, so iterating the fleet always has the same order,
    // whatever order the nodes first connected in.
    for (int region = 0; region < regions.regionCount(); region++) {
      nodes.put(regions.owner(region), new NodeState());
      streams.put(region, new RegionStream());
    }
    env.transport().listen(this);
  }

  // Accessors for harness assertions. Everything below this point is behavior.

  public int requestsIssued() {
    return requestsIssued;
  }

  /** Requests that still count against a node, abandoned ones included. */
  public int outstandingCount() {
    return outstanding.size();
  }

  /**
   * The world sequence a node reports once it has applied everything produced for its region: the
   * last one produced. An assignment stands at the stream's position, so this holds for a node that
   * holds its assignment alone too.
   */
  public long heldSequence(NodeId node) {
    int region = regions.regionOf(node);
    return region < 0 ? 0 : streams.get(region).lastSequence;
  }

  /**
   * Whether every change to the region so far is in a frame produced for it, so a holder at {@link
   * #heldSequence} holds what the world holds now.
   */
  public boolean regionSettled(int region) {
    return streams.get(region).dirtyChunks.isEmpty() && pendingCells.isEmpty();
  }

  /**
   * Internal bookkeeping that must be consistent: one mob mapping per live request, per-node
   * in-flight counters that sum to the requests that count against nodes, and due counts that match
   * the requests sent.
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
      if (state.connected && state.connecting) {
        violations.add(node + " is connected with a connect still pending");
      }
      if (state.inFlight < 0) {
        violations.add(node + " has a negative in-flight count: " + state.inFlight);
      }
      counted += state.inFlight;
      int due = 0;
      for (Outstanding request : outstanding.values()) {
        due += request.node().equals(node) && request.dueTick() != NOT_SENT ? 1 : 0;
      }
      int indexed = state.dueCounts.values().stream().mapToInt(Integer::intValue).sum();
      if (due != indexed) {
        violations.add(node + " has " + due + " requests sent but " + indexed + " indexed");
      }
    }
    for (Map.Entry<Integer, RegionStream> entry : streams.entrySet()) {
      int retained = entry.getValue().outbox.size();
      if (retained > RETENTION_FRAMES) {
        violations.add(
            "region "
                + entry.getKey()
                + " retains "
                + retained
                + " frames, past "
                + RETENTION_FRAMES);
      }
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
    int live = 0;
    for (Outstanding request : outstanding.values()) {
      live += request.abandoned() ? 0 : 1;
    }
    if (requestByMob.size() != live) {
      violations.add(
          "mob index holds " + requestByMob.size() + " but " + live + " requests are live");
    }
    for (Map.Entry<Integer, Long> entry : requestByMob.entrySet()) {
      Outstanding request = outstanding.get(entry.getValue());
      if (request == null || request.abandoned() || request.mobId() != entry.getKey()) {
        violations.add("mob " + entry.getKey() + " points at a request that is not live");
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
      case WorldEvent.TickEnded ended -> onTickEnded(ended.tick());
      case WorldEvent.BlockChanged changed -> pendingCells.add(changed);
      case WorldEvent.ChunkLoaded loaded ->
          onChunkResidency(loaded.chunkX(), loaded.chunkZ(), true);
      case WorldEvent.ChunkUnloaded unloaded ->
          onChunkResidency(unloaded.chunkX(), unloaded.chunkZ(), false);
      case WorldEvent.MobSpawned spawned -> mobs.add(spawned.mobId());
      case WorldEvent.MobRemoved removed -> onMobRemoved(removed.mobId());
      case WorldEvent.PathRequested requested -> onPathRequested(requested);
      case WorldEvent.PathCanceled canceled -> onPathCanceled(canceled.mobId());
      // No decision reads player positions.
      case WorldEvent.PlayerMoved ignored -> {}
      case WorldEvent.PlayerLeft ignored -> {}
    }
  }

  @Override
  public void onConnected(NodeId node) {
    NodeState state = nodes.get(node);
    if (state == null) {
      env.metrics().count("transport.unknown.node", 1);
      return;
    }
    state.connecting = false;
    state.connected = true;
    state.connection++;
    // Nothing is sent yet. Until the node reports how far its world stream got,
    // the controller cannot tell whether to replay or send a new snapshot.
    state.resetConnection();
  }

  @Override
  public void onDisconnected(NodeId node) {
    NodeState state = nodes.get(node);
    if (state == null) {
      env.metrics().count("transport.unknown.node", 1);
      return;
    }
    state.connecting = false;
    state.connected = false;
    state.greeted = false;
    state.resetConnection();
    dropWhere(node, DropReason.NODE_LOST);
  }

  @Override
  public void onFrame(NodeId node, Channel channel, ByteBuffer frame) {
    if (!nodes.containsKey(node)) {
      env.metrics().count("transport.unknown.node", 1);
      return;
    }
    if (channel == Channel.WORLD) {
      selfViolations.add(node + " sent a frame on the world channel");
      return;
    }
    Reply reply;
    try {
      reply = channel == Channel.REQUEST ? decodeResponse(frame) : decodeControlReply(frame);
    } catch (RuntimeException e) {
      // A datagram can arrive cut short, or from something that is not a node.
      env.metrics().count("controller.malformed.frame", 1, "channel", channel.name());
      return;
    }
    switch (reply) {
      case Answer answer -> onPathResponse(node, answer);
      case Refusal refusal -> onUnknownProfile(node, refusal);
      case Greeting greeting -> onHello(node, greeting);
      case Depth depth -> {
        state(node).reportedDepth = (int) Math.min(Integer.MAX_VALUE, depth.queueDepth());
        // The only view of a node's backlog in production, and the arrivals a
        // failure detector reads.
        env.metrics().record("node.queue.reported", depth.queueDepth(), "node", node.toString());
      }
      case Foreign ignored -> env.metrics().count("path.response.foreign", 1);
      case null -> env.metrics().count("controller.unknown.frame", 1, "channel", channel.name());
    }
  }

  /** The reply in a response frame, or null for a body this controller does not know. */
  private Reply decodeResponse(ByteBuffer frame) {
    ResponseFrame response = ResponseFrame.getRootAsResponseFrame(frame);
    if (response.run() != run) {
      return new Foreign();
    }
    switch (response.bodyType()) {
      case ResponseMsg.PathResponse -> {
        PathResponse body = (PathResponse) response.body(new PathResponse());
        // Not presized: the length is read from the datagram and may be garbage.
        List<WorldOutput.Node> nodes = new ArrayList<>();
        PathNode scratch = new PathNode();
        for (int i = 0; i < body.nodesLength(); i++) {
          body.nodes(scratch, i);
          Pos pos = new Pos(scratch.pos().x(), scratch.pos().y(), scratch.pos().z());
          nodes.add(new WorldOutput.Node(pos, (byte) scratch.type()));
        }
        return new Answer(
            body.requestId(), body.reached(), nodes, body.cost(), body.worldSequence());
      }
      case ResponseMsg.UnknownProfile -> {
        UnknownProfile body = (UnknownProfile) response.body(new UnknownProfile());
        return new Refusal(body.requestId(), body.classId());
      }
      default -> {
        return null;
      }
    }
  }

  /** The reply in a control frame, or null for a body this controller does not know. */
  private static Reply decodeControlReply(ByteBuffer frame) {
    ControlReplyFrame reply = ControlReplyFrame.getRootAsControlReplyFrame(frame);
    switch (reply.bodyType()) {
      case ControlReplyMsg.Hello -> {
        Hello hello = (Hello) reply.body(new Hello());
        return new Greeting(
            hello.run(), hello.regionId(), hello.lastSequence(), hello.pathfinder());
      }
      case ControlReplyMsg.Heartbeat -> {
        Heartbeat heartbeat = (Heartbeat) reply.body(new Heartbeat());
        return new Depth(heartbeat.queueDepth());
      }
      default -> {
        return null;
      }
    }
  }

  /**
   * The request arrived before the profile it names, which is still on the control channel. That
   * channel either delivers the profile or breaks the connection, so nothing is resent. The request
   * is dropped sooner than its timeout would drop it.
   */
  private void onUnknownProfile(NodeId node, Refusal refusal) {
    env.metrics().count("path.profile.unknown", 1, "node", node.toString());
    Outstanding request = outstanding.get(refusal.requestId());
    if (request == null || !request.node().equals(node)) {
      env.metrics().count("path.response.stale", 1);
      return;
    }
    answered(node);
    // A request outstanding on a node was admitted on its current connection, so its profile
    // was sent on that connection too. One that was not is a controller bug, which the node
    // cannot tell from a delay.
    if (!state(node).registeredClasses.contains((short) refusal.classId())) {
      selfViolations.add(node + " was asked to use profile " + refusal.classId() + " never sent");
    }
    // The node does not remember a refusal, so a resent copy that arrives after the profile
    // would still be served. It is canceled, and the request stops counting against the node
    // like any other the node drops.
    if (request.resends() > 0) {
      state(node).cancels.add(refusal.requestId());
    }
    if (request.abandoned()) {
      release(refusal.requestId());
    } else {
      finish(refusal.requestId(), request, DropReason.CLASS_UNREGISTERED);
    }
  }

  /**
   * Decides whether to replay the world stream or start it over. Replay needs the node to still
   * hold its assigned region, at a sequence that the controller has not dropped from the region's
   * outbox and that is at or after the point the retained frames continue from.
   */
  private void onHello(NodeId node, Greeting hello) {
    NodeState state = state(node);
    state.greeted = true;
    state.pathfinder = hello.pathfinder();
    int region = regions.regionOf(node);
    if (region < 0) {
      return;
    }
    RegionStream stream = streams.get(region);
    long nodeSequence = hello.lastSequence();
    boolean resume =
        nodeSequence > 0
            && hello.run() == run
            && hello.regionId() == region
            && nodeSequence <= stream.lastSequence
            && nodeSequence >= stream.logBase
            && nodeSequence >= stream.evicted;

    if (resume) {
      env.metrics().count("world.resume", 1, "node", node.toString());
      stream.nextToSend = nodeSequence + 1;
    } else {
      env.metrics().count("world.resnapshot", 1, "node", node.toString());
      restartStream(region);
    }
    sendAssignment(region, resume ? nodeSequence : stream.lastSequence, resume);
    drainOutbox(region);
  }

  /**
   * Sent on the world channel, not the control channel. The assignment must arrive before the
   * frames it applies to, and only one channel orders them. It says where the stream stands, so the
   * node refuses a first frame that does not follow it.
   */
  private void sendAssignment(int region, long sequence, boolean resume) {
    send(
        regions.owner(region),
        Channel.WORLD,
        Frames.assignment(run, region, regions.regionSize(), sequence, resume, world));
  }

  /**
   * Starts the region's stream over at its last sequence: its holder will hold it as air, and every
   * resident chunk is read and sent again. Frames before it are never sent.
   */
  private void restartStream(int region) {
    RegionStream stream = streams.get(region);
    stream.outbox.clear();
    stream.evicted = 0;
    stream.logBase = stream.lastSequence + 1;
    stream.nextToSend = stream.lastSequence + 1;
    stream.resident.clear();
    stream.dirtyChunks.clear();
    markRegionDirty(region, stream);
  }

  private void onTickBegan(long tick) {
    expireOutstanding(tick);
    if (tick % RECONNECT_TICKS != 0) {
      return;
    }
    for (Map.Entry<NodeId, NodeState> entry : nodes.entrySet()) {
      NodeState state = entry.getValue();
      if (!state.connected && !state.connecting) {
        state.connecting = true;
        env.transport().connect(entry.getKey());
      }
    }
  }

  private void onTickEnded(long tick) {
    // Block changes first, while the chunks about to be sent are still marked.
    flushCells();
    flushChunks();
    settleDueAnswers(tick);
    flushRequests();
    env.metrics().gauge("controller.outstanding", outstanding.size());
  }

  /**
   * A request not answered within the budget, resends included, is abandoned. This handles repeated
   * loss on the request stream, and a request to a node that went silent without resetting its
   * connection. Admission keeps a node's work within the budget, so an abandoned request older than
   * it stops counting against the node too. {@code outstanding} is in issue order, so the scan
   * stops at the first younger entry.
   */
  private void expireOutstanding(long tick) {
    Iterator<Map.Entry<Long, Outstanding>> entries = outstanding.entrySet().iterator();
    while (entries.hasNext()) {
      Outstanding request = entries.next().getValue();
      if (tick - request.issuedTick() <= MAX_TICKS_LATE) {
        return;
      }
      entries.remove();
      left(request);
      if (request.abandoned()) {
        continue;
      }
      requestByMob.remove(request.mobId());
      env.metrics().count("path.expired", 1);
      out.pathDropped(request.mobId(), DropReason.TIMEOUT);
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
      streams.get(region).dirtyChunks.add(key);
    }
  }

  private void onPathCanceled(int mobId) {
    Long requestId = requestByMob.get(mobId);
    if (requestId != null) {
      abandon(requestId, DropReason.CANCELED);
    }
  }

  private void onMobRemoved(int mobId) {
    mobs.remove(mobId);
    Long requestId = requestByMob.get(mobId);
    if (requestId != null) {
      abandon(requestId, DropReason.MOB_GONE);
    }
  }

  private void onPathRequested(WorldEvent.PathRequested event) {
    requestsIssued++;
    int mobId = event.mobId();
    if (!mobs.contains(mobId)) {
      out.pathDropped(mobId, DropReason.MOB_GONE);
      return;
    }
    // One live request per mob. A repeat replaces the older one even if it is not admitted
    // itself, since the mob no longer wants the older answer. The older one is reported as
    // dropped, so every request still ends in exactly one callback.
    Long previousRequest = requestByMob.get(mobId);
    if (previousRequest != null) {
      abandon(previousRequest, DropReason.SUPERSEDED);
    }
    short classId = event.classId();

    int region = regions.regionAt(event.start().x(), event.start().z());
    if (region < 0) {
      out.pathDropped(mobId, DropReason.NO_NODE);
      return;
    }
    // A position the wire cannot carry would fail every encode of its batch.
    if (!Frames.fits(event.start(), regions.minX(region), regions.minZ(region))
        || !Frames.fits(event.target(), regions.minX(region), regions.minZ(region))) {
      out.pathDropped(mobId, DropReason.NO_NODE);
      return;
    }
    NodeId node = regions.owner(region);
    NodeState state = state(node);
    // The chunk the search starts in must be resident on the node. Its frame is produced
    // at a tick boundary, before the request, so it usually reaches the node a tick
    // ahead of it. If it does not, as when its encode is retried, the answer's world
    // sequence shows it.
    long startChunk = Chunks.key(Chunks.of(event.start().x()), Chunks.of(event.start().z()));
    Long chunkSequence = streams.get(region).resident.get(startChunk);
    if (!state.greeted || chunkSequence == null) {
      out.pathDropped(mobId, DropReason.NO_NODE);
      return;
    }

    // A profile is interned when a mob first needs it, so the node may not have it
    // yet. Registering it abandons this request. The profile leaves now on the
    // ordered control channel, and requests naming it leave at the end of the tick,
    // so they usually arrive after it. One made later in this tick trails it by less
    // than a tick. A request that still overtakes it is answered `UnknownProfile`.
    if (state.registeredClasses.add(classId)) {
      MobClass profile = world.mobClass(classId);
      if (profile == null) {
        state.registeredClasses.remove(classId);
        selfViolations.add("request named profile " + classId + ", which the world does not hold");
        out.pathDropped(mobId, DropReason.CLASS_UNREGISTERED);
        return;
      }
      send(node, Channel.CONTROL, Frames.mobClass(profile));
      env.metrics().count("mobclass.registered", 1, "node", node.toString());
      out.pathDropped(mobId, DropReason.CLASS_UNREGISTERED);
      return;
    }

    // The node serves this request once it has served the ones ahead of it and this one,
    // at the capacity admission assumes, and no sooner than `SEND_TICKS` from now. The
    // larger of the reported depth and the requests counted against the node estimates
    // how many are ahead.
    long ahead = Math.max(state.reportedDepth, state.inFlight);
    if ((ahead + 1) / nodeCapacity + SEND_TICKS > MAX_TICKS_LATE) {
      env.metrics().count("path.rejected", 1, "reason", "queue_depth");
      out.pathDropped(mobId, DropReason.QUEUE_DEPTH);
      return;
    }

    long requestId = nextRequestId++;
    Frames.Request request =
        new Frames.Request(requestId, classId, event.start(), event.target(), event.search());
    requestByMob.put(mobId, requestId);
    put(
        requestId,
        new Outstanding(mobId, node, event.tick(), request, chunkSequence, NOT_SENT, 0, false));
    state.requests.put(requestId, request);
  }

  private void onPathResponse(NodeId node, Answer answer) {
    Outstanding request = outstanding.get(answer.requestId());
    if (request == null) {
      // A retry, or the answer to a request that was already settled.
      env.metrics().count("path.response.stale", 1);
      return;
    }
    if (!request.node().equals(node)) {
      env.metrics().count("path.response.misrouted", 1, "node", node.toString());
      return;
    }
    answered(node);
    if (request.abandoned()) {
      // The node is done with it, and the mob no longer wants it.
      release(answer.requestId());
      env.metrics().count("path.response.abandoned", 1);
      return;
    }
    if (answer.worldSequence() < request.chunkSequence()) {
      // The node searched before the chunk the search starts in reached it, so it searched
      // air there. A resend would get the same answer back.
      env.metrics().count("path.response.behind", 1, "node", node.toString());
      finish(answer.requestId(), request, DropReason.NO_NODE);
      return;
    }
    release(answer.requestId());
    requestByMob.remove(request.mobId());
    env.metrics().record("path.latency.ticks", env.clock().tick() - request.issuedTick());
    out.pathComputed(
        new WorldOutput.PathResult(
            request.mobId(),
            request.request().target(),
            answer.reached(),
            inWorld(answer.nodes(), node),
            answer.cost(),
            request.issuedTick()));
  }

  /** The node finished a request, so it holds one fewer than its last heartbeat said. */
  private void answered(NodeId node) {
    NodeState state = state(node);
    state.reportedDepth = Math.max(0, state.reportedDepth - 1);
  }

  /** The answer's path in world positions. Positions come back relative to the node's area. */
  private List<WorldOutput.Node> inWorld(List<WorldOutput.Node> path, NodeId node) {
    int region = regions.regionOf(node);
    int originX = regions.minX(region);
    int originZ = regions.minZ(region);
    List<WorldOutput.Node> nodes = new ArrayList<>(path.size());
    for (WorldOutput.Node relative : path) {
      Pos pos = relative.pos();
      nodes.add(
          new WorldOutput.Node(
              new Pos(pos.x() + originX, pos.y(), pos.z() + originZ), relative.pathType()));
    }
    return nodes;
  }

  /**
   * Reports a live request dropped because its mob no longer wants it. One still waiting for its
   * batch never leaves and is simply removed. Otherwise a copy may be on its way to the node or in
   * the node's queue, so the node is told to drop it, and the request keeps counting against the
   * node until its answer is due or arrives, since the cancel may be lost or come too late.
   */
  private void abandon(long requestId, DropReason reason) {
    Outstanding request = outstanding.get(requestId);
    if (request == null || request.abandoned()) {
      return;
    }
    requestByMob.remove(request.mobId());
    NodeState state = state(request.node());
    // A resend waits in the batch only between `settleDueAnswers` and `flushRequests`,
    // which nothing runs between, so a request found waiting was never sent.
    if (state.requests.remove(requestId) != null) {
      release(requestId);
    } else {
      state.cancels.add(requestId);
      put(requestId, request.abandon());
    }
    out.pathDropped(request.mobId(), reason);
  }

  /** Reports a live request dropped once its node is done with it or never had it. */
  private void finish(long requestId, Outstanding request, DropReason reason) {
    release(requestId);
    requestByMob.remove(request.mobId());
    out.pathDropped(request.mobId(), reason);
  }

  /** The request stops counting against its node. */
  private void release(long requestId) {
    remove(requestId);
  }

  /** Every request on a lost connection. The live ones are reported dropped. */
  private void dropWhere(NodeId node, DropReason reason) {
    List<Long> lost = new ArrayList<>();
    for (Map.Entry<Long, Outstanding> entry : outstanding.entrySet()) {
      if (entry.getValue().node().equals(node)) {
        lost.add(entry.getKey());
      }
    }
    for (long requestId : lost) {
      Outstanding request = remove(requestId);
      if (!request.abandoned()) {
        requestByMob.remove(request.mobId());
        out.pathDropped(request.mobId(), reason);
      }
    }
  }

  /** Adds or replaces a request, keeping the in-flight counters and due indexes in step. */
  private void put(long requestId, Outstanding request) {
    Outstanding previous = outstanding.put(requestId, request);
    if (previous == null) {
      state(request.node()).inFlight++;
    } else {
      unindex(previous);
    }
    if (request.dueTick() != NOT_SENT) {
      state(request.node()).dueCounts.merge(request.dueTick(), 1, Integer::sum);
      dueIndex.computeIfAbsent(request.dueTick(), tick -> new ArrayList<>()).add(requestId);
    }
  }

  /** Removes a request, keeping the in-flight counters and due indexes in step. */
  private Outstanding remove(long requestId) {
    Outstanding previous = outstanding.remove(requestId);
    if (previous != null) {
      left(previous);
    }
    return previous;
  }

  /** Undoes what {@link #put} counted for a request no longer in {@link #outstanding}. */
  private void left(Outstanding request) {
    state(request.node()).inFlight--;
    unindex(request);
  }

  /** Undoes what {@link #put} indexed for a request that changed or left. */
  private void unindex(Outstanding request) {
    if (request.dueTick() != NOT_SENT) {
      state(request.node())
          .dueCounts
          .computeIfPresent(request.dueTick(), (tick, count) -> count > 1 ? count - 1 : null);
    }
  }

  /**
   * One frame per chunk whose residency or content changed, to the node that owns it. Reads of
   * resident chunks stop at {@link #MAX_CHUNK_READS_PER_TICK}, and the rest stay marked for the
   * next tick. Regions take turns at being first.
   */
  private void flushChunks() {
    int reads = 0;
    int count = regions.regionCount();
    for (int turn = 0; turn < count; turn++) {
      int region = (chunkCursor + turn) % count;
      RegionStream stream = streams.get(region);
      if (stream.dirtyChunks.isEmpty() || !state(regions.owner(region)).greeted) {
        continue;
      }
      int originChunkX = Chunks.of(regions.minX(region));
      int originChunkZ = Chunks.of(regions.minZ(region));
      Iterator<Long> dirty = stream.dirtyChunks.iterator();
      while (dirty.hasNext()) {
        long key = dirty.next();
        boolean loaded = loadedChunks.contains(key);
        if (loaded && reads == MAX_CHUNK_READS_PER_TICK) {
          break;
        }
        dirty.remove();
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;
        // Wire positions, including chunk indices, are relative to the region.
        int localX = chunkX - originChunkX;
        int localZ = chunkZ - originChunkZ;
        long sequence = ++stream.lastSequence;
        if (loaded) {
          reads++;
          // A chunk sent again while resident never was air on the node, so it keeps
          // the sequence it first became resident at.
          stream.resident.putIfAbsent(key, sequence);
          // Read on the main thread and encode on another: `WorldSource` is only safe here.
          Frames.ChunkRuns runs = Frames.readChunk(world, chunkX, chunkZ);
          offload(
              region,
              sequence,
              "chunk",
              () -> Frames.chunkSnapshot(sequence, localX, localZ, runs));
        } else {
          stream.resident.remove(key);
          offload(region, sequence, "chunk", () -> Frames.chunkCleared(sequence, localX, localZ));
        }
      }
    }
    chunkCursor = (chunkCursor + 1) % count;
    if (reads == MAX_CHUNK_READS_PER_TICK) {
      env.metrics().count("world.chunks.deferred", 1);
    }
  }

  /**
   * Deltas per region, holding the changes to chunks its holder has resident. A chunk about to be
   * sent is read after every change in it, so its changes are left out. Sending them after it would
   * also put a block back into a chunk that left.
   */
  private void flushCells() {
    Map<Integer, List<WorldEvent.BlockChanged>> byRegion = new TreeMap<>();
    for (WorldEvent.BlockChanged change : pendingCells) {
      int region = regions.regionAt(change.pos().x(), change.pos().z());
      if (region < 0) {
        continue;
      }
      RegionStream stream = streams.get(region);
      long key = Chunks.key(Chunks.of(change.pos().x()), Chunks.of(change.pos().z()));
      if (stream.resident.containsKey(key) && !stream.dirtyChunks.contains(key)) {
        byRegion.computeIfAbsent(region, unseen -> new ArrayList<>()).add(change);
      }
    }
    pendingCells.clear();

    for (Map.Entry<Integer, List<WorldEvent.BlockChanged>> entry : byRegion.entrySet()) {
      // Produced even while the node is away. The stream belongs to the region, and
      // a node that reconnects within the retention window is replayed from here.
      int region = entry.getKey();
      List<WorldEvent.BlockChanged> cells = entry.getValue();
      RegionStream stream = streams.get(region);
      int minX = regions.minX(region);
      int minZ = regions.minZ(region);
      for (int from = 0; from < cells.size(); from += Frames.MAX_CELLS_PER_DELTA) {
        List<WorldEvent.BlockChanged> part =
            cells.subList(from, Math.min(cells.size(), from + Frames.MAX_CELLS_PER_DELTA));
        long sequence = ++stream.lastSequence;
        offload(region, sequence, "delta", () -> Frames.delta(sequence, part, minX, minZ));
      }
    }
  }

  /**
   * Acts on every request whose answer is due by now, in issue order. A live one is added to its
   * node's batch again, so a lost request or answer costs a few ticks rather than a timeout. An
   * abandoned one stops counting against its node, which has served it by now.
   */
  private void settleDueAnswers(long tick) {
    TreeSet<Long> due = new TreeSet<>();
    while (!dueIndex.isEmpty() && dueIndex.firstKey() <= tick) {
      Map.Entry<Long, List<Long>> entry = dueIndex.pollFirstEntry();
      for (long requestId : entry.getValue()) {
        Outstanding request = outstanding.get(requestId);
        if (request != null && request.dueTick() == entry.getKey()) {
          due.add(requestId);
        }
      }
    }
    for (long requestId : due) {
      Outstanding request = outstanding.get(requestId);
      if (request.abandoned()) {
        release(requestId);
        continue;
      }
      if (request.resends() >= MAX_RESENDS) {
        continue;
      }
      put(requestId, request.resent());
      state(request.node()).requests.put(requestId, request.request());
      env.metrics().count("path.resent", 1, "node", request.node().toString());
    }
  }

  /**
   * Each node's requests and cancels for the tick, in frames that each fit one datagram. They leave
   * together, so they may arrive in any order, and each request may wait behind the whole batch.
   */
  private void flushRequests() {
    for (Map.Entry<NodeId, NodeState> entry : nodes.entrySet()) {
      NodeState state = entry.getValue();
      if (state.requests.isEmpty() && state.cancels.isEmpty()) {
        continue;
      }
      List<Frames.Request> batch = List.copyOf(state.requests.values());
      List<Long> cancels = state.cancels;
      state.requests = new LinkedHashMap<>();
      state.cancels = new ArrayList<>();
      NodeId node = entry.getKey();
      int region = regions.regionOf(node);
      int originX = regions.minX(region);
      int originZ = regions.minZ(region);
      long connection = state.connection;
      encode(
          "requests",
          () -> requestFrames(run, region, batch, cancels, originX, originZ),
          frames -> {
            // A retried encode can finish after the connection it was built for ended. Its
            // requests were dropped with that connection, and the profiles they name may not
            // be registered on this one.
            if (state(node).connection != connection) {
              env.metrics().count("requests.discarded", 1, "node", node.toString());
              return;
            }
            for (byte[] frame : frames) {
              send(node, Channel.REQUEST, frame);
            }
            if (!cancels.isEmpty()) {
              env.metrics().count("path.cancels", cancels.size(), "node", node.toString());
            }
            if (batch.isEmpty()) {
              return;
            }
            env.metrics()
                .count(
                    "requests.frames",
                    frameCount(batch.size(), Frames.MAX_REQUESTS_PER_FRAME),
                    "node",
                    node.toString());
            // The node serves requests in the order they arrive, after the ones it is still
            // expected to hold, so the last of the batch is done once all of them are. Timing
            // starts when the batch leaves, not when it is built.
            long tick = env.clock().tick();
            long due =
                tick + RESEND_TICKS + (expectedQueue(node, tick) + batch.size()) / nodeCapacity;
            for (Frames.Request request : batch) {
              Outstanding waiting = outstanding.get(request.requestId());
              if (waiting != null) {
                put(request.requestId(), waiting.sent(due));
              }
            }
          },
          // The requests time out, and the cancels are lost as a datagram would be.
          () -> {});
    }
  }

  /**
   * {@code batch} in frames of at most {@link Frames#MAX_REQUESTS_PER_FRAME}, then {@code cancels}
   * in frames of at most {@link Frames#MAX_CANCELS_PER_FRAME}.
   */
  private static List<byte[]> requestFrames(
      long run,
      int region,
      List<Frames.Request> batch,
      List<Long> cancels,
      int originX,
      int originZ) {
    List<byte[]> frames = new ArrayList<>();
    for (int from = 0; from < batch.size(); from += Frames.MAX_REQUESTS_PER_FRAME) {
      int to = Math.min(batch.size(), from + Frames.MAX_REQUESTS_PER_FRAME);
      frames.add(Frames.requests(run, region, batch.subList(from, to), originX, originZ));
    }
    for (int from = 0; from < cancels.size(); from += Frames.MAX_CANCELS_PER_FRAME) {
      int to = Math.min(cancels.size(), from + Frames.MAX_CANCELS_PER_FRAME);
      frames.add(Frames.cancels(run, cancels.subList(from, to)));
    }
    return frames;
  }

  private static int frameCount(int items, int perFrame) {
    return (items + perFrame - 1) / perFrame;
  }

  /**
   * Requests the node is expected to still hold: sent to it, and not yet due. The controller
   * assumes every node serves {@code nodeCapacity} a tick.
   */
  private int expectedQueue(NodeId node, long tick) {
    int queued = 0;
    for (int count : state(node).dueCounts.tailMap(tick, false).values()) {
      queued += count;
    }
    return queued;
  }

  /**
   * Stores the finished frame under its sequence number, then sends every frame from the lowest
   * unsent sequence up to the first missing one. Encoding runs off the controller thread and can
   * finish out of order, so sending each frame as it finishes would leave gaps in the sequence. A
   * frame of a stream since started over is dropped, and one whose encode is given up on starts the
   * stream over again.
   */
  private void offload(int region, long sequence, String job, Supplier<byte[]> work) {
    encode(
        job,
        work,
        frame -> {
          RegionStream stream = streams.get(region);
          if (sequence < stream.logBase) {
            env.metrics().count("world.frame.superseded", 1, "job", job);
            return;
          }
          stream.outbox.put(sequence, frame);
          drainOutbox(region);
        },
        () -> {
          if (sequence < streams.get(region).logBase) {
            return;
          }
          env.metrics().count("world.restarted", 1, "region", Integer.toString(region));
          restartStream(region);
          if (state(regions.owner(region)).greeted) {
            sendAssignment(region, streams.get(region).lastSequence, false);
          }
        });
  }

  /**
   * Runs {@code work} off the controller thread, and runs it again at the next tick boundary if it
   * fails, at most {@link #MAX_ENCODE_ATTEMPTS} times in all, then runs {@code gaveUp}. The work
   * captured everything it reads, so a retry builds the same bytes. A world frame keeps its
   * sequence number, because skipping a number would leave a gap for the node.
   */
  private <T> void encode(String job, Supplier<T> work, Consumer<T> completion, Runnable gaveUp) {
    encode(job, work, completion, gaveUp, 1);
  }

  private <T> void encode(
      String job, Supplier<T> work, Consumer<T> completion, Runnable gaveUp, int attempt) {
    env.offload()
        .submit(
            job,
            work,
            completion,
            () -> {
              if (attempt >= MAX_ENCODE_ATTEMPTS) {
                env.metrics().count("offload.abandoned", 1, "job", job);
                gaveUp.run();
                return;
              }
              env.metrics().count("offload.retried", 1, "job", job);
              encode(job, work, completion, gaveUp, attempt + 1);
            });
  }

  /**
   * Advances the send pointer over every frame now present, sending only once the region's owner
   * has sent its {@code Hello}. Frames behind the pointer stay as the replay window until they age
   * out.
   */
  private void drainOutbox(int region) {
    RegionStream stream = streams.get(region);
    NodeId owner = regions.owner(region);
    boolean greeted = state(owner).greeted;
    while (stream.outbox.containsKey(stream.nextToSend)) {
      if (greeted) {
        send(owner, Channel.WORLD, stream.outbox.get(stream.nextToSend));
      }
      stream.nextToSend++;
    }

    long lowest = stream.nextToSend - RETENTION_FRAMES;
    while (!stream.outbox.isEmpty() && stream.outbox.firstKey() < lowest) {
      stream.evicted = Math.max(stream.evicted, stream.outbox.pollFirstEntry().getKey());
    }
  }

  /**
   * A node of the fleet. Frames and callbacks from any other are dropped before this is reached.
   */
  private NodeState state(NodeId node) {
    NodeState state = nodes.get(node);
    if (state == null) {
      throw new IllegalStateException(node + " is not in the fleet");
    }
    return state;
  }

  /** Every chunk of a region the server holds. The rest are already air on the node. */
  private void markRegionDirty(int region, RegionStream stream) {
    int minX = regions.minX(region);
    int minZ = regions.minZ(region);
    int regionSize = regions.regionSize();
    for (int x = minX; x < minX + regionSize; x += Chunks.SIZE) {
      for (int z = minZ; z < minZ + regionSize; z += Chunks.SIZE) {
        long key = Chunks.key(Chunks.of(x), Chunks.of(z));
        if (loadedChunks.contains(key)) {
          stream.dirtyChunks.add(key);
        }
      }
    }
  }

  private void send(NodeId node, Channel channel, byte[] frame) {
    env.metrics().record("frame.bytes", frame.length, "channel", channel.name());
    env.transport().send(node, channel, ByteBuffer.wrap(frame));
  }
}
