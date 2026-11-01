package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Offload;
import io.github.davidmenggx.maestro.controller.env.RequestTrace;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.Regions;
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
import java.nio.ByteBuffer;
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
 * Admission, shedding, batching, resends, cancels, probes and answers for path requests. A request
 * goes to a holder of the region its search starts in: the mob's own by id, or another ready one if
 * its own holds more ahead of it than its {@link Priority} share allows. If every ready holder
 * does, it is shed.
 *
 * <p>Each node's queue is modeled as draining at its {@link Capacity} from when each batch leaves.
 * A heartbeat showing more queued than the model holds moves the model up to it.
 */
public final class RequestTracker {

  /** The tick budget: a request unanswered this many ticks after it is asked is given up. */
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
   * {@code hunting} is whether the mob had a target when it asked. {@code firstSent} is when the
   * first copy left. {@code dueTick} is when the latest copy should be answered. Both are {@link
   * #NOT_SENT} until then, and {@code dueTick} is {@link #ENCODING} while a first copy's batch
   * encodes. An {@code abandoned} request is reported dropped and canceled, but still counts
   * against its node.
   */
  private record Outstanding(
      int mobId,
      NodeId node,
      Region region,
      long startChunk,
      long issuedTick,
      boolean hunting,
      Frames.Request request,
      long firstSent,
      long dueTick,
      int resends,
      boolean abandoned) {

    Outstanding sent(long tick, long due) {
      return new Outstanding(
          mobId,
          node,
          region,
          startChunk,
          issuedTick,
          hunting,
          request,
          resends == 0 ? tick : firstSent,
          due,
          resends,
          abandoned);
    }

    Outstanding encoding() {
      return new Outstanding(
          mobId,
          node,
          region,
          startChunk,
          issuedTick,
          hunting,
          request,
          firstSent,
          ENCODING,
          resends,
          abandoned);
    }

    Outstanding resent() {
      return new Outstanding(
          mobId,
          node,
          region,
          startChunk,
          issuedTick,
          hunting,
          request,
          firstSent,
          NOT_SENT,
          resends + 1,
          abandoned);
    }

    Outstanding abandon() {
      return new Outstanding(
          mobId,
          node,
          region,
          startChunk,
          issuedTick,
          hunting,
          request,
          firstSent,
          dueTick,
          resends,
          true);
    }
  }

  private static final long NOT_SENT = -1;

  private static final long ENCODING = -2;

  static final long FIRST_PROBE_ID = 1L << 62;

  /** What one node is modeled to hold, and what waits for its next batch. */
  private static final class Load {

    /**
     * When, in ticks, the node is modeled to have served every request sent to it, at its capacity.
     * Dropped requests stay in it: the node may be serving them.
     */
    private double doneAt;

    /** First copies admitted for the node that wait for its next batch. */
    private int unsent;

    /** Requests waiting for the next batch, by id. */
    private Map<Long, Frames.Request> batch = new LinkedHashMap<>();

    /** Requests abandoned since the last flush whose copies may still wait on the node. */
    private List<Long> cancels = new ArrayList<>();

    /** Probes waiting for the next batch. */
    private List<Long> probes = new ArrayList<>();
  }

  /** A probe out: its node, when its latest copy is due, and how often it was sent again. */
  private record Probe(NodeId node, long dueTick, int resends) {

    Probe sent(long due) {
      return new Probe(node, due, resends);
    }

    Probe resent() {
      return new Probe(node, NOT_SENT, resends + 1);
    }
  }

  private final ControllerEnv env;
  private final WorldSource world;
  private final WorldOutput out;
  private final Regions regions;
  private final NodeLinks links;
  private final NodeHealth health;
  private final WorldStreams streams;
  private final Placement placement;
  private final Priority priority;
  private final Encoder encoder;
  private final Violations violations;
  private final long run;
  private final Capacity capacity;
  private final Dispatch dispatch;
  private final RequestTraces traces;

  private final Set<Integer> mobs = new LinkedHashSet<>();
  private final Map<NodeId, Load> loads = new LinkedHashMap<>();

  /**
   * Every request counting against a node, abandoned included, in issue order. Changed only through
   * {@link #put} and {@link #remove}.
   */
  private final Map<Long, Outstanding> outstanding = new LinkedHashMap<>();

  /** Probes out, by request id. */
  private final Map<Long, Probe> probes = new LinkedHashMap<>();

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

  /** Request ids count up from 1 and probe ids from {@link #FIRST_PROBE_ID}, so they never meet. */
  private long nextRequestId = 1;

  private long nextProbeId = FIRST_PROBE_ID;
  private int requestsIssued;

  RequestTracker(
      ControllerEnv env,
      WorldSource world,
      WorldOutput out,
      Regions regions,
      List<NodeId> fleet,
      NodeLinks links,
      NodeHealth health,
      WorldStreams streams,
      Placement placement,
      Priority priority,
      Encoder encoder,
      Violations violations,
      long run,
      Capacity capacity,
      Dispatch dispatch,
      RequestTraces traces) {
    this.env = env;
    this.world = world;
    this.out = out;
    this.regions = regions;
    this.links = links;
    this.health = health;
    this.streams = streams;
    this.placement = placement;
    this.priority = priority;
    this.encoder = encoder;
    this.violations = violations;
    this.run = run;
    this.capacity = capacity;
    this.dispatch = dispatch;
    this.traces = traces;
    for (NodeId node : fleet) {
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
        || !Frames.fits(event.start(), region.areaMinX(), region.areaMinZ())
        || !Frames.fits(event.target(), region.areaMinX(), region.areaMinZ())) {
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
    // A search that could read past the area would see air as a cliff.
    if (!SearchReach.fits(
        region.areaMinX(),
        region.areaMinZ(),
        region.areaSizeX(),
        region.areaSizeZ(),
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
    List<NodeId> ready = ready(mobId, region, startChunk, true);
    if (ready.isEmpty()) {
      // A ready holder out of service would have taken it, so search on the server.
      boolean unavailable = !ready(mobId, region, startChunk, false).isEmpty();
      env.metrics().count("path.rejected", 1, "reason", unavailable ? "unavailable" : "not_ready");
      drop(mobId, DropReason.UNAVAILABLE);
      return;
    }

    // Its own holder takes it, or the next with no more ahead of it than its share allows.
    double share = priority.share(event.start(), event.hunting());
    NodeId node = null;
    for (NodeId candidate : ready) {
      if (ahead(candidate, event.tick()) <= Priority.allowedAhead(share, capacity.of(candidate))) {
        node = candidate;
        break;
      }
    }
    if (node == null) {
      env.metrics().count("path.rejected", 1, "reason", "shed");
      placement.shed(region, event.start());
      drop(mobId, DropReason.SHED);
      return;
    }
    if (!node.equals(ready.get(0))) {
      env.metrics().count("path.shared", 1, "node", node.toString());
    }

    // A new profile abandons this request. The profile leaves now on the ordered control
    // channel, ahead of requests naming it; one that overtakes it is answered `UnknownProfile`.
    if (links.register(node, classId)) {
      links.send(node, Channel.CONTROL, Frames.mobClass(profile));
      env.metrics().count("mobclass.registered", 1, "node", node.toString());
      drop(mobId, DropReason.CLASS_UNREGISTERED);
      return;
    }

    long requestId = nextRequestId++;
    Frames.Request request =
        new Frames.Request(requestId, classId, event.start(), event.target(), event.search());
    requestByMob.put(mobId, requestId);
    put(
        requestId,
        new Outstanding(
            mobId,
            node,
            region,
            startChunk,
            event.tick(),
            event.hunting(),
            request,
            NOT_SENT,
            NOT_SENT,
            0,
            false));
    traces.admitted(requestId, mobId, classId, node, region.id(), event.hunting(), event.tick());
    placement.sent(region, event.start());
    if (dispatch == Dispatch.STREAM) {
      stream(node, region, request);
    } else {
      loads.get(node).batch.put(requestId, request);
    }
  }

  /**
   * Requests a request asked at {@code tick} would wait behind at the node: what the node is
   * modeled to hold when it leaves, after {@link #SEND_TICKS}, and the first copies that leave with
   * it.
   */
  private double ahead(NodeId node, long tick) {
    Load load = loads.get(node);
    double held = Math.max(0, load.doneAt - (tick + SEND_TICKS)) * capacity.of(node);
    return held + load.unsent;
  }

  /** A streamed request's frames, and when the worker sent them. */
  private record Streamed(List<byte[]> frames, long sentNanos) {}

  /**
   * Sends one request from the encode worker as soon as it is encoded. The send is recorded when
   * the encode is handed back, and a given-up encode joins the node's next batch.
   */
  private void stream(NodeId node, Region region, Frames.Request request) {
    List<Frames.Request> one = List.of(request);
    Map<Region, List<Frames.Request>> byRegion = new LinkedHashMap<>();
    byRegion.put(region, one);
    long attempt = links.attempt(node);
    Transport transport = env.transport();
    Clock clock = env.clock();
    long run = this.run;
    leaving(node, one);
    encoder.encode(
        "request",
        Offload.Lane.REQUESTS,
        () -> {
          List<byte[]> frames = requestFrames(run, byRegion, Regions.NONE, List.of(), List.of());
          long sentNanos = clock.nowNanos();
          for (byte[] frame : frames) {
            transport.send(node, Channel.REQUEST, ByteBuffer.wrap(frame));
          }
          return new Streamed(frames, sentNanos);
        },
        streamed -> {
          for (byte[] frame : streamed.frames()) {
            links.sentElsewhere(Channel.REQUEST, frame);
          }
          recordSent(node, one, List.of(), List.of(), streamed.frames(), streamed.sentNanos());
        },
        () -> requeue(node, attempt, one, List.of(), List.of()));
  }

  /**
   * The holders that could serve a mob's request, its own by id first, then the rest in holder
   * order. Ready means greeted, holding the start chunk, and, if {@code inService}, in service.
   */
  private List<NodeId> ready(int mobId, Region region, long startChunk, boolean inService) {
    List<NodeId> holders = regions.holders(region.id());
    List<NodeId> ready = new ArrayList<>(holders.size());
    int first = Math.floorMod(mobId, Math.max(1, holders.size()));
    for (int i = 0; i < holders.size(); i++) {
      NodeId node = holders.get((first + i) % holders.size());
      if (links.greeted(node)
          && (!inService || health.inService(node))
          && streams.residentSince(node, startChunk) != null) {
        ready.add(node);
      }
    }
    return ready;
  }

  /** {@code arrivedNanos} is when the transport heard the answer. */
  void answer(NodeId node, Reply.Answer answer, long arrivedNanos) {
    long tick = env.clock().tick();
    Probe probe = probes.get(answer.requestId());
    if (probe != null) {
      if (!probe.node().equals(node)) {
        env.metrics().count("path.response.misrouted", 1, "node", node.toString());
        return;
      }
      probes.remove(answer.requestId());
      env.metrics().count("node.probe.answered", 1, "node", node.toString());
      health.probeAnswered(node, answer.requestId(), tick);
      return;
    }
    Outstanding request = outstanding.get(answer.requestId());
    if (request == null) {
      // A retry, or the answer to a request that was already settled.
      env.metrics().count("path.response.stale", 1);
      GivenUp gone = givenUp.remove(answer.requestId());
      if (gone != null && gone.node().equals(node)) {
        health.answered(node, tick);
      }
      return;
    }
    if (!request.node().equals(node)) {
      env.metrics().count("path.response.misrouted", 1, "node", node.toString());
      return;
    }
    // Once per request: a later copy finds it settled above. Sequence 0 is an answer not searched.
    env.metrics().record("node.wait.micros", answer.waitMicros(), "node", node.toString());
    if (answer.worldSequence() != 0) {
      env.metrics().record("node.search.micros", answer.searchMicros(), "node", node.toString());
    }
    health.answered(node, tick);
    if (request.abandoned()) {
      // The node is done with it, and the mob no longer wants it.
      remove(answer.requestId());
      env.metrics().count("path.response.abandoned", 1);
      return;
    }
    traces.answered(answer.requestId(), answer, arrivedNanos);
    // The node must have held the start chunk when it searched, else it searched air.
    Long since = streams.residentSince(node, request.startChunk());
    if (since == null || answer.worldSequence() < since) {
      env.metrics().count("path.response.behind", 1, "node", node.toString());
      finish(answer.requestId(), request, DropReason.NO_NODE);
      return;
    }
    remove(answer.requestId());
    requestByMob.remove(request.mobId());
    String hunting = Boolean.toString(request.hunting());
    env.metrics()
        .record(
            "path.latency.ticks",
            tick - request.issuedTick(),
            "node",
            node.toString(),
            "hunting",
            hunting);
    env.metrics().count("path.answered", 1, "node", node.toString(), "hunting", hunting);
    out.pathComputed(
        new WorldOutput.PathResult(
            request.mobId(),
            request.request().target(),
            answer.reached(),
            inWorld(answer.nodes(), request.region()),
            answer.cost(),
            request.issuedTick()));
    traces.ended(answer.requestId(), RequestTrace.ANSWERED);
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
    health.answered(node, env.clock().tick());
    // An outstanding request was admitted on this connection, so a missing profile is a
    // controller bug.
    if (!links.registered(node, (short) refusal.classId())) {
      violations.add(node + " was asked to use profile " + refusal.classId() + " never sent");
    }
    if (request.abandoned()) {
      remove(refusal.requestId());
      return;
    }
    // The node does not remember refusals, so a late resend would be served. Cancel it.
    if (request.resends() > 0) {
      loads.get(node).cancels.add(refusal.requestId());
    }
    finish(refusal.requestId(), request, DropReason.CLASS_UNREGISTERED);
  }

  /**
   * The node reported {@code queueDepth} requests waiting or running. More than the model holds
   * means the node serves slower than the model has it, so the model moves up to it.
   */
  void depth(NodeId node, long queueDepth) {
    Load load = loads.get(node);
    load.doneAt = Math.max(load.doneAt, env.clock().tick() + queueDepth / capacity.of(node));
  }

  /** A new link: nothing is queued for the node. */
  void linkOpened(NodeId node) {
    reset(node);
  }

  /** Every request and probe on a lost link. The live requests are reported dropped. */
  void linkLost(NodeId node) {
    reset(node);
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
        dropAdmitted(requestId, request.mobId(), DropReason.NODE_LOST);
      }
    }
  }

  /**
   * The node left service or stopped holding its region, with its link up. It may still answer what
   * it holds, so its live requests are dropped but count against it until due.
   */
  void abandonLive(NodeId node) {
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

  private void reset(NodeId node) {
    Load load = loads.get(node);
    load.doneAt = 0;
    load.batch.clear();
    load.cancels.clear();
    load.probes.clear();
    probes.values().removeIf(probe -> probe.node().equals(node));
  }

  /** Probes the node at the next flush, as {@link NodeHealth#probesDue} named it. */
  void probe(NodeId node, long tick) {
    long requestId = nextProbeId++;
    health.probeSent(node, requestId, tick);
    probes.put(requestId, new Probe(node, NOT_SENT, 0));
    loads.get(node).probes.add(requestId);
    env.metrics().count("node.probe.sent", 1, "node", node.toString());
  }

  /**
   * Abandons a request unanswered within the budget, resends included. Older abandoned ones stop
   * counting against the node. The scan stops at the first younger entry.
   */
  void expire(long tick) {
    givenUp.values().removeIf(gone -> tick - gone.issuedTick() > MAX_TICKS_LATE);
    Iterator<Map.Entry<Long, Outstanding>> entries = outstanding.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<Long, Outstanding> entry = entries.next();
      Outstanding request = entry.getValue();
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
      dropAdmitted(entry.getKey(), request.mobId(), DropReason.TIMEOUT);
    }
  }

  /**
   * Acts on requests due by now, in issue order. Abandoned ones stop counting, and show nothing, as
   * their node may have dropped them on purpose. A live one whose first copy fell due unanswered
   * shows the node missed it, and rejoins the batch. A probe due unanswered is sent again while it
   * is its node's probe.
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
      if (request.resends() == 0) {
        health.missed(request.node(), request.firstSent());
      }
      if (request.resends() >= MAX_RESENDS) {
        continue;
      }
      put(requestId, request.resent());
      loads.get(request.node()).batch.put(requestId, request.request());
      env.metrics().count("path.resent", 1, "node", request.node().toString());
    }
    Iterator<Map.Entry<Long, Probe>> out = probes.entrySet().iterator();
    while (out.hasNext()) {
      Map.Entry<Long, Probe> entry = out.next();
      Probe probe = entry.getValue();
      if (probe.dueTick() == NOT_SENT || probe.dueTick() > tick) {
        continue;
      }
      if (health.probeOut(probe.node()) != entry.getKey()) {
        out.remove();
      } else if (probe.resends() < MAX_RESENDS) {
        entry.setValue(probe.resent());
        loads.get(probe.node()).probes.add(entry.getKey());
      }
    }
  }

  /**
   * Each node's requests, cancels and probes for the tick, in datagram-sized frames. They may
   * arrive in any order. A request names the region it was admitted for, which a node that has
   * moved on answers as if it held no area.
   */
  void flush() {
    for (Map.Entry<NodeId, Load> entry : loads.entrySet()) {
      Load load = entry.getValue();
      if (load.batch.isEmpty() && load.cancels.isEmpty() && load.probes.isEmpty()) {
        continue;
      }
      List<Frames.Request> batch = List.copyOf(load.batch.values());
      List<Long> cancels = load.cancels;
      List<Long> probing = load.probes;
      load.batch = new LinkedHashMap<>();
      load.cancels = new ArrayList<>();
      load.probes = new ArrayList<>();
      NodeId node = entry.getKey();
      Map<Region, List<Frames.Request>> byRegion = new LinkedHashMap<>();
      for (Frames.Request request : batch) {
        // One requeued after a given-up encode may have expired since.
        Outstanding waiting = outstanding.get(request.requestId());
        if (waiting != null) {
          byRegion.computeIfAbsent(waiting.region(), unseen -> new ArrayList<>()).add(request);
        }
      }
      int cancelRegion = regions.regionOf(node);
      long attempt = links.attempt(node);
      leaving(node, batch);
      encoder.encode(
          "requests",
          Offload.Lane.REQUESTS,
          () -> requestFrames(run, byRegion, cancelRegion, cancels, probing),
          frames -> {
            // The link ended while this encode retried; its requests were dropped and
            // its profiles may be missing here.
            if (links.attempt(node) != attempt) {
              env.metrics().count("requests.discarded", 1, "node", node.toString());
              return;
            }
            sent(node, batch, cancels, probing, frames);
          },
          () -> requeue(node, attempt, batch, cancels, probing));
    }
  }

  /** Sends a node's encoded batch, and sets when each of its requests and probes is due. */
  private void sent(
      NodeId node,
      List<Frames.Request> batch,
      List<Long> cancels,
      List<Long> probing,
      List<byte[]> frames) {
    // Read first, as an early frame's answer can be heard before the last frame leaves.
    long sentNanos = env.clock().nowNanos();
    for (byte[] frame : frames) {
      links.send(node, Channel.REQUEST, frame);
    }
    recordSent(node, batch, cancels, probing, frames, sentNanos);
  }

  /**
   * Adds the first copies of a batch handed to the encoder to the node's model, as leaving after
   * {@link #SEND_TICKS}. An encode that runs past the next hand-over sends later, but still before
   * any later batch, so the requests asked meanwhile do not wait behind it all.
   */
  private void leaving(NodeId node, List<Frames.Request> batch) {
    int fresh = 0;
    for (Frames.Request request : batch) {
      Outstanding waiting = outstanding.get(request.requestId());
      if (waiting != null && unsent(waiting)) {
        put(request.requestId(), waiting.encoding());
        traces.encoding(request.requestId());
        fresh++;
      }
    }
    // The node serves in arrival order, so the last request is done once all are. A resend adds
    // no work the model does not hold.
    Load load = loads.get(node);
    load.doneAt =
        Math.max(load.doneAt, env.clock().tick() + SEND_TICKS) + fresh / capacity.of(node);
  }

  /**
   * Sets when each request and probe of a batch that left at {@code sentNanos} is due: once the
   * node has served everything sent to it, as the model has it, plus {@link #RESEND_TICKS}.
   */
  private void recordSent(
      NodeId node,
      List<Frames.Request> batch,
      List<Long> cancels,
      List<Long> probing,
      List<byte[]> frames,
      long sentNanos) {
    // Settled or not: a streamed request's answer can be handed over before its send.
    for (Frames.Request request : batch) {
      traces.sent(request.requestId(), sentNanos);
    }
    if (!cancels.isEmpty()) {
      env.metrics().count("path.cancels", cancels.size(), "node", node.toString());
    }
    if (batch.isEmpty() && probing.isEmpty()) {
      return;
    }
    if (!batch.isEmpty()) {
      env.metrics()
          .count(
              "requests.frames",
              frameCount(batch.size(), Frames.MAX_REQUESTS_PER_FRAME),
              "node",
              node.toString());
    }
    long tick = env.clock().tick();
    long due = (long) Math.floor(loads.get(node).doneAt) + RESEND_TICKS;
    for (Frames.Request request : batch) {
      Outstanding waiting = outstanding.get(request.requestId());
      if (waiting != null) {
        put(request.requestId(), waiting.sent(tick, due));
      }
    }
    for (long requestId : probing) {
      Probe probe = probes.get(requestId);
      if (probe != null && probe.dueTick() == NOT_SENT) {
        probes.put(requestId, probe.sent(due));
      }
    }
  }

  /**
   * Returns a given-up batch's live requests, cancels and current probes to the node's next batch
   * if its link stands. The model keeps the first copies it already holds.
   */
  private void requeue(
      NodeId node,
      long attempt,
      List<Frames.Request> batch,
      List<Long> cancels,
      List<Long> probing) {
    if (links.attempt(node) != attempt) {
      return;
    }
    Load load = loads.get(node);
    for (Frames.Request request : batch) {
      long requestId = request.requestId();
      Outstanding waiting = outstanding.get(requestId);
      if (waiting != null && waiting.dueTick() < 0 && !waiting.abandoned()) {
        load.batch.put(requestId, request);
      }
    }
    load.cancels.addAll(cancels);
    for (long requestId : probing) {
      Probe probe = probes.get(requestId);
      if (probe != null && probe.dueTick() == NOT_SENT && health.probeOut(node) == requestId) {
        load.probes.add(requestId);
      }
    }
  }

  /**
   * Each region's requests, positioned in its area, then {@code cancels}, under {@code
   * cancelRegion}, then one frame per probe, each in frames within the {@link Frames} limits. A
   * node cancels by id alone.
   */
  private static List<byte[]> requestFrames(
      long run,
      Map<Region, List<Frames.Request>> byRegion,
      int cancelRegion,
      List<Long> cancels,
      List<Long> probing) {
    List<byte[]> frames = new ArrayList<>();
    for (Map.Entry<Region, List<Frames.Request>> entry : byRegion.entrySet()) {
      Region region = entry.getKey();
      List<Frames.Request> batch = entry.getValue();
      for (int from = 0; from < batch.size(); from += Frames.MAX_REQUESTS_PER_FRAME) {
        int to = Math.min(batch.size(), from + Frames.MAX_REQUESTS_PER_FRAME);
        frames.add(
            Frames.requests(
                run, region.id(), batch.subList(from, to), region.areaMinX(), region.areaMinZ()));
      }
    }
    for (int from = 0; from < cancels.size(); from += Frames.MAX_CANCELS_PER_FRAME) {
      int to = Math.min(cancels.size(), from + Frames.MAX_CANCELS_PER_FRAME);
      frames.add(Frames.cancels(run, cancelRegion, cancels.subList(from, to)));
    }
    for (long requestId : probing) {
      frames.add(Frames.probe(run, requestId));
    }
    return frames;
  }

  private static int frameCount(int items, int perFrame) {
    return (items + perFrame - 1) / perFrame;
  }

  /** The answer's path in world positions. Positions come back relative to the area. */
  private static List<WorldOutput.Node> inWorld(List<WorldOutput.Node> path, Region region) {
    List<WorldOutput.Node> nodes = new ArrayList<>(path.size());
    for (WorldOutput.Node relative : path) {
      Pos pos = relative.pos();
      nodes.add(
          new WorldOutput.Node(
              new Pos(pos.x() + region.areaMinX(), pos.y(), pos.z() + region.areaMinZ()),
              relative.pathType()));
    }
    return nodes;
  }

  /**
   * Reports a live request dropped because its mob no longer wants it. A request whose first copy
   * awaits a batch is just removed. Otherwise it is canceled (best effort) and stays in its node's
   * model, counting as outstanding until due or answered. A node remembers a cancel for an id it
   * has not seen, so a cancel that overtakes a copy still being encoded drops that copy too.
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
    } else {
      load.cancels.add(requestId);
      put(requestId, request.abandon());
    }
    dropAdmitted(requestId, request.mobId(), reason);
  }

  /** Reports a live request dropped once its node is done with it or never had it. */
  private void finish(long requestId, Outstanding request, DropReason reason) {
    remove(requestId);
    requestByMob.remove(request.mobId());
    dropAdmitted(requestId, request.mobId(), reason);
  }

  /** Reports an admitted request dropped, ending its trace. */
  private void dropAdmitted(long requestId, int mobId, DropReason reason) {
    drop(mobId, reason);
    traces.ended(requestId, reason.name());
  }

  /** Reports a request dropped, counted by reason so every drop shows in the metrics. */
  private void drop(int mobId, DropReason reason) {
    env.metrics().count("path.dropped", 1, "reason", reason.name());
    out.pathDropped(mobId, reason);
  }

  /** Adds or replaces a request, keeping the unsent counters and the due index in step. */
  private void put(long requestId, Outstanding request) {
    Outstanding previous = outstanding.put(requestId, request);
    if (previous != null) {
      left(previous);
    }
    loads.get(request.node()).unsent += unsent(request) ? 1 : 0;
    if (request.dueTick() >= 0) {
      dueIndex.computeIfAbsent(request.dueTick(), tick -> new ArrayList<>()).add(requestId);
    }
  }

  /** Removes a request, keeping the unsent counters in step. */
  private Outstanding remove(long requestId) {
    Outstanding previous = outstanding.remove(requestId);
    if (previous != null) {
      left(previous);
    }
    return previous;
  }

  /** Undoes what {@link #put} counted for a request that changed or left. */
  private void left(Outstanding request) {
    loads.get(request.node()).unsent -= unsent(request) ? 1 : 0;
  }

  /** Whether the request is a first copy waiting for its batch. */
  private static boolean unsent(Outstanding request) {
    return request.resends() == 0 && request.dueTick() == NOT_SENT;
  }

  /**
   * One mob mapping per live request, unsent counters matching the requests not yet sent, and no
   * trace left open.
   */
  void check(List<String> found) {
    Map<NodeId, Integer> unsent = new LinkedHashMap<>();
    for (Outstanding request : outstanding.values()) {
      unsent.merge(request.node(), unsent(request) ? 1 : 0, Integer::sum);
    }
    for (Map.Entry<NodeId, Load> entry : loads.entrySet()) {
      int counted = entry.getValue().unsent;
      int actual = unsent.getOrDefault(entry.getKey(), 0);
      if (counted != actual) {
        found.add(
            entry.getKey() + " counts " + counted + " requests unsent, but " + actual + " are");
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
    traces.check(new LinkedHashSet<>(requestByMob.values()), found);
  }
}
