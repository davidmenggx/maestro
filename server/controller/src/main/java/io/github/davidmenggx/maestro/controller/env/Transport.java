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

    void onFrame(NodeId node, Channel channel, ByteBuffer frame);

    void onConnected(NodeId node);

    /**
     * The link is gone (process death or reset), or a {@link #connect} attempt failed. Not the same
     * as an unresponsive node, and not called for a link the controller closed itself.
     */
    void onDisconnected(NodeId node);
  }

  /**
   * Frames are copied or fully consumed before returning, so the caller may reuse the buffer. A
   * send to a node that is not connected is discarded, with no error return, because backpressure
   * is decided from the node's reported queue depth, not the socket.
   */
  void send(NodeId node, Channel channel, ByteBuffer frame);

  /**
   * Opens a link to the node, replacing any it has. The transport knows each node's addresses, and
   * opens both ordered channels with a {@code Join} naming the controller's run and this attempt.
   * Every attempt ends in exactly one {@link Listener#onConnected} or {@link
   * Listener#onDisconnected}.
   */
  void connect(NodeId node);

  /**
   * Closes the node's link, so nothing more is sent or received on it, as when the controller
   * fences a node it gave up on. No callback reports it, and an attempt still pending ends in
   * {@link Listener#onDisconnected}.
   */
  void disconnect(NodeId node);

  /** Installed once, before any {@link #connect}. */
  void listen(Listener listener);
}
