package io.github.davidmenggx.maestro.controller.env;

import java.nio.ByteBuffer;

/**
 * Byte-level link to the compute fleet. Frames are encoded FlatBuffers, so the simulation uses the
 * same serialization as the real fleet, and a captured frame can serve as a node-side fixture.
 */
public interface Transport {

  /**
   * Independent streams to one node. Frames are ordered within a channel, not across channels.
   * {@link #ordered} says whether a frame can be lost or reordered without breaking the connection.
   * On an ordered channel (TCP) it cannot, so a loss shows up as a disconnect. The unordered
   * channel is UDP, one frame per datagram.
   */
  enum Channel {
    /**
     * Region assignments, then chunk state and deltas: strictly ordered, sequence-numbered and
     * resumable.
     */
    WORLD(true),
    /**
     * Path requests: at-least-once, idempotent, may arrive out of order or twice. Datagrams are not
     * part of the link, so one in flight when the link changes still arrives.
     */
    REQUEST(false),
    /** Mob profiles to the node; {@code Hello} and heartbeats back. */
    CONTROL(true);

    private final boolean ordered;

    Channel(boolean ordered) {
      this.ordered = ordered;
    }

    public boolean ordered() {
      return ordered;
    }
  }

  /** Callbacks run on the controller thread, never inside a call into the transport. */
  interface Listener {

    /**
     * {@code arrivedNanos} is when the transport heard the frame, on the scale of {@link
     * Clock#nowNanos}. The callback may run much later, as after a server stall.
     */
    void onFrame(NodeId node, Channel channel, ByteBuffer frame, long arrivedNanos);

    void onConnected(NodeId node);

    /**
     * The link is gone (process death or reset), or a {@link #connect} attempt failed. Not the same
     * as an unresponsive node.
     */
    void onDisconnected(NodeId node);
  }

  /**
   * The transport may read the frame after this returns, so the caller must not change it. A send
   * to a node that is not connected is discarded, with no error return, because backpressure is
   * decided from the node's reported queue depth, not the socket.
   */
  void send(NodeId node, Channel channel, ByteBuffer frame);

  /**
   * Opens a link to the node, replacing any it has. The transport knows each node's addresses.
   * Every attempt ends in exactly one {@link Listener#onConnected} or {@link
   * Listener#onDisconnected}. The controller's first frame on each ordered channel of a new link is
   * its {@code Join}, which the transport carries like any other frame.
   */
  void connect(NodeId node);

  /** Installed once, before any {@link #connect}. */
  void listen(Listener listener);
}
