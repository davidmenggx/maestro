package io.github.davidmenggx.maestro.controller.sim;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import maestro.fbs.CellDelta;
import maestro.fbs.CellRun;
import maestro.fbs.ChunkSnapshot;
import maestro.fbs.ControlFrame;
import maestro.fbs.ControlMsg;
import maestro.fbs.ControlReplyFrame;
import maestro.fbs.ControlReplyMsg;
import maestro.fbs.Heartbeat;
import maestro.fbs.Hello;
import maestro.fbs.Join;
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
 * state a stateful node would, and makes the same decisions about links, requests, cancels and
 * world frames as the real node, which the conformance fixtures check against it. Paths are
 * straight lines, because the controller and the wire are under test, not A*.
 *
 * <p>Workers start a request as soon as one is free and take a seeded time to serve it, so answers
 * can finish out of order and a burst waits behind the workers. With no workers, requests only wait
 * until {@link #serve} runs them.
 *
 * <p>It refuses exactly the world frames the real node's {@code WorldStore} refuses, and reacts the
 * same way: nothing in the frame is applied, all held state is dropped, and the link is dropped so
 * the node reconnects reporting nothing held. A correct controller never sends such a frame, so
 * each refusal is recorded in {@link #violations()} instead of thrown, and one run reports every
 * problem it hit.
 */
public final class ModeledNode {

  /** What the node does to the world outside it. */
  interface Host {

    /** Absolute time in simulation steps. */
    long now();

    /** A frame from the node: control replies on {@code CONTROL}, answers on {@code REQUEST}. */
    void send(Channel channel, byte[] frame);

    /** The node dropped its link and closed every connection of it. */
    void linkDropped();

    /** The node closed a connection on {@code port} that never joined the link. */
    void refused(Channel port);

    /** Runs {@code action} after {@code delaySteps}, at least one. */
    void schedule(long delaySteps, String name, Runnable action);

    /** The node applied world frame {@code sequence} of a region's stream. */
    void applied(ModeledNode node, long run, long region, long sequence);

    /** The node took a fresh assignment standing at {@code sequence}. */
    void assigned(ModeledNode node, long run, long region, long sequence);
  }

  /** How the node serves requests. With no workers, only {@link #serve} runs them. */
  record Service(int workers, int minCostSteps, int maxCostSteps) {

    Service {
      if (workers < 0 || minCostSteps < 1 || maxCostSteps < minCostSteps) {
        throw new IllegalArgumentException(
            "workers " + workers + ", cost " + minCostSteps + " to " + maxCostSteps);
      }
    }

    static final Service MANUAL = new Service(0, 1, 1);
  }

  private static final int MAX_PATH_NODES = 32;

  /** The node's own heartbeat interval, 1 s. The controller neither sets nor requests it. */
  static final int HEARTBEAT_STEPS = 20 * Simulation.STEPS_PER_TICK;

  /** How long an answer or a cancel is kept for later copies of its request: 500 ms. */
  static final int RETENTION_STEPS = 10 * Simulation.STEPS_PER_TICK;

  private enum State {
    WAITING,
    RUNNING
  }

  /** An admitted request. Positions are relative to the area, as received. */
  private record Job(
      long requestId, long run, long regionId, Pos start, Pos target, long generation) {}

  /** A request settled within {@link #RETENTION_STEPS}: its answer, or null if canceled. */
  private record Settled(byte[] answer, long at) {}

  private record SettledAt(long at, long requestId) {}

  private final NodeId id;
  private final Host host;
  private final Service service;
  private final Rng rng;
  private final List<String> violations = new ArrayList<>();

  // The link: the token of the last `Join` taken, and which ports have joined.
  private long linkRun;
  private long linkAttempt;
  private boolean live;
  private boolean worldJoined;
  private boolean controlJoined;

  // What belongs to the link, forgotten with it.
  private final Map<Short, MobClass.Shape> knownClasses = new LinkedHashMap<>();
  private final Deque<Job> waiting = new ArrayDeque<>();
  private final Map<Long, State> open = new LinkedHashMap<>();
  private final Map<Long, Settled> settled = new LinkedHashMap<>();
  private final Deque<SettledAt> settledOrder = new ArrayDeque<>();
  private int waitingCount;
  private int running;

  /** Bumped when the link goes, so a job still running settles without effect. */
  private long generation;

  /** Bumped when the process dies, so its timers and jobs never fire. */
  private long process;

  private boolean paused;

  /** Timers and completions that fell due while paused, run in order on resume. */
  private final List<Runnable> deferred = new ArrayList<>();

  // The world: one area, kept across links.
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
  private long servedRequests;
  private boolean backlogged;

  ModeledNode(NodeId id, Host host, Service service, Rng rng) {
    this.id = id;
    this.host = host;
    this.service = service;
    this.rng = rng;
    startHeartbeat();
  }

  // Accessors for harness assertions. Everything below this point is behavior.

  public NodeId id() {
    return id;
  }

  public Service service() {
    return service;
  }

  /** Mob profiles this node holds on its current link. */
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

  public long servedRequests() {
    return servedRequests;
  }

  /** Whether a request ever waited for a worker. */
  public boolean backlogged() {
    return backlogged;
  }

  /** Waiting and running requests, as the heartbeat reports them. */
  public int depth() {
    return waitingCount + running;
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

  /** Process death: everything is lost, and nothing the process scheduled runs. */
  void kill() {
    process++;
    deferred.clear();
    paused = false;
    run = 0;
    regionId = 0;
    cells = new byte[0];
    floors = new byte[0];
    lastSequence = 0;
    forgetLink();
    linkRun = 0;
    linkAttempt = 0;
    running = 0;
  }

  /** A new process, holding nothing. */
  void revive() {
    startHeartbeat();
  }

  /** A frozen process runs nothing until it is resumed. Its frames are held by the network. */
  void pause(boolean active) {
    paused = active;
    if (active) {
      return;
    }
    List<Runnable> due = new ArrayList<>(deferred);
    deferred.clear();
    for (Runnable action : due) {
      action.run();
    }
    startWorkers();
  }

  /**
   * The network broke the link, or the controller closed it, and the transport has already told
   * both sides. The node forgets what belonged to the link.
   */
  void connectionLost() {
    if (live) {
      forgetLink();
    }
  }

  /**
   * The controller closed a connection on {@code port}. If it was part of the link, the link goes.
   */
  void disconnected(Channel port) {
    if (live && joined(port)) {
      dropLink();
    }
  }

  /**
   * A new connection on {@code port}, with its first frame. It joins the link, replaces it, or is
   * refused, as the comment on {@code Join} in the schema says.
   */
  void connect(Channel port, ByteBuffer first) {
    Join join = joinIn(port, first);
    if (join == null || join.run() == 0 || join.link() == 0) {
      host.refused(port);
      return;
    }
    boolean replaces = linkAttempt == 0 || join.run() != linkRun || join.link() > linkAttempt;
    if (replaces) {
      if (live) {
        dropLink();
      }
      linkRun = join.run();
      linkAttempt = join.link();
      live = true;
    } else if (join.link() != linkAttempt || !live || joined(port)) {
      host.refused(port);
      return;
    }
    if (port == Channel.WORLD) {
      worldJoined = true;
    } else {
      controlJoined = true;
      hello();
    }
  }

  /** A frame on a joined connection, or a datagram. */
  void receive(Channel channel, ByteBuffer frame) {
    switch (channel) {
      case WORLD -> onWorld(frame);
      case REQUEST -> onRequest(frame);
      case CONTROL -> onControl(frame);
    }
  }

  /**
   * Runs up to {@code count} waiting requests at once, oldest first, or every waiting request if
   * {@code count} is 0. For a node with no workers.
   */
  void serve(int count) {
    for (int served = 0; count == 0 || served < count; served++) {
      Job job = take();
      if (job == null) {
        return;
      }
      running++;
      settle(job, answerBuilder(job));
    }
  }

  /** The heartbeat timer fired. */
  void heartbeatDue() {
    if (live && controlJoined) {
      FlatBufferBuilder builder = new FlatBufferBuilder(64);
      int heartbeat = Heartbeat.createHeartbeat(builder, depth());
      builder.finish(
          ControlReplyFrame.createControlReplyFrame(builder, ControlReplyMsg.Heartbeat, heartbeat));
      host.send(Channel.CONTROL, builder.sizedByteArray());
    }
  }

  private void startHeartbeat() {
    long process = this.process;
    host.schedule(
        1 + rng.nextInt(HEARTBEAT_STEPS), "heartbeat:" + id, () -> heartbeatTimer(process));
  }

  private void heartbeatTimer(long process) {
    if (process != this.process) {
      return;
    }
    if (paused) {
      deferred.add(() -> heartbeatTimer(process));
      return;
    }
    heartbeatDue();
    host.schedule(HEARTBEAT_STEPS, "heartbeat:" + id, () -> heartbeatTimer(process));
  }

  private boolean joined(Channel port) {
    return port == Channel.WORLD ? worldJoined : controlJoined;
  }

  private static Join joinIn(Channel port, ByteBuffer frame) {
    try {
      if (port == Channel.WORLD) {
        WorldFrame world = WorldFrame.getRootAsWorldFrame(frame);
        return world.bodyType() == WorldMsg.Join ? (Join) world.body(new Join()) : null;
      }
      ControlFrame control = ControlFrame.getRootAsControlFrame(frame);
      return control.bodyType() == ControlMsg.Join ? (Join) control.body(new Join()) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private void hello() {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int hello =
        hasState()
            ? Hello.createHello(builder, run, regionId, lastSequence, Pathfinder.VANILLA)
            : Hello.createHello(builder, 0, 0, 0, Pathfinder.VANILLA);
    builder.finish(
        ControlReplyFrame.createControlReplyFrame(builder, ControlReplyMsg.Hello, hello));
    host.send(Channel.CONTROL, builder.sizedByteArray());
  }

  /** The node drops its link and closes its connections. */
  private void dropLink() {
    forgetLink();
    host.linkDropped();
  }

  /**
   * Profiles and request ids belong to the link, since the next link may come from another
   * controller run, so they are forgotten. The world state and the link's token stay.
   */
  private void forgetLink() {
    live = false;
    worldJoined = false;
    controlJoined = false;
    knownClasses.clear();
    waiting.clear();
    open.clear();
    settled.clear();
    settledOrder.clear();
    waitingCount = 0;
    generation++;
  }

  private void onWorld(ByteBuffer frame) {
    WorldFrame world = WorldFrame.getRootAsWorldFrame(frame);
    if (world.bodyType() == WorldMsg.Join) {
      return;
    }
    String refusal = applyWorld(world);
    if (refusal == null) {
      return;
    }
    violations.add(id + ": " + refusal);
    run = 0;
    regionId = 0;
    cells = new byte[0];
    floors = new byte[0];
    lastSequence = 0;
    dropLink();
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

  private void onControl(ByteBuffer frame) {
    ControlFrame control = ControlFrame.getRootAsControlFrame(frame);
    if (control.bodyType() != ControlMsg.MobClass) {
      // A `Join` after the first frame, or a message this node does not know.
      return;
    }
    maestro.fbs.MobClass profile = (maestro.fbs.MobClass) control.body(new maestro.fbs.MobClass());
    if (profile.malusLength() != PathTypes.count()
        || !MobClass.Shape.fits(profile.width(), profile.height(), profile.maxUpStep())) {
      violations.add(id + ": class " + profile.classId() + " breaks the profile contract");
      dropLink();
      return;
    }
    knownClasses.put(
        (short) profile.classId(),
        new MobClass.Shape(profile.width(), profile.height(), profile.maxUpStep()));
  }

  private void onRequest(ByteBuffer frame) {
    RequestFrame request;
    try {
      request = RequestFrame.getRootAsRequestFrame(frame);
      if (!live || request.run() != linkRun) {
        return;
      }
    } catch (RuntimeException e) {
      return;
    }
    try {
      switch (request.bodyType()) {
        case RequestMsg.PathRequestBatch -> {
          PathRequestBatch batch = (PathRequestBatch) request.body(new PathRequestBatch());
          PathRequest scratch = new PathRequest();
          for (int i = 0; i < batch.requestsLength(); i++) {
            admit(batch.requests(scratch, i), request.run(), request.regionId());
          }
        }
        case RequestMsg.PathCancel -> {
          PathCancel cancel = (PathCancel) request.body(new PathCancel());
          for (int i = 0; i < cancel.requestIdsLength(); i++) {
            cancel(cancel.requestIds(i));
          }
        }
        default -> {}
      }
    } catch (RuntimeException e) {
      // A malformed datagram, which the real node ignores after verifying it.
    }
  }

  private void admit(PathRequest request, long frameRun, long frameRegion) {
    long requestId = request.requestId();
    if (!knownClasses.containsKey((short) request.classId())) {
      // The profile has not arrived yet, so tell the controller immediately instead of
      // letting the request time out. Nothing is recorded, so a later copy is served.
      host.send(Channel.REQUEST, unknownProfile(frameRun, requestId, request.classId()));
      return;
    }
    if (request.start() == null || request.target() == null) {
      return;
    }
    expire();
    Settled earlier = settled.get(requestId);
    if (earlier != null) {
      if (earlier.answer() != null) {
        // At-least-once delivery: answer the copy with the byte-identical response.
        duplicateRequests++;
        host.send(Channel.REQUEST, earlier.answer());
      }
      return;
    }
    if (open.containsKey(requestId)) {
      duplicateRequests++;
      return;
    }
    open.put(requestId, State.WAITING);
    waitingCount++;
    waiting.add(
        new Job(
            requestId,
            frameRun,
            frameRegion,
            new Pos(request.start().x(), request.start().y(), request.start().z()),
            new Pos(request.target().x(), request.target().y(), request.target().z()),
            generation));
    startWorkers();
  }

  /**
   * Drops a waiting request, and any copy that arrives later. One running or answered is left
   * alone.
   */
  private void cancel(long requestId) {
    expire();
    if (settled.containsKey(requestId) || open.get(requestId) == State.RUNNING) {
      return;
    }
    if (open.remove(requestId) != null) {
      waitingCount--;
      canceledRequests++;
    }
    record(requestId, null);
  }

  /**
   * The oldest waiting request, now running, or null. A canceled one stays in the queue until
   * reached and is skipped then, as the real node's queue does.
   */
  private Job take() {
    while (waitingCount > 0) {
      Job next = waiting.poll();
      if (open.get(next.requestId()) == State.WAITING) {
        open.put(next.requestId(), State.RUNNING);
        waitingCount--;
        return next;
      }
    }
    return null;
  }

  private void startWorkers() {
    if (paused) {
      return;
    }
    while (running < service.workers()) {
      Job job = take();
      if (job == null) {
        return;
      }
      running++;
      // The area is pinned when the job starts, as a worker pins the current version.
      byte[] answer = answerBuilder(job);
      long process = this.process;
      int cost =
          service.minCostSteps() + rng.nextInt(service.maxCostSteps() - service.minCostSteps() + 1);
      host.schedule(cost, "served:" + id, () -> completed(process, job, answer));
    }
    backlogged |= waitingCount > 0;
  }

  private void completed(long process, Job job, byte[] answer) {
    if (process != this.process) {
      return;
    }
    if (paused) {
      deferred.add(() -> completed(process, job, answer));
      return;
    }
    settle(job, answer);
    startWorkers();
  }

  /** Records a finished job's answer and sends it, unless its link has gone since. */
  private void settle(Job job, byte[] answer) {
    running--;
    if (job.generation() != generation) {
      return;
    }
    servedRequests++;
    open.remove(job.requestId());
    expire();
    record(job.requestId(), answer);
    host.send(Channel.REQUEST, answer);
  }

  private void record(long requestId, byte[] answer) {
    long now = host.now();
    settled.put(requestId, new Settled(answer, now));
    settledOrder.add(new SettledAt(now, requestId));
  }

  /** Forgets requests settled {@link #RETENTION_STEPS} ago or more. */
  private void expire() {
    long now = host.now();
    while (!settledOrder.isEmpty() && settledOrder.peek().at() + RETENTION_STEPS <= now) {
      SettledAt oldest = settledOrder.poll();
      // A request settled again later has a newer entry of its own.
      Settled current = settled.get(oldest.requestId());
      if (current != null && current.at() == oldest.at()) {
        settled.remove(oldest.requestId());
      }
    }
  }

  /**
   * The answer to a job against the area held now. A node holding no area, or another run's or
   * region's, answers with no path at sequence 0, which the controller reads as a search that ran
   * too early.
   */
  private byte[] answerBuilder(Job job) {
    boolean matches = cells.length > 0 && run == job.run() && regionId == job.regionId();
    List<Pos> path = matches ? straightLine(job.start(), job.target()) : List.of();
    boolean reached = !path.isEmpty() && path.get(path.size() - 1).equals(job.target());

    FlatBufferBuilder builder = new FlatBufferBuilder(256);
    PathResponse.startNodesVector(builder, path.size());
    for (int i = path.size() - 1; i >= 0; i--) {
      Pos node = path.get(i);
      PathNode.createPathNode(
          builder, (short) node.x(), (short) node.y(), (short) node.z(), PathType.WALKABLE);
    }
    int nodes = builder.endVector();

    PathResponse.startPathResponse(builder);
    PathResponse.addRequestId(builder, job.requestId());
    PathResponse.addReached(builder, reached);
    PathResponse.addNodes(builder, nodes);
    PathResponse.addCost(builder, path.size());
    PathResponse.addWorldSequence(builder, matches ? lastSequence : 0);
    int response = PathResponse.endPathResponse(builder);

    builder.finish(
        ResponseFrame.createResponseFrame(builder, ResponseMsg.PathResponse, response, job.run()));
    return builder.sizedByteArray();
  }

  private static byte[] unknownProfile(long run, long requestId, int classId) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int reply = UnknownProfile.createUnknownProfile(builder, requestId, classId);
    builder.finish(
        ResponseFrame.createResponseFrame(builder, ResponseMsg.UnknownProfile, reply, run));
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
    host.assigned(this, run, regionId, lastSequence);
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

    int originX = position.x() * Chunks.SIZE;
    int originZ = position.z() * Chunks.SIZE;
    // No columns means the server does not hold the chunk, so it is air.
    if (snapshot.runCountsLength() == 0) {
      fillChunk(originX, originZ, (byte) PathType.OPEN, (byte) 0);
    } else {
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
    }
    advance(snapshot.sequence());
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

    for (int i = 0; i < delta.cellsLength(); i++) {
      delta.cells(cell, i);
      Vec3s pos = cell.pos();
      int index = index(pos.x(), pos.y(), pos.z());
      cells[index] = (byte) cell.nodeType();
      floors[index] = (byte) cell.floor();
    }
    advance(delta.sequence());
    return null;
  }

  private void advance(long sequence) {
    lastSequence = sequence;
    host.applied(this, run, regionId, sequence);
  }

  private int index(int x, int y, int z) {
    int dy = y - baseY;
    if (x < 0 || x >= size || z < 0 || z >= size || dy < 0 || dy >= height) {
      return -1;
    }
    return (x * size + z) * height + dy;
  }
}
