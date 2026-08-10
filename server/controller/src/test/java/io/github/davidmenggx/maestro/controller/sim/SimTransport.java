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
import java.util.Set;

/**
 * Modeled network. Ordered channels behave like TCP: frames keep their order and are never silently
 * lost, but the connection can break. Unordered channels may drop, duplicate and reorder, which is
 * the traffic the at-least-once request path has to survive.
 *
 * <p>Process death resets the connection, so the controller hears about it. A partition is silent
 * by design: only the failure detector can notice one.
 */
public final class SimTransport implements Transport {

  private record Link(NodeId node, Channel channel, boolean toNode) {}

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
    this.listener = listener;
  }

  @Override
  public void connect(NodeId node, String endpoint) {
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
        });
  }

  @Override
  public void disconnect(NodeId node) {
    connected.remove(node);
    bumpEpoch(node);
  }

  @Override
  public void send(NodeId node, Channel channel, ByteBuffer frame) {
    transmit(node, channel, frame, true);
  }

  /** The node side of the same wire. */
  void toController(NodeId node, Channel channel, ByteBuffer frame) {
    transmit(node, channel, frame, false);
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

  public void revive(NodeId node) {
    dead.remove(node);
    bumpEpoch(node);
  }

  /**
   * Silent black hole. Ordered channels sit on TCP, so their frames are held and released in order
   * once the partition lifts rather than lost; unordered frames are simply gone.
   */
  public void partition(NodeId node, boolean on) {
    if (on) {
      partitioned.add(node);
      return;
    }
    if (!partitioned.remove(node)) {
      return;
    }
    int sentEpoch = epoch.getOrDefault(node, 0);
    for (Map.Entry<Link, List<byte[]>> held : backlog.entrySet()) {
      Link link = held.getKey();
      if (!link.node().equals(node)) {
        continue;
      }
      for (byte[] bytes : held.getValue()) {
        long time = Math.max(sim.now() + latency(), lastDelivery.getOrDefault(link, 0L) + 1);
        lastDelivery.put(link, time);
        sim.scheduleAt(
            time,
            "backlog:" + link.channel(),
            () -> arrive(node, link.channel(), bytes, link.toNode(), sentEpoch));
      }
      held.getValue().clear();
    }
  }

  private void transmit(NodeId node, Channel channel, ByteBuffer frame, boolean toNode) {
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
        backlog.computeIfAbsent(new Link(node, channel, toNode), k -> new ArrayList<>()).add(bytes);
        sim.metrics().count("transport.held", 1, "node", nodeLabel, "channel", channel.name());
      } else {
        sim.metrics().count("transport.lost", 1, "node", nodeLabel, "channel", channel.name());
      }
      return;
    }

    if (channel.ordered()) {
      Link link = new Link(node, channel, toNode);
      long time = Math.max(sim.now() + latency(), lastDelivery.getOrDefault(link, 0L) + 1);
      lastDelivery.put(link, time);
      sim.scheduleAt(
          time, "frame:" + channel, () -> arrive(node, channel, bytes, toNode, sentEpoch));
      return;
    }

    if (rng.nextDouble() < faults.dropRate()) {
      sim.metrics().count("transport.dropped", 1, "node", nodeLabel, "channel", channel.name());
      return;
    }
    sim.schedule(
        latency(), "frame:" + channel, () -> arrive(node, channel, bytes, toNode, sentEpoch));
    if (rng.nextDouble() < faults.duplicateRate()) {
      // An independent delay, so a duplicate can also land before the original.
      sim.metrics().count("transport.duplicated", 1, "node", nodeLabel, "channel", channel.name());
      sim.schedule(
          latency(), "frame-dup:" + channel, () -> arrive(node, channel, bytes, toNode, sentEpoch));
    }
  }

  private void arrive(NodeId node, Channel channel, byte[] bytes, boolean toNode, int sentEpoch) {
    if (epoch.getOrDefault(node, 0) != sentEpoch || !linkUp(node)) {
      sim.metrics().count("transport.lost", 1, "node", node.toString(), "channel", channel.name());
      return;
    }
    sim.metrics()
        .count("transport.received", 1, "node", node.toString(), "channel", channel.name());
    if (toNode) {
      nodes.get(node).receive(channel, ByteBuffer.wrap(bytes));
    } else {
      listener.onFrame(node, channel, ByteBuffer.wrap(bytes));
    }
  }

  /** A partition is handled separately: it holds or discards frames instead of failing the link. */
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
