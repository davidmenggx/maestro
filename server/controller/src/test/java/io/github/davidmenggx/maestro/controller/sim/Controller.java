package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.wire.Reply;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The controller. It streams each region's world to the nodes holding it ({@link WorldStreams}),
 * admits, batches, resends and cancels path requests and applies their answers ({@link
 * RequestTracker}), and keeps a link to every node ({@link NodeLinks}). Failover, resharding and
 * the failure detector are not built.
 */
public final class Controller implements WorldInput, Transport.Listener {

  private final ControllerEnv env;
  private final Violations violations = new Violations();
  private final NodeLinks links;
  private final WorldStreams streams;
  private final RequestTracker requests;

  /** Identifies this controller process to the fleet. */
  final long run;

  public Controller(
      ControllerEnv env, WorldSource world, WorldOutput out, RegionMap regions, int nodeCapacity) {
    this.env = Objects.requireNonNull(env, "env");
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(out, "out");
    Objects.requireNonNull(regions, "regions");
    this.run = env.run();
    Encoder encoder = new Encoder(env);
    this.links = new NodeLinks(env, run, regions.nodes());
    this.streams = new WorldStreams(env, world, regions, links, encoder, violations, run);
    this.requests =
        new RequestTracker(
            env, world, out, regions, links, streams, encoder, violations, run, nodeCapacity);
    env.transport().listen(this);
  }

  /** The classes the controller is made of, for checks that read compiled code. */
  public static final class Parts {

    private Parts() {}

    public static List<Class<?>> all() {
      return List.of(
          Controller.class,
          NodeLinks.class,
          WorldStreams.class,
          RequestTracker.class,
          Encoder.class,
          Violations.class);
    }
  }

  // Accessors for harness assertions. Everything below this point is behavior.

  public int requestsIssued() {
    return requests.requestsIssued();
  }

  /** Requests that still count against a node, abandoned ones included. */
  public int outstandingCount() {
    return requests.outstandingCount();
  }

  /** The world sequence the node reports once it has everything produced for its region. */
  public long heldSequence(NodeId node) {
    return streams.heldSequence(node);
  }

  /** Whether a holder at {@link #heldSequence} holds what the world holds now. */
  public boolean regionSettled(int regionId) {
    return streams.regionSettled(regionId);
  }

  /** Bookkeeping that must be consistent, and every violation the controller noticed itself. */
  public List<String> selfCheck() {
    List<String> found = new ArrayList<>(violations.list());
    links.check(found);
    streams.check(found);
    requests.check(found);
    return found;
  }

  @Override
  public void accept(WorldEvent event) {
    switch (event) {
      case WorldEvent.TickBegan began -> {
        requests.expire(began.tick());
        links.dial(began.tick());
      }
      case WorldEvent.TickEnded ended -> {
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
      // Shedding and the failover budget will read player positions. Nothing does yet.
      case WorldEvent.PlayerMoved ignored -> {}
      case WorldEvent.PlayerLeft ignored -> {}
    }
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
    requests.linkLost(node);
  }

  @Override
  public void onFrame(NodeId node, Channel channel, ByteBuffer frame) {
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
      case Reply.Answer answer -> requests.answer(node, answer);
      case Reply.Refusal refusal -> requests.refusal(node, refusal);
      case Reply.Greeting greeting -> {
        links.greeted(node, greeting.pathfinder());
        streams.hello(node, greeting);
      }
      case Reply.Depth depth -> {
        requests.depth(node, depth.queueDepth());
        // The only view of a node's backlog in production, and the arrivals a failure
        // detector reads.
        env.metrics().record("node.queue.reported", depth.queueDepth(), "node", node.toString());
      }
      case Reply.Foreign ignored -> env.metrics().count("path.response.foreign", 1);
      case null -> env.metrics().count("controller.unknown.frame", 1, "channel", channel.name());
    }
  }
}
