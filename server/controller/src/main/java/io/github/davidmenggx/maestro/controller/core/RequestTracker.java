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

/** Admission, batching, resends, cancels and answers for path requests. */
public final class RequestTracker {

  /** Requests that cannot be answered within this many ticks are not sent. */
  public static final int MAX_TICKS_LATE = 10;

  /** Ticks from a request to the earliest tick its node usually serves it. */
  public static final int SEND_TICKS = 1;

  /**
   * Ticks late before a request is resent under the same id, at most {@link #MAX_RESENDS} times. A
   * node answers a seen request with the same bytes, so a crossed resend costs only bandwidth.
   */
  static final int RESEND_TICKS = 1;

  public static final int MAX_RESENDS = 2;

  /**
   * {@code request} is kept for resends. {@code startChunk} is the chunk the search starts in.
   * {@code dueTick} is when the latest copy should be answered, or {@link #NOT_SENT} while it waits
   * to be encoded. An {@code abandoned} request is reported dropped but still counts against its
   * node.
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
     * Depth in the last heartbeat, less answers seen since. Errs high until the next heartbeat.
     * Admission uses the larger of it and {@link #inFlight}.
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
   * Every request counting against a node, abandoned included, in issue order. Changed only through
   * {@link #put} and {@link #remove}.
   */
  private final Map<Long, Outstanding> outstanding = new LinkedHashMap<>();

  /** Request ids by due tick. Entries can go stale, so check against {@link #outstanding}. */
  private final TreeMap<Long, List<Long>> dueIndex = new TreeMap<>();

  /** Each mob's live request. */
  private final Map<Integer, Long> requestByMob = new LinkedHashMap<>();

  /** A request given up on that no longer counts against its node, and when it was issued. */
  private record GivenUp(NodeId node, long issuedTick) {}

  /**
   * Given-up requests that stopped counting against their node, by id, until their tick budget
   * ends. A late answer within it shows the request path works.
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
    // A repeat replaces the mob's older request, which is reported dropped.
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
    // A search that could read past the region would see air as a cliff.
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
    // The start chunk must be resident on the node. Its frame usually arrives a tick ahead; if
    // not, the answer's world sequence shows it.
    long startChunk = Chunks.key(Chunks.of(event.start().x()), Chunks.of(event.start().z()));
    NodeId node = holderFor(mobId, region, startChunk, true);
    if (node == null) {
      // A ready holder out of service would have taken it, so search on the server.
      boolean unavailable = holderFor(mobId, region, startChunk, false) != null;
      env.metrics().count("path.rejected", 1, "reason", unavailable ? "unavailable" : "not_ready");
      drop(mobId, DropReason.UNAVAILABLE);
      return;
    }

    // A new profile abandons this request. The profile leaves now on the ordered control
    // channel, ahead of requests naming it; one that overtakes it is answered `UnknownProfile`.
    if (links.register(node, classId)) {
      links.send(node, Channel.CONTROL, Frames.mobClass(profile));
      env.metrics().count("mobclass.registered", 1, "node", node.toString());
      drop(mobId, DropReason.CLASS_UNREGISTERED);
      return;
    }

    // Due once the requests ahead are served at the assumed capacity, and no sooner than
    // `SEND_TICKS`. The larger of reported depth and counted requests estimates those ahead.
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
   * The holder to serve a mob's request: its own by id, else the next ready one in holder order.
   * Ready means greeted, holding the start chunk, and, if {@code inService}, admitted by {@link
   * NodeHealth}. Null if none.
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
    // The node must have held the start chunk when it searched, else it searched air.
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
   * The request beat its profile on the control channel, which delivers it or breaks the
   * connection, so nothing is resent. Dropped early; the node never queued it.
   */
  void refusal(NodeId node, Reply.Refusal refusal) {
    env.metrics().count("path.profile.unknown", 1, "node", node.toString());
    Outstanding request = outstanding.get(refusal.requestId());
    if (request == null || !request.node().equals(node)) {
      env.metrics().count("path.response.stale", 1);
      return;
    }
    health.answered(node);
    // An outstanding request was admitted on this connection, so a missing profile is a
    // controller bug.
    if (!links.registered(node, (short) refusal.classId())) {
      violations.add(node + " was asked to use profile " + refusal.classId() + " never sent");
    }
    // The node does not remember refusals, so a late resend would be served. Cancel it.
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
   * The node left service with its link up: live requests are dropped but count against it until
   * due.
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
   * Abandons a request unanswered within the budget, resends included. Older abandoned ones stop
   * counting against the node. The scan stops at the first younger entry.
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
   * Acts on requests due by now, in issue order. Live ones rejoin the batch; abandoned ones stop
   * counting.
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
   * Each node's requests and cancels for the tick, in datagram-sized frames. They may arrive in any
   * order.
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
            // The link ended while this encode retried; its requests were dropped and
            // its profiles may be missing here.
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
    // The node serves in arrival order, so the last request is done once all are. Timing starts
    // when the batch leaves.
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
   * Returns a given-up batch's waiting requests and cancels to the node's next batch if its link
   * stands.
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

  /** {@code batch} then {@code cancels}, each in frames within the {@link Frames} limits. */
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

  /** Requests sent to the node and not yet due, at {@code nodeCapacity} served a tick. */
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
   * Reports a live request dropped because its mob no longer wants it. A request whose first copy
   * awaits a batch is just removed. Otherwise it counts against the node until due or answered, and
   * a sent copy is canceled (best effort). A first copy still being encoded is not canceled: the
   * cancel would race it, and a node honoring one would look unhealthy.
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
   * One mob mapping per live request, in-flight counters summing to the requests counting against
   * nodes, and due counts matching sent requests.
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
