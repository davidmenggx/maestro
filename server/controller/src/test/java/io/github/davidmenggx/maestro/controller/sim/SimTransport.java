package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.env.Transport;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Modeled network. An ordered channel keeps frame order and loses nothing without also breaking the
 * connection; an unordered one may drop, duplicate and reorder. The at-least-once request path has
 * to survive that.
 *
 * <p>Process death resets the connection, so the controller sees it happen. A partition does not,
 * so only a failure detector can find one.
 */
public final class SimTransport implements Transport {

  /** Which way a frame is travelling. Each direction of a channel orders itself. */
  private enum Direction {
    TO_NODE,
    TO_CONTROLLER
  }

  private record Link(NodeId node, Channel channel, Direction direction) {}

  private final Simulation sim;
  private final FaultPlan faults;
  private final Rng rng;
  private final Map<NodeId, ModeledNode> nodes;
  private final Set<NodeId> connected = new LinkedHashSet<>();
  private final Set<NodeId> dead = new LinkedHashSet<>();
  private final Set<NodeId> partitioned = new LinkedHashSet<>();
  private final Map<NodeId, Integer> epoch = new LinkedHashMap<>();
  private final Map<Link, Long> lastDelivery = new LinkedHashMap<>();
  private final Map<Link, List<byte[]>> backlog = new LinkedHashMap<>();

  private Listener listener;

  SimTransport(Simulation sim, FaultPlan faults, Rng rng, Map<NodeId, ModeledNode> nodes) {
    this.sim = sim;
    this.faults = faults;
    this.rng = rng;
    this.nodes = nodes;
  }

  @Override
  public void listen(Listener listener) {
    if (this.listener != null) {
      throw new IllegalStateException("the transport already has a listener");
    }
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  @Override
  public void connect(NodeId node, String endpoint) {
    if (listener == null) {
      throw new IllegalStateException("connect before listen");
    }
    if (!nodes.containsKey(node)) {
      throw new IllegalArgumentException(node + " is not in the fleet");
    }
    sim.metrics().count("transport.connect", 1, "node", node.toString());
    int attempt = bumpEpoch(node);
    sim.schedule(
        latency(),
        "connect:" + node,
        () -> {
          if (epoch.get(node) != attempt) {
            return;
          }
          if (dead.contains(node) || partitioned.contains(node)) {
            listener.onDisconnected(node);
            return;
          }
          connected.add(node);
          listener.onConnected(node);
          nodes.get(node).linkUp();
        });
  }

  @Override
  public void disconnect(NodeId node) {
    connected.remove(node);
    bumpEpoch(node);
  }

  @Override
  public void send(NodeId node, Channel channel, ByteBuffer frame) {
    transmit(node, channel, frame, Direction.TO_NODE);
  }

  /** The node side of the same wire. */
  void toController(NodeId node, Channel channel, ByteBuffer frame) {
    transmit(node, channel, frame, Direction.TO_CONTROLLER);
  }

  /** Process death: node state is lost and the controller sees a reset. */
  public void kill(NodeId node) {
    if (!dead.add(node)) {
      return;
    }
    bumpEpoch(node);
    backlog.keySet().removeIf(link -> link.node().equals(node));
    ModeledNode modeled = nodes.get(node);
    if (modeled != null) {
      modeled.kill();
    }
    if (connected.remove(node)) {
      sim.schedule(latency(), "reset:" + node, () -> listener.onDisconnected(node));
    }
  }

  /** The connection breaks but the process lives, so the node keeps its state. */
  public void resetLink(NodeId node) {
    bumpEpoch(node);
    backlog.keySet().removeIf(link -> link.node().equals(node));
    if (connected.remove(node)) {
      sim.metrics().count("transport.link.reset", 1, "node", node.toString());
      sim.schedule(latency(), "link-reset:" + node, () -> listener.onDisconnected(node));
    }
  }

  /**
   * Only a node that died recovers. Bumping the epoch of a live connection would discard the frames
   * already in flight on it, which is a loss the ordered channel is not allowed to have.
   */
  public void revive(NodeId node) {
    if (dead.remove(node)) {
      bumpEpoch(node);
    }
  }

  /** Ordered frames are held and replayed when the partition lifts; unordered ones are lost. */
  public void partition(NodeId node, boolean active) {
    if (active) {
      partitioned.add(node);
      return;
    }
    if (!partitioned.remove(node)) {
      return;
    }
    int sentEpoch = epoch.getOrDefault(node, 0);
    for (Map.Entry<Link, List<byte[]>> entry : backlog.entrySet()) {
      Link link = entry.getKey();
      if (!link.node().equals(node)) {
        continue;
      }
      for (byte[] bytes : entry.getValue()) {
        deliverInOrder(link, bytes, sentEpoch, "backlog:" + link.channel());
      }
      entry.getValue().clear();
    }
  }

  private void transmit(NodeId node, Channel channel, ByteBuffer frame, Direction direction) {
    byte[] bytes = new byte[frame.remaining()];
    frame.duplicate().get(bytes);
    sim.digest().mix(bytes);
    String nodeLabel = node.toString();
    sim.metrics().count("transport.sent", 1, "node", nodeLabel, "channel", channel.name());

    if (!linkUp(node)) {
      sim.metrics().count("transport.lost", 1, "node", nodeLabel, "channel", channel.name());
      return;
    }

    int sentEpoch = epoch.getOrDefault(node, 0);
    if (partitioned.contains(node)) {
      if (channel.ordered()) {
        backlog
            .computeIfAbsent(new Link(node, channel, direction), link -> new ArrayList<>())
            .add(bytes);
        sim.metrics().count("transport.held", 1, "node", nodeLabel, "channel", channel.name());
      } else {
        sim.metrics().count("transport.lost", 1, "node", nodeLabel, "channel", channel.name());
      }
      return;
    }

    if (channel.ordered()) {
      deliverInOrder(new Link(node, channel, direction), bytes, sentEpoch, "frame:" + channel);
      return;
    }

    if (rng.nextDouble() < faults.dropRate()) {
      sim.metrics().count("transport.dropped", 1, "node", nodeLabel, "channel", channel.name());
      return;
    }
    sim.schedule(
        latency(), "frame:" + channel, () -> arrive(node, channel, bytes, direction, sentEpoch));
    if (rng.nextDouble() < faults.duplicateRate()) {
      // An independent delay, so a duplicate can also land before the original.
      sim.metrics().count("transport.duplicated", 1, "node", nodeLabel, "channel", channel.name());
      sim.schedule(
          latency(),
          "frame-dup:" + channel,
          () -> arrive(node, channel, bytes, direction, sentEpoch));
    }
  }

  /** Holds the arrival back behind the last one on the same link, so order cannot invert. */
  private void deliverInOrder(Link link, byte[] bytes, int sentEpoch, String name) {
    long time = Math.max(sim.now() + latency(), lastDelivery.getOrDefault(link, 0L) + 1);
    lastDelivery.put(link, time);
    sim.scheduleAt(
        time, name, () -> arrive(link.node(), link.channel(), bytes, link.direction(), sentEpoch));
  }

  private void arrive(
      NodeId node, Channel channel, byte[] bytes, Direction direction, int sentEpoch) {
    if (epoch.getOrDefault(node, 0) != sentEpoch || !linkUp(node)) {
      sim.metrics().count("transport.lost", 1, "node", node.toString(), "channel", channel.name());
      return;
    }
    sim.metrics()
        .count("transport.received", 1, "node", node.toString(), "channel", channel.name());
    if (direction == Direction.TO_NODE) {
      nodes.get(node).receive(channel, ByteBuffer.wrap(bytes));
    } else {
      listener.onFrame(node, channel, ByteBuffer.wrap(bytes));
    }
  }

  /** A partition is not a link failure: it holds or discards frames while the link stays up. */
  private boolean linkUp(NodeId node) {
    return connected.contains(node) && !dead.contains(node);
  }

  private int bumpEpoch(NodeId node) {
    return epoch.merge(node, 1, Integer::sum);
  }

  private int latency() {
    int span = faults.maxLatencySteps() - faults.minLatencySteps() + 1;
    return faults.minLatencySteps() + rng.nextInt(span);
  }
}
