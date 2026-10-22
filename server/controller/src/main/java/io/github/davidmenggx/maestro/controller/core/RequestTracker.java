package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Offload;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.wire.Reply;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.SearchReach;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every path request from admission to its outcome: which holder serves it, whether its node can
 * answer it in time, the per-tick batches that carry it, its resends and cancels, and the answer
 * that settles it.
 */
public final class RequestTracker {

  /**
   * A request that cannot be answered within this many ticks is not sent, and is not waited for.
   */
  public static final int MAX_TICKS_LATE = 10;

  /**
   * Ticks from a request to the earliest tick its node usually serves it: the batch is encoded off
   * the main thread at the end of the tick, leaves when the next tick starts, and the node serves
   * it in that tick.
   */
  public static final int SEND_TICKS = 1;

  /**
   * A request is sent again under the same id once its answer is this many ticks late, at most
   * {@link #MAX_RESENDS} times. An answer is due once the node has served the requests it held
   * ahead of it when it left. A node answers a request it has seen with the same bytes, so a resend
   * that crosses a slow answer costs only bandwidth.
   */
  static final int RESEND_TICKS = 1;

  public static final int MAX_RESENDS = 2;

  /**
   * {@code request} is kept so it can be resent, and so the result can be returned as a complete
   * {@code Path}. {@code startChunk} is the chunk the search starts in, whose residency on the node
   * decides whether an answer is valid. {@code dueTick} is the tick by whose end the latest copy
   * should be answered, or {@link #NOT_SENT} while that copy waits to be encoded. An {@code
   * abandoned} request has already been reported dropped, but its node may still be working on it,
   * so it still counts against the node.
   */
  private record Outstanding(
      int mobId,
      NodeId node,
      Region region,
      long startChunk,
      long issuedTick,
      Frames.Request request,
      long dueTick,
      int resends,
      boolean abandoned) {

    Outstanding sent(long due) {
      return new Outstanding(
          mobId, node, region, startChunk, issuedTick, request, due, resends, abandoned);
    }

    Outstanding resent() {
      return new Outstanding(
          mobId, node, region, startChunk, issuedTick, request, NOT_SENT, resends + 1, abandoned);
    }

    Outstanding abandon() {
      return new Outstanding(
          mobId, node, region, startChunk, issuedTick, request, dueTick, resends, true);
    }
  }

  private static final long NOT_SENT = -1;

  /** What counts against one node, and what waits for its next batch. */
  private static final class Load {

    /** Requests that count against the node: outstanding ones, abandoned ones included. */
    private int inFlight;

    /**
     * Depth the node reported in its last heartbeat, less the answers seen since. Requests the node
     * finished after the controller stopped waiting, or dropped on a cancel, are not taken off, so
     * it errs high until the next heartbeat. Admission takes the larger of it and {@link
     * #inFlight}.
     */
    private int reportedDepth;

    /** Requests waiting for the next batch, by id. */
    private Map<Long, Frames.Request> batch = new LinkedHashMap<>();

    /** Requests abandoned since the last flush whose copies may still wait on the node. */
    private List<Long> cancels = new ArrayList<>();

    /** Sent requests by the tick their answer is due, for {@link #expectedQueue}. */
    private final TreeMap<Long, Integer> dueCounts = new TreeMap<>();
  }

  private final ControllerEnv env;
  private final WorldSource world;
  private final WorldOutput out;
  private final RegionMap regions;
  private final NodeLinks links;
  private final NodeHealth health;
  private final WorldStreams streams;
  private final Encoder encoder;
  private final Violations violations;
  private final long run;
  private final int nodeCapacity;

  private final Set<Integer> mobs = new LinkedHashSet<>();
  private final Map<NodeId, Load> loads = new LinkedHashMap<>();

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

  /** A request given up on that no longer counts against its node, and when it was issued. */
  private record GivenUp(NodeId node, long issuedTick) {}

  /**
   * Requests given up on that stopped counting against their node once their answer was due, by id,
   * until their tick budget ends. The answer may still come a little late, and one that comes
   * within the budget shows the node's request path works.
   */
  private final Map<Long, GivenUp> givenUp = new LinkedHashMap<>();

  private long nextRequestId = 1;
  private int requestsIssued;

  RequestTracker(
      ControllerEnv env,
      WorldSource world,
      WorldOutput out,
      RegionMap regions,
      NodeLinks links,
      NodeHealth health,
      WorldStreams streams,
      Encoder encoder,
      Violations violations,
      long run,
      int nodeCapacity) {
    if (nodeCapacity < 1) {
      throw new IllegalArgumentException("a node serves at least one request a tick");
    }
    this.env = env;
    this.world = world;
    this.out = out;
    this.regions = regions;
    this.links = links;
    this.health = health;
    this.streams = streams;
    this.encoder = encoder;
    this.violations = violations;
    this.run = run;
    this.nodeCapacity = nodeCapacity;
    for (NodeId node : regions.nodes()) {
      loads.put(node, new Load());
    }
  }

  int requestsIssued() {
    return requestsIssued;
  }

  int outstandingCount() {
    return outstanding.size();
  }

  void mobSpawned(int mobId) {
    mobs.add(mobId);
  }

  void mobRemoved(int mobId) {
    mobs.remove(mobId);
    Long requestId = requestByMob.get(mobId);
    if (requestId != null) {
      abandon(requestId, DropReason.MOB_GONE);
    }
  }

  void pathCanceled(int mobId) {
    Long requestId = requestByMob.get(mobId);
    if (requestId != null) {
      abandon(requestId, DropReason.CANCELED);
    }
  }

  void pathRequested(WorldEvent.PathRequested event) {
    requestsIssued++;
    int mobId = event.mobId();
    if (!mobs.contains(mobId)) {
      drop(mobId, DropReason.MOB_GONE);
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

    Region region = regions.regionAt(event.start().x(), event.start().z());
    // A position the wire cannot carry would fail every encode of its batch.
    if (region == null
        || !Frames.fits(event.start(), region.minX(), region.minZ())
        || !Frames.fits(event.target(), region.minX(), region.minZ())) {
      env.metrics().count("path.rejected", 1, "reason", "outside");
      drop(mobId, DropReason.NO_NODE);
      return;
    }
    MobClass profile = world.mobClass(classId);
    if (profile == null) {
      violations.add("request named profile " + classId + ", which the world does not hold");
      drop(mobId, DropReason.CLASS_UNREGISTERED);
      return;
    }
    // A node reads past its area as air, which turns real ground into a cliff, so a search
    // that could read past the region is not sent.
    if (!SearchReach.fits(
        region.minX(),
        region.minZ(),
        region.size(),
        event.start(),
        event.search().maxPathLength(),
        profile.shape().width())) {
      env.metrics().count("path.rejected", 1, "reason", "reach");
      drop(mobId, DropReason.NO_NODE);
      return;
    }
    // The chunk the search starts in must be resident on the node. Its frame is produced at a
    // tick boundary, before the request, so it usually reaches the node a tick ahead of it. If
    // it does not, as when its encode is retried, the answer's world sequence shows it.
    long startChunk = Chunks.key(Chunks.of(event.start().x()), Chunks.of(event.start().z()));
    NodeId node = holderFor(mobId, region, startChunk, true);
    if (node == null) {
      // A holder that is ready but out of service would have taken it, so its search runs on
      // the server instead of waiting on a node that cannot answer.
      boolean unavailable = holderFor(mobId, region, startChunk, false) != null;
      env.metrics().count("path.rejected", 1, "reason", unavailable ? "unavailable" : "not_ready");
      drop(mobId, DropReason.UNAVAILABLE);
      return;
    }

    // A profile is interned when a mob first needs it, so the node may not have it yet.
    // Registering it abandons this request. The profile leaves now on the ordered control
    // channel, and requests naming it leave at the end of the tick, so they usually arrive
    // after it. One made later in this tick trails it by less than a tick. A request that
    // still overtakes it is answered `UnknownProfile`.
    if (links.register(node, classId)) {
      links.send(node, Channel.CONTROL, Frames.mobClass(profile));
      env.metrics().count("mobclass.registered", 1, "node", node.toString());
      drop(mobId, DropReason.CLASS_UNREGISTERED);
      return;
    }

    // The node serves this request once it has served the ones ahead of it and this one, at
    // the capacity admission assumes, and no sooner than `SEND_TICKS` from now. The larger of
    // the reported depth and the requests counted against the node estimates how many are
    // ahead.
    Load load = loads.get(node);
    long ahead = Math.max(load.reportedDepth, load.inFlight);
    if ((ahead + 1) / nodeCapacity + SEND_TICKS > MAX_TICKS_LATE) {
      env.metrics().count("path.rejected", 1, "reason", "queue_depth");
      drop(mobId, DropReason.QUEUE_DEPTH);
      return;
    }

    long requestId = nextRequestId++;
    Frames.Request request =
        new Frames.Request(requestId, classId, event.start(), event.target(), event.search());
    requestByMob.put(mobId, requestId);
    put(
        requestId,
        new Outstanding(
            mobId, node, region, startChunk, event.tick(), request, NOT_SENT, 0, false));
    load.batch.put(requestId, request);
    health.admitted(node, requestId, event.tick());
  }

  /**
   * The holder to serve a mob's request: a region's holders split its mobs between them by id, and
   * a mob whose own holder is not ready goes to the next one that is, in holder order. A holder is
   * ready once it has greeted the controller and has the chunk the search starts in, and, if {@code
   * inService}, while {@link NodeHealth} admits requests for it. Null if no holder is ready.
   */
  private NodeId holderFor(int mobId, Region region, long startChunk, boolean inService) {
    List<NodeId> holders = regions.holders(region.id());
    int first = Math.floorMod(mobId, holders.size());
    for (int i = 0; i < holders.size(); i++) {
      NodeId node = holders.get((first + i) % holders.size());
      if (links.greeted(node)
          && (!inService || health.admits(node))
          && streams.residentSince(node, startChunk) != null) {
        return node;
      }
    }
    return null;
  }

  void answer(NodeId node, Reply.Answer answer) {
    Outstanding request = outstanding.get(answer.requestId());
    if (request == null) {
      // A retry, or the answer to a request that was already settled.
      env.metrics().count("path.response.stale", 1);
      GivenUp gone = givenUp.remove(answer.requestId());
      if (gone != null && gone.node().equals(node)) {
        answered(node);
        health.answered(node);
      }
      return;
    }
    if (!request.node().equals(node)) {
      env.metrics().count("path.response.misrouted", 1, "node", node.toString());
      return;
    }
    answered(node);
    health.answered(node);
    if (request.abandoned()) {
      // The node is done with it, and the mob no longer wants it.
      remove(answer.requestId());
      env.metrics().count("path.response.abandoned", 1);
      return;
    }
    // The node must have held the chunk the search starts in when it searched. If the chunk
    // reached it later, or the node has held it as air since, as after its stream started
    // over, it searched air there, and a resend could get the same answer back.
    Long since = streams.residentSince(node, request.startChunk());
    if (since == null || answer.worldSequence() < since) {
      env.metrics().count("path.response.behind", 1, "node", node.toString());
      finish(answer.requestId(), request, DropReason.NO_NODE);
      return;
    }
    remove(answer.requestId());
    requestByMob.remove(request.mobId());
    env.metrics().record("path.latency.ticks", env.clock().tick() - request.issuedTick());
    env.metrics().count("path.answered", 1);
    out.pathComputed(
        new WorldOutput.PathResult(
            request.mobId(),
            request.request().target(),
            answer.reached(),
            inWorld(answer.nodes(), request.region()),
            answer.cost(),
            request.issuedTick()));
  }

  /**
   * The request arrived before the profile it names, which is still on the control channel. That
   * channel either delivers the profile or breaks the connection, so nothing is resent. The request
   * is dropped sooner than its timeout would drop it. The node never queued it, so its depth is
   * unchanged.
   */
  void refusal(NodeId node, Reply.Refusal refusal) {
    env.metrics().count("path.profile.unknown", 1, "node", node.toString());
    Outstanding request = outstanding.get(refusal.requestId());
    if (request == null || !request.node().equals(node)) {
      env.metrics().count("path.response.stale", 1);
      return;
    }
    health.answered(node);
    // A request outstanding on a node was admitted on its current connection, so its profile
    // was sent on that connection too. One that was not is a controller bug, which the node
    // cannot tell from a delay.
    if (!links.registered(node, (short) refusal.classId())) {
      violations.add(node + " was asked to use profile " + refusal.classId() + " never sent");
    }
    // The node does not remember a refusal, so a resent copy that arrives after the profile
    // would still be served. It is canceled, and the request stops counting against the node
    // like any other the node drops.
    if (request.resends() > 0) {
      loads.get(node).cancels.add(refusal.requestId());
    }
    if (request.abandoned()) {
      remove(refusal.requestId());
    } else {
      finish(refusal.requestId(), request, DropReason.CLASS_UNREGISTERED);
    }
  }

  void depth(NodeId node, long queueDepth) {
    loads.get(node).reportedDepth = (int) Math.min(Integer.MAX_VALUE, queueDepth);
  }

  /** A new link: nothing is queued for the node, and it has reported no depth on it. */
  void linkOpened(NodeId node) {
    reset(loads.get(node));
  }

  /** Every request on a lost link. The live ones are reported dropped. */
  void linkLost(NodeId node) {
    reset(loads.get(node));
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
        drop(request.mobId(), DropReason.NODE_LOST);
      }
    }
  }

  /**
   * The node went out of service with its link up: each live request on it is dropped, so its mob
   * stops waiting, and keeps counting against the node until it is due, since the node may still be
   * working on it.
   */
  void outOfService(NodeId node) {
    List<Long> live = new ArrayList<>();
    for (Map.Entry<Long, Outstanding> entry : outstanding.entrySet()) {
      Outstanding request = entry.getValue();
      if (request.node().equals(node) && !request.abandoned()) {
        live.add(entry.getKey());
      }
    }
    for (long requestId : live) {
      abandon(requestId, DropReason.NODE_LOST);
    }
  }

  private static void reset(Load load) {
    load.reportedDepth = 0;
    load.batch.clear();
    load.cancels.clear();
  }

  /**
   * A request not answered within the budget, resends included, is abandoned. This handles repeated
   * loss on the request stream, and a request to a node that went silent without resetting its
   * connection. Admission keeps a node's work within the budget, so an abandoned request older than
   * it stops counting against the node too. {@code outstanding} is in issue order, so the scan
   * stops at the first younger entry.
   */
  void expire(long tick) {
    givenUp.values().removeIf(gone -> tick - gone.issuedTick() > MAX_TICKS_LATE);
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
      drop(request.mobId(), DropReason.TIMEOUT);
    }
  }

  /**
   * Acts on every request whose answer is due by now, in issue order. A live one is added to its
   * node's batch again, so a lost request or answer costs a few ticks rather than a timeout. An
   * abandoned one stops counting against its node, which has served it by now, though its answer
   * may still be on its way.
   */
  void settleDue(long tick) {
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
        remove(requestId);
        givenUp.put(requestId, new GivenUp(request.node(), request.issuedTick()));
        continue;
      }
      if (request.resends() >= MAX_RESENDS) {
        continue;
      }
      put(requestId, request.resent());
      loads.get(request.node()).batch.put(requestId, request.request());
      env.metrics().count("path.resent", 1, "node", request.node().toString());
    }
  }

  /**
   * Each node's requests and cancels for the tick, in frames that each fit one datagram. They leave
   * together, so they may arrive in any order, and each request may wait behind the whole batch.
   */
  void flush() {
    for (Map.Entry<NodeId, Load> entry : loads.entrySet()) {
      Load load = entry.getValue();
      if (load.batch.isEmpty() && load.cancels.isEmpty()) {
        continue;
      }
      List<Frames.Request> batch = List.copyOf(load.batch.values());
      List<Long> cancels = load.cancels;
      load.batch = new LinkedHashMap<>();
      load.cancels = new ArrayList<>();
      NodeId node = entry.getKey();
      Region region = regions.region(regions.regionOf(node));
      long attempt = links.attempt(node);
      encoder.encode(
          "requests",
          Offload.Lane.REQUESTS,
          () -> requestFrames(run, region, batch, cancels),
          frames -> {
            // A retried encode can finish after the link it was built for ended. Its requests
            // were dropped with that link, and the profiles they name may not be registered on
            // this one.
            if (links.attempt(node) != attempt) {
              env.metrics().count("requests.discarded", 1, "node", node.toString());
              return;
            }
            sent(node, batch, cancels, frames);
          },
          () -> requeue(node, attempt, batch, cancels));
    }
  }

  /** Sends a node's encoded batch, and sets when each of its requests is due. */
  private void sent(
      NodeId node, List<Frames.Request> batch, List<Long> cancels, List<byte[]> frames) {
    for (byte[] frame : frames) {
      links.send(node, Channel.REQUEST, frame);
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
    // The node serves requests in the order they arrive, after the ones it is still expected
    // to hold, so the last of the batch is done once all of them are. Timing starts when the
    // batch leaves, not when it is built.
    long tick = env.clock().tick();
    long due = tick + RESEND_TICKS + (expectedQueue(node, tick) + batch.size()) / nodeCapacity;
    int first = 0;
    for (Frames.Request request : batch) {
      Outstanding waiting = outstanding.get(request.requestId());
      if (waiting != null) {
        first += waiting.resends() == 0 ? 1 : 0;
        put(request.requestId(), waiting.sent(due));
      }
    }
    health.sent(node, tick, first);
  }

  /**
   * A batch given up on goes back to its node's next batch, if its link still stands: each request
   * still waiting for it, and each cancel. A request that times out meanwhile leaves on its own.
   */
  private void requeue(NodeId node, long attempt, List<Frames.Request> batch, List<Long> cancels) {
    if (links.attempt(node) != attempt) {
      return;
    }
    Load load = loads.get(node);
    for (Frames.Request request : batch) {
      Outstanding waiting = outstanding.get(request.requestId());
      if (waiting != null && !waiting.abandoned() && waiting.dueTick() == NOT_SENT) {
        load.batch.put(request.requestId(), request);
      }
    }
    load.cancels.addAll(cancels);
  }

  /**
   * {@code batch} in frames of at most {@link Frames#MAX_REQUESTS_PER_FRAME}, then {@code cancels}
   * in frames of at most {@link Frames#MAX_CANCELS_PER_FRAME}.
   */
  private static List<byte[]> requestFrames(
      long run, Region region, List<Frames.Request> batch, List<Long> cancels) {
    List<byte[]> frames = new ArrayList<>();
    for (int from = 0; from < batch.size(); from += Frames.MAX_REQUESTS_PER_FRAME) {
      int to = Math.min(batch.size(), from + Frames.MAX_REQUESTS_PER_FRAME);
      frames.add(
          Frames.requests(run, region.id(), batch.subList(from, to), region.minX(), region.minZ()));
    }
    for (int from = 0; from < cancels.size(); from += Frames.MAX_CANCELS_PER_FRAME) {
      int to = Math.min(cancels.size(), from + Frames.MAX_CANCELS_PER_FRAME);
      frames.add(Frames.cancels(run, region.id(), cancels.subList(from, to)));
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
    for (int count : loads.get(node).dueCounts.tailMap(tick, false).values()) {
      queued += count;
    }
    return queued;
  }

  /** The node finished a request, so it holds one fewer than its last heartbeat said. */
  private void answered(NodeId node) {
    Load load = loads.get(node);
    load.reportedDepth = Math.max(0, load.reportedDepth - 1);
  }

  /** The answer's path in world positions. Positions come back relative to the region. */
  private static List<WorldOutput.Node> inWorld(List<WorldOutput.Node> path, Region region) {
    List<WorldOutput.Node> nodes = new ArrayList<>(path.size());
    for (WorldOutput.Node relative : path) {
      Pos pos = relative.pos();
      nodes.add(
          new WorldOutput.Node(
              new Pos(pos.x() + region.minX(), pos.y(), pos.z() + region.minZ()),
              relative.pathType()));
    }
    return nodes;
  }

  /**
   * Reports a live request dropped because its mob no longer wants it. One whose first copy is
   * still waiting for its batch never leaves and is simply removed. Otherwise a copy may be on its
   * way to the node or in the node's queue, and the request keeps counting against the node until
   * its answer is due or arrives. The node is told to drop a copy already sent, though the cancel
   * may be lost or come too late. A first copy still being encoded is not canceled: it leaves
   * anyway, a cancel would only race it, and a node that honored one would leave unanswered a
   * request the controller takes as a sign of its health.
   */
  private void abandon(long requestId, DropReason reason) {
    Outstanding request = outstanding.get(requestId);
    if (request == null || request.abandoned()) {
      return;
    }
    requestByMob.remove(request.mobId());
    Load load = loads.get(request.node());
    boolean waiting = load.batch.remove(requestId) != null;
    if (waiting && request.resends() == 0) {
      remove(requestId);
      health.withdrawn(request.node(), requestId);
    } else {
      boolean encoding = !waiting && request.dueTick() == NOT_SENT && request.resends() == 0;
      if (!encoding) {
        load.cancels.add(requestId);
      }
      put(requestId, request.abandon());
    }
    drop(request.mobId(), reason);
  }

  /** Reports a live request dropped once its node is done with it or never had it. */
  private void finish(long requestId, Outstanding request, DropReason reason) {
    remove(requestId);
    requestByMob.remove(request.mobId());
    drop(request.mobId(), reason);
  }

  /** Reports a request dropped, counted by reason so every drop shows in the metrics. */
  private void drop(int mobId, DropReason reason) {
    env.metrics().count("path.dropped", 1, "reason", reason.name());
    out.pathDropped(mobId, reason);
  }

  /** Adds or replaces a request, keeping the in-flight counters and due indexes in step. */
  private void put(long requestId, Outstanding request) {
    Outstanding previous = outstanding.put(requestId, request);
    if (previous == null) {
      loads.get(request.node()).inFlight++;
    } else {
      unindex(previous);
    }
    if (request.dueTick() != NOT_SENT) {
      loads.get(request.node()).dueCounts.merge(request.dueTick(), 1, Integer::sum);
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
    loads.get(request.node()).inFlight--;
    unindex(request);
  }

  /** Undoes what {@link #put} indexed for a request that changed or left. */
  private void unindex(Outstanding request) {
    if (request.dueTick() != NOT_SENT) {
      loads
          .get(request.node())
          .dueCounts
          .computeIfPresent(request.dueTick(), (tick, count) -> count > 1 ? count - 1 : null);
    }
  }

  /**
   * One mob mapping per live request, per-node in-flight counters that sum to the requests that
   * count against nodes, and due counts that match the requests sent.
   */
  void check(List<String> found) {
    int counted = 0;
    for (Map.Entry<NodeId, Load> entry : loads.entrySet()) {
      NodeId node = entry.getKey();
      Load load = entry.getValue();
      if (load.inFlight < 0) {
        found.add(node + " has a negative in-flight count: " + load.inFlight);
      }
      counted += load.inFlight;
      int due = 0;
      for (Outstanding request : outstanding.values()) {
        due += request.node().equals(node) && request.dueTick() != NOT_SENT ? 1 : 0;
      }
      int indexed = load.dueCounts.values().stream().mapToInt(Integer::intValue).sum();
      if (due != indexed) {
        found.add(node + " has " + due + " requests sent but " + indexed + " indexed");
      }
    }
    int live = 0;
    for (Outstanding request : outstanding.values()) {
      live += request.abandoned() ? 0 : 1;
    }
    if (requestByMob.size() != live) {
      found.add("mob index holds " + requestByMob.size() + " but " + live + " requests are live");
    }
    for (Map.Entry<Integer, Long> entry : requestByMob.entrySet()) {
      Outstanding request = outstanding.get(entry.getValue());
      if (request == null || request.abandoned() || request.mobId() != entry.getKey()) {
        found.add("mob " + entry.getKey() + " points at a request that is not live");
      }
    }
    if (counted != outstanding.size()) {
      found.add("in-flight counters sum to " + counted + ", outstanding is " + outstanding.size());
    }
  }
}
