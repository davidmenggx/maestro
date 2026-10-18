package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Each node's link: dialing it, the {@code Join} and {@code Hello} handshake, and the profiles sent
 * on it. Every frame to a node leaves through {@link #send}.
 */
final class NodeLinks {

  /** How often a disconnected node is dialed again. */
  static final int RECONNECT_TICKS = 20;

  private static final class Link {

    private boolean connected;

    /** A {@code connect} is pending, so another must not start. */
    private boolean connecting;

    /**
     * Counts the links opened to the node. It names the link in its {@code Join}, and a request
     * batch built for one link is never sent on a later one.
     */
    private long attempt;

    /** Past the {@code Hello} handshake, so world frames may flow. */
    private boolean greeted;

    /** Search the node runs, reported in its {@code Hello}. */
    private int pathfinder;

    /** Profiles this node has been sent on the current link. */
    private final Set<Short> registeredClasses = new LinkedHashSet<>();
  }

  private final ControllerEnv env;
  private final long run;

  /** In fleet order, so iterating always has the same order, whatever order nodes connected in. */
  private final Map<NodeId, Link> links = new LinkedHashMap<>();

  NodeLinks(ControllerEnv env, long run, List<NodeId> fleet) {
    this.env = env;
    this.run = run;
    for (NodeId node : fleet) {
      links.put(node, new Link());
    }
  }

  boolean inFleet(NodeId node) {
    return links.containsKey(node);
  }

  /** Dials every node with no link and no attempt pending, every {@link #RECONNECT_TICKS}. */
  void dial(long tick) {
    if (tick % RECONNECT_TICKS != 0) {
      return;
    }
    for (Map.Entry<NodeId, Link> entry : links.entrySet()) {
      Link link = entry.getValue();
      if (!link.connected && !link.connecting) {
        link.connecting = true;
        env.transport().connect(entry.getKey());
      }
    }
  }

  /**
   * A new link is up. Its first frames name it, and nothing else is sent until the node reports how
   * far its world stream got.
   */
  void connected(NodeId node) {
    Link link = link(node);
    link.connecting = false;
    link.connected = true;
    link.greeted = false;
    link.attempt++;
    link.registeredClasses.clear();
    send(node, Channel.WORLD, Frames.joinWorld(run, link.attempt));
    send(node, Channel.CONTROL, Frames.joinControl(run, link.attempt));
  }

  void disconnected(NodeId node) {
    Link link = link(node);
    link.connecting = false;
    link.connected = false;
    link.greeted = false;
    link.registeredClasses.clear();
  }

  void greeted(NodeId node, int pathfinder) {
    Link link = link(node);
    link.greeted = true;
    link.pathfinder = pathfinder;
  }

  boolean greeted(NodeId node) {
    return link(node).greeted;
  }

  long attempt(NodeId node) {
    return link(node).attempt;
  }

  /** Records that the profile is sent on the node's link. Returns false if it already was. */
  boolean register(NodeId node, short classId) {
    return link(node).registeredClasses.add(classId);
  }

  void unregister(NodeId node, short classId) {
    link(node).registeredClasses.remove(classId);
  }

  boolean registered(NodeId node, short classId) {
    return link(node).registeredClasses.contains(classId);
  }

  void send(NodeId node, Channel channel, byte[] frame) {
    env.metrics().record("frame.bytes", frame.length, "channel", channel.name());
    env.transport().send(node, channel, ByteBuffer.wrap(frame));
  }

  /** Links that contradict themselves, and a fleet that runs more than one search. */
  void check(List<String> violations) {
    int fleetPathfinder = -1;
    for (Map.Entry<NodeId, Link> entry : links.entrySet()) {
      NodeId node = entry.getKey();
      Link link = entry.getValue();
      if (link.greeted && !link.connected) {
        violations.add(node + " is greeted but not connected");
      }
      if (link.connected && link.connecting) {
        violations.add(node + " is connected with a connect still pending");
      }
      if (!link.greeted) {
        continue;
      }
      // One search across the fleet, so every answer in a comparison run is attributed to it.
      if (fleetPathfinder < 0) {
        fleetPathfinder = link.pathfinder;
      } else if (link.pathfinder != fleetPathfinder) {
        violations.add(
            node
                + " runs pathfinder "
                + link.pathfinder
                + " but the fleet runs "
                + fleetPathfinder);
      }
    }
  }

  /**
   * A node of the fleet. Frames and callbacks from any other are dropped before this is reached.
   */
  private Link link(NodeId node) {
    Link link = links.get(node);
    if (link == null) {
      throw new IllegalStateException(node + " is not in the fleet");
    }
    return link;
  }
}
