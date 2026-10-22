package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Modeled network. An ordered channel keeps frame order and cannot lose a frame without breaking
 * the connection. An unordered channel may drop, duplicate and reorder frames, which the
 * at-least-once request path must tolerate. Its frames are UDP datagrams, so one too large to send
 * is refused, one split into IP fragments is lost if any fragment is, and one in flight when the
 * link changes still arrives.
 *
 * <p>Process death resets the connection, so the controller sees it. A partition does not, so only
 * a failure detector can find it. A stall holds only the ordered channels, like TCP holding a
 * stream during a retransmit, so unordered frames can overtake the held ones. A paused process
 * keeps its connections but handles nothing, so what reaches it waits until it resumes.
 *
 * <p>Overlapping faults of one kind on one node are counted, so a fault ends only when every
 * interval of it has.
 *
 * <p>Each controller process gets its own {@link Session}. A restarted controller's links are gone
 * and its old session sends nothing, but datagrams still in flight reach the new process. The first
 * frame the node reads on each ordered connection is the one it joins the link with.
 */
public final class SimTransport {

  /** Which way a frame is travelling. Each direction of a channel is ordered separately. */
  private enum Direction {
    TO_NODE,
    TO_CONTROLLER
  }

  private record Link(NodeId node, Transport.Channel channel, Direction direction) {}

  /** A frame that reached a paused node, kept until it resumes. */
  private record Arrived(Transport.Channel channel, byte[] bytes, int sentEpoch) {}

  /** UDP payload that fits one Ethernet frame. A larger datagram travels as several fragments. */
  static final int FRAGMENT_BYTES = 1_472;

  private final Simulation sim;
  private final FaultPlan faults;
  private final Rng rng;
  private final Map<NodeId, ModeledNode> nodes;
  private final Set<NodeId> connected = new LinkedHashSet<>();
  private final Map<NodeId, Integer> dead = new LinkedHashMap<>();
  private final Map<NodeId, Integer> partitioned = new LinkedHashMap<>();
  private final Map<NodeId, Integer> stalled = new LinkedHashMap<>();
  private final Map<NodeId, Integer> paused = new LinkedHashMap<>();

  /** Counts each node's connect attempts and link changes. A frame is valid only in its own. */
  private final Map<NodeId, Integer> epoch = new LinkedHashMap<>();

  /**
   * Ordered links whose first frame, which should be a `Join`, the node has handled in this epoch.
   */
  private final Set<Link> opened = new LinkedHashSet<>();

  private final Map<Link, Long> lastDelivery = new LinkedHashMap<>();
  private final Map<Link, List<byte[]>> backlog = new LinkedHashMap<>();
  private final Map<NodeId, List<Arrived>> heldAtNode = new LinkedHashMap<>();

  private Session session;

  SimTransport(Simulation sim, FaultPlan faults, Rng rng, Map<NodeId, ModeledNode> nodes) {
    this.sim = sim;
    this.faults = faults;
    this.rng = rng;
    this.nodes = nodes;
  }

  /**
   * The transport of one controller process. Once a newer session starts, this one sends nothing
   * and hears nothing.
   */
  public final class Session implements Transport {

    private Listener listener;

    private Session() {}

    @Override
    public void listen(Listener listener) {
      if (this.listener != null) {
        throw new IllegalStateException("the transport already has a listener");
      }
      this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void send(NodeId node, Channel channel, ByteBuffer frame) {
      if (session == this) {
        transmit(node, channel, frame, Direction.TO_NODE);
      }
    }

    @Override
    public void connect(NodeId node) {
      if (listener == null) {
        throw new IllegalStateException("connect before listen");
      }
      if (!nodes.containsKey(node)) {
        throw new IllegalArgumentException(node + " is not in the fleet");
      }
      if (session != this) {
        return;
      }
      sim.metrics().count("transport.connect", 1, "node", node.toString());
      closeLink(node);
      int attempt = bumpEpoch(node);
      sim.schedule(
          latency(),
          "connect:" + node,
          () -> {
            if (session != this) {
              return;
            }
            if (epoch.get(node) != attempt || isDead(node) || count(partitioned, node) > 0) {
              listener.onDisconnected(node);
              return;
            }
            connected.add(node);
            listener.onConnected(node);
          });
    }

    /** Runs {@code callback} on the listener later, if this session is still the live one. */
    private void notify(String name, Callback callback) {
      sim.schedule(
          latency(),
          name,
          () -> {
            if (session == this && listener != null) {
              callback.accept(listener);
            }
          });
    }
  }

  private interface Callback {
    void accept(Transport.Listener listener);
  }

  /** Starts the transport of a new controller process. The old one's links are gone. */
  Session restart() {
    for (NodeId node : List.copyOf(connected)) {
      bumpEpoch(node);
      closeLink(node);
    }
    session = new Session();
    return session;
  }

  /** The host each modeled node acts through. */
  ModeledNode.Host hostFor(NodeId node) {
    return new ModeledNode.Host() {
      @Override
      public long now() {
        return sim.now();
      }

      @Override
      public void send(Transport.Channel channel, byte[] frame) {
        transmit(node, channel, ByteBuffer.wrap(frame), Direction.TO_CONTROLLER);
      }

      @Override
      public void linkDropped() {
        if (!connected.contains(node)) {
          return;
        }
        sim.metrics().count("transport.link.dropped", 1, "node", node.toString());
        bumpEpoch(node);
        dropLink(node);
        session.notify("link-dropped:" + node, listener -> listener.onDisconnected(node));
      }

      @Override
      public void refused(Transport.Channel port) {
        sim.metrics().count("transport.refused", 1, "node", node.toString());
      }

      @Override
      public void schedule(long delaySteps, String name, Runnable action) {
        sim.schedule(delaySteps, name, action);
      }

      @Override
      public void applied(ModeledNode modeled, long run, long region, long sequence) {
        sim.applied(modeled, run, region, sequence);
      }

      @Override
      public void assigned(ModeledNode modeled, long run, long region, long sequence) {
        sim.assigned(modeled, run, region, sequence);
      }
    };
  }

  /** Whether the node's process is running, whatever the state of its link. */
  public boolean alive(NodeId node) {
    return !isDead(node);
  }

  /** Process death: node state is lost and the controller sees a reset. */
  public void kill(NodeId node) {
    if (increment(dead, node) > 1) {
      return;
    }
    bumpEpoch(node);
    heldAtNode.remove(node);
    nodes.get(node).kill();
    if (dropLink(node)) {
      session.notify("reset:" + node, listener -> listener.onDisconnected(node));
    }
  }

  /** The node recovers once every death that overlapped has ended, as a new process. */
  public void revive(NodeId node) {
    if (release(dead, node)) {
      bumpEpoch(node);
      nodes.get(node).revive();
    }
  }

  /**
   * The connection breaks but the process lives, so the node keeps its world state and forgets what
   * belonged to the link.
   */
  public void resetLink(NodeId node) {
    bumpEpoch(node);
    if (connected.contains(node)) {
      sim.metrics().count("transport.link.reset", 1, "node", node.toString());
      closeLink(node);
      session.notify("link-reset:" + node, listener -> listener.onDisconnected(node));
    }
  }

  /** Ordered frames are held and replayed when the partition ends. Unordered ones are lost. */
  public void partition(NodeId node, boolean active) {
    hold(partitioned, node, active);
  }

  /** Ordered frames are held and replayed when the stall ends. Unordered ones still flow. */
  public void stall(NodeId node, boolean active) {
    hold(stalled, node, active);
  }

  /** The node's process freezes: its links stay up, and frames that reach it wait. */
  public void pause(NodeId node, boolean active) {
    if (active) {
      if (increment(paused, node) == 1) {
        nodes.get(node).pause(true);
      }
      return;
    }
    if (release(paused, node)) {
      ModeledNode modeled = nodes.get(node);
      List<Arrived> arrived = heldAtNode.remove(node);
      if (arrived != null) {
        for (Arrived frame : arrived) {
          handle(node, frame.channel(), frame.bytes(), frame.sentEpoch());
        }
      }
      if (!isDead(node)) {
        modeled.pause(false);
      }
    }
  }

  private void hold(Map<NodeId, Integer> holding, NodeId node, boolean active) {
    if (active) {
      increment(holding, node);
      return;
    }
    if (!release(holding, node) || count(partitioned, node) > 0 || count(stalled, node) > 0) {
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

  /** Ends the controller's view of the link, and the node's, without telling the controller. */
  private void closeLink(NodeId node) {
    if (dropLink(node)) {
      nodes.get(node).connectionLost();
    }
  }

  /** Forgets the link's held frames. Returns whether there was a link. */
  private boolean dropLink(NodeId node) {
    backlog.keySet().removeIf(link -> link.node().equals(node));
    opened.removeIf(link -> link.node().equals(node));
    return connected.remove(node);
  }

  private void transmit(
      NodeId node, Transport.Channel channel, ByteBuffer frame, Direction direction) {
    byte[] bytes = new byte[frame.remaining()];
    frame.duplicate().get(bytes);
    sim.digest().mix(bytes);
    String nodeLabel = node.toString();
    sim.metrics().count("transport.sent", 1, "node", nodeLabel, "channel", channel.name());

    // The receiving side would refuse it: a node drops a stream frame past its limit, and a
    // socket will not send a datagram past the protocol's.
    if (bytes.length > (channel.ordered() ? Frames.MAX_FRAME_BYTES : Frames.MAX_DATAGRAM_BYTES)) {
      sim.metrics().count("transport.oversize", 1, "node", nodeLabel, "channel", channel.name());
      return;
    }

    int sentEpoch = epoch.getOrDefault(node, 0);
    if (channel.ordered()) {
      if (!connected.contains(node) || isDead(node)) {
        sim.metrics().count("transport.lost", 1, "node", nodeLabel, "channel", channel.name());
        return;
      }
      Link link = new Link(node, channel, direction);
      boolean cut = count(partitioned, node) > 0;
      if (cut || count(stalled, node) > 0) {
        backlog.computeIfAbsent(link, held -> new ArrayList<>()).add(bytes);
        sim.metrics().count("transport.held", 1, "node", nodeLabel, "channel", channel.name());
        if (cut) {
          sim.metrics()
              .count("transport.held.partition", 1, "node", nodeLabel, "channel", channel.name());
        }
        return;
      }
      deliverInOrder(link, bytes, sentEpoch, "frame:" + channel);
      return;
    }

    if (isDead(node) || count(partitioned, node) > 0) {
      sim.metrics().count("transport.lost", 1, "node", nodeLabel, "channel", channel.name());
      return;
    }
    if (rng.nextDouble() < lossChance(bytes.length)) {
      sim.metrics().count("transport.dropped", 1, "node", nodeLabel, "channel", channel.name());
      return;
    }
    sim.schedule(
        latency(), "frame:" + channel, () -> arrive(node, channel, bytes, direction, sentEpoch));
    if (rng.nextDouble() < faults.duplicateRate()) {
      // An independent delay, so a duplicate can arrive before the original.
      sim.metrics().count("transport.duplicated", 1, "node", nodeLabel, "channel", channel.name());
      sim.schedule(
          latency(),
          "frame-dup:" + channel,
          () -> arrive(node, channel, bytes, direction, sentEpoch));
    }
  }

  /** Delays the arrival until after the last one on the same link, so order is preserved. */
  private void deliverInOrder(Link link, byte[] bytes, int sentEpoch, String name) {
    long time = Math.max(sim.now() + latency(), lastDelivery.getOrDefault(link, 0L) + 1);
    lastDelivery.put(link, time);
    sim.scheduleAt(
        time, name, () -> arrive(link.node(), link.channel(), bytes, link.direction(), sentEpoch));
  }

  private void arrive(
      NodeId node, Transport.Channel channel, byte[] bytes, Direction direction, int sentEpoch) {
    String label = node.toString();
    if (channel.ordered()
        && (epoch.getOrDefault(node, 0) != sentEpoch || !connected.contains(node))) {
      sim.metrics().count("transport.lost", 1, "node", label, "channel", channel.name());
      return;
    }
    if (direction == Direction.TO_CONTROLLER) {
      sim.metrics().count("transport.received", 1, "node", label, "channel", channel.name());
      if (session.listener != null) {
        session.listener.onFrame(node, channel, ByteBuffer.wrap(bytes));
      }
      return;
    }
    if (isDead(node)) {
      sim.metrics().count("transport.lost", 1, "node", label, "channel", channel.name());
      return;
    }
    if (count(paused, node) > 0) {
      heldAtNode
          .computeIfAbsent(node, held -> new ArrayList<>())
          .add(new Arrived(channel, bytes, sentEpoch));
      return;
    }
    handle(node, channel, bytes, sentEpoch);
  }

  /** The node reads a frame that reached it. The first on each connection is its `Join`. */
  private void handle(NodeId node, Transport.Channel channel, byte[] bytes, int sentEpoch) {
    String label = node.toString();
    if (channel.ordered()
        && (epoch.getOrDefault(node, 0) != sentEpoch || !connected.contains(node))) {
      sim.metrics().count("transport.lost", 1, "node", label, "channel", channel.name());
      return;
    }
    sim.metrics().count("transport.received", 1, "node", label, "channel", channel.name());
    ModeledNode modeled = nodes.get(node);
    if (channel.ordered() && opened.add(new Link(node, channel, Direction.TO_NODE))) {
      modeled.connect(channel, ByteBuffer.wrap(bytes));
    } else {
      modeled.receive(channel, ByteBuffer.wrap(bytes));
    }
  }

  private boolean isDead(NodeId node) {
    return count(dead, node) > 0;
  }

  private static int count(Map<NodeId, Integer> faults, NodeId node) {
    return faults.getOrDefault(node, 0);
  }

  private static int increment(Map<NodeId, Integer> faults, NodeId node) {
    return faults.merge(node, 1, Integer::sum);
  }

  /** Ends one interval of a fault, and returns whether that ended the fault. */
  private static boolean release(Map<NodeId, Integer> faults, NodeId node) {
    int before = count(faults, node);
    if (before == 0) {
      return false;
    }
    faults.put(node, before - 1);
    return before == 1;
  }

  private int bumpEpoch(NodeId node) {
    opened.removeIf(link -> link.node().equals(node));
    return epoch.merge(node, 1, Integer::sum);
  }

  /** Each fragment is lost at the plan's rate, and a datagram is lost with any of its fragments. */
  private double lossChance(int bytes) {
    int fragments = (bytes + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES;
    return fragments <= 1 ? faults.dropRate() : 1 - Math.pow(1 - faults.dropRate(), fragments);
  }

  private int latency() {
    int span = faults.maxLatencySteps() - faults.minLatencySteps() + 1;
    return faults.minLatencySteps() + rng.nextInt(span);
  }
}
