package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.wire.Reply;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Streams each region's world to its holders ({@link WorldStreams}), handles path requests ({@link
 * RequestTracker}), links to nodes ({@link NodeLinks}), removes unresponsive nodes from service
 * ({@link NodeHealth}) and moves regions between nodes ({@link Placement}).
 */
public final class Controller implements WorldInput, Transport.Listener, Placement.Changes {

  /**
   * How the controller runs. The run starts with {@link RegionLayout#initial} of the fleet past
   * {@code spares}. {@code nodeCapacity} is the requests a node serves per tick, as admission
   * assumes until a node's heartbeats show it serves fewer. {@code traceSampleRate} is the share of
   * admitted requests traced, 0 to 1.
   */
  public record Settings(
      RegionLayout layout,
      int spares,
      int nodeCapacity,
      Dispatch dispatch,
      Placement.Policy policy,
      double traceSampleRate) {

    public Settings {
      Objects.requireNonNull(layout, "layout");
      Objects.requireNonNull(dispatch, "dispatch");
      Objects.requireNonNull(policy, "policy");
      if (spares < 0 || nodeCapacity < 1) {
        throw new IllegalArgumentException(
            "spares must not be negative and capacity positive: " + spares + ", " + nodeCapacity);
      }
      if (!(traceSampleRate >= 0 && traceSampleRate <= 1)) {
        throw new IllegalArgumentException("trace sample rate must be 0 to 1: " + traceSampleRate);
      }
    }
  }

  private final ControllerEnv env;
  private final Violations violations = new Violations();
  private final RegionMap regions;
  private final NodeLinks links;
  private final NodeHealth health;
  private final Capacity capacity;
  private final WorldStreams streams;
  private final Priority priority = new Priority();
  private final Placement placement;
  private final RequestTraces traces;
  private final RequestTracker requests;
  private final List<NodeId> fleet;

  /** Each node's last heartbeat, for the area it reported holding. */
  private final Map<NodeId, Reply.Beat> beats = new LinkedHashMap<>();

  private final long run;

  public Controller(
      ControllerEnv env,
      WorldSource world,
      WorldOutput out,
      List<NodeId> fleet,
      Settings settings) {
    this.env = Objects.requireNonNull(env, "env");
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(out, "out");
    Objects.requireNonNull(settings, "settings");
    this.run = env.run();
    this.fleet = List.copyOf(fleet);
    this.regions = settings.layout().initial(fleet, settings.spares());
    Encoder encoder = new Encoder(env);
    this.links = new NodeLinks(env, run, fleet);
    this.health = new NodeHealth(env.metrics(), fleet);
    this.capacity = new Capacity(settings.nodeCapacity(), fleet);
    this.streams = new WorldStreams(env, world, regions, links, encoder, violations, run);
    this.placement =
        new Placement(regions, fleet, links, health, settings.policy(), capacity, this, env);
    this.traces = new RequestTraces(env, settings.traceSampleRate());
    this.requests =
        new RequestTracker(
            env,
            world,
            out,
            regions,
            fleet,
            links,
            health,
            streams,
            placement,
            priority,
            encoder,
            violations,
            run,
            capacity,
            settings.dispatch(),
            traces);
    env.transport().listen(this);
  }

  // Accessors for harness assertions and production checks.

  /** Identifies this controller process to the fleet. */
  public long run() {
    return run;
  }

  public int requestsIssued() {
    return requests.requestsIssued();
  }

  /** Requests that still count against a node, abandoned ones included. */
  public int outstandingCount() {
    return requests.outstandingCount();
  }

  /** Traced requests answered but not yet reported, which are lost if this controller stops now. */
  public int tracesUnreported() {
    return traces.answeredUnreported();
  }

  /** The world sequence the node reports once it has everything produced for its region. */
  public long heldSequence(NodeId node) {
    return streams.heldSequence(node);
  }

  /** Whether a holder at {@link #heldSequence} holds what the world holds now. */
  public boolean regionSettled(int regionId) {
    return streams.regionSettled(regionId);
  }

  /** Whether the node is past its handshake on a live link. */
  public boolean linked(NodeId node) {
    return links.greeted(node);
  }

  /** Whether the node is not suspected and its request path flows: not quiet, not being probed. */
  public boolean inService(NodeId node) {
    return health.inService(node);
  }

  /** Whether the node can take a region: linked, in service, and its request path shown to work. */
  public boolean serves(NodeId node) {
    return placement.up(node);
  }

  /** The regions and their holders now. Changed only by the controller, at a tick's start. */
  public Regions regions() {
    return regions;
  }

  /** Counts changes to {@link #regions}, so a reader can tell when to look again. */
  public int regionsVersion() {
    return placement.version();
  }

  /** Bookkeeping that must be consistent, and every violation the controller noticed itself. */
  public List<String> selfCheck() {
    List<String> found = new ArrayList<>(violations.list());
    regions.check(found);
    links.check(found);
    streams.check(found);
    requests.check(found);
    return found;
  }

  @Override
  public void accept(WorldEvent event) {
    switch (event) {
      case WorldEvent.TickBegan began -> {
        // What the hand-over just finished, sends included.
        traces.flush();
        reportLag();
        requests.expire(began.tick());
        for (NodeId node : health.evaluate(began.tick(), env.clock().nowNanos())) {
          requests.abandonLive(node);
        }
        placement.evaluate(began.tick());
        links.dial(began.tick());
        for (NodeId node : health.probesDue(began.tick(), links::greeted)) {
          requests.probe(node, began.tick());
        }
      }
      case WorldEvent.TickEnded ended -> {
        traces.flush();
        // Block changes first, while the chunks about to be read are still marked.
        streams.flushCells();
        streams.flushChunks();
        requests.settleDue(ended.tick());
        requests.flush();
        env.metrics().gauge("controller.outstanding", requests.outstandingCount());
      }
      case WorldEvent.BlockChanged changed -> streams.blockChanged(changed);
      case WorldEvent.ChunkLoaded loaded ->
          streams.chunkResidency(loaded.chunkX(), loaded.chunkZ(), true);
      case WorldEvent.ChunkUnloaded unloaded ->
          streams.chunkResidency(unloaded.chunkX(), unloaded.chunkZ(), false);
      case WorldEvent.MobSpawned spawned -> requests.mobSpawned(spawned.mobId());
      case WorldEvent.MobRemoved removed -> requests.mobRemoved(removed.mobId());
      case WorldEvent.PathRequested requested -> requests.pathRequested(requested);
      case WorldEvent.PathCanceled canceled -> requests.pathCanceled(canceled.mobId());
      case WorldEvent.PlayerMoved moved -> priority.moved(moved.playerId(), moved.pos());
      case WorldEvent.PlayerLeft left -> priority.left(left.playerId());
    }
  }

  /**
   * Frames of its region's stream each node has yet to apply, from its last heartbeat, so the lag
   * of a node gone quiet keeps growing. A node whose last heartbeat names none of its region has
   * applied nothing past the assignment it was last sent. 0 for a node holding none.
   */
  private void reportLag() {
    for (NodeId node : fleet) {
      long lag = 0;
      int region = regions.regionOf(node);
      if (region != Regions.NONE) {
        Reply.Beat beat = beats.get(node);
        long applied =
            beat != null && beat.regionId() == region
                ? beat.sequence()
                : streams.assignedSequence(node);
        lag = streams.heldSequence(node) - applied;
      }
      env.metrics().gauge("world.lag", lag, "node", node.toString());
    }
  }

  @Override
  public void regionSplit(Region parent, Region lower, Region upper) {
    streams.split(parent, lower, upper);
    // Their requests name the parent, which they no longer hold.
    for (NodeId node : regions.holders(lower.id())) {
      requests.abandonLive(node);
    }
  }

  @Override
  public void holderAdded(Region region, NodeId node) {
    streams.holderAdded(region.id(), node);
  }

  @Override
  public void holderRemoved(Region region, NodeId node) {
    streams.holderRemoved(region.id(), node);
    requests.abandonLive(node);
  }

  @Override
  public void onConnected(NodeId node) {
    if (!links.inFleet(node)) {
      env.metrics().count("transport.unknown.node", 1);
      return;
    }
    links.connected(node);
    requests.linkOpened(node);
  }

  @Override
  public void onDisconnected(NodeId node) {
    if (!links.inFleet(node)) {
      env.metrics().count("transport.unknown.node", 1);
      return;
    }
    links.disconnected(node);
    health.disconnected(node);
    capacity.linkLost(node);
    requests.linkLost(node);
  }

  @Override
  public void onFrame(NodeId node, Channel channel, ByteBuffer frame, long arrivedNanos) {
    if (!links.inFleet(node)) {
      env.metrics().count("transport.unknown.node", 1);
      return;
    }
    if (channel == Channel.WORLD) {
      violations.add(node + " sent a frame on the world channel");
      return;
    }
    Reply reply;
    try {
      reply =
          channel == Channel.REQUEST
              ? Frames.readResponse(frame, run)
              : Frames.readControlReply(frame);
    } catch (RuntimeException e) {
      // A datagram can arrive cut short, or from something that is not a node.
      env.metrics().count("controller.malformed.frame", 1, "channel", channel.name());
      return;
    }
    switch (reply) {
      case Reply.Answer answer -> requests.answer(node, answer, arrivedNanos);
      case Reply.Refusal refusal -> requests.refusal(node, refusal);
      case Reply.Greeting greeting -> {
        links.greeted(node, greeting.pathfinder());
        health.greeted(node, arrivedNanos);
        streams.hello(node, greeting);
      }
      case Reply.Beat beat -> {
        // The rate first, which the backlog is read at.
        capacity.heartbeat(node, beat.searches(), beat.workerMicros(), beat.workers());
        requests.depth(node, beat.queueDepth());
        health.heartbeat(node, arrivedNanos);
        String label = node.toString();
        // The only view of a node's backlog and speed in production. Its worker time per second and
        // per worker is its utilization.
        env.metrics().record("node.queue.reported", beat.queueDepth(), "node", label);
        env.metrics().gauge("node.queue.depth", beat.queueDepth(), "node", label);
        env.metrics().count("node.searches", beat.searches(), "node", label);
        env.metrics().count("node.worker.micros", beat.workerMicros(), "node", label);
        env.metrics().gauge("node.workers", beat.workers(), "node", label);
        env.metrics().gauge("node.capacity", capacity.of(node), "node", label);
        beats.put(node, beat);
      }
      case Reply.Foreign ignored -> env.metrics().count("path.response.foreign", 1);
      case null -> env.metrics().count("controller.unknown.frame", 1, "channel", channel.name());
    }
  }
}
