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
    /** Path requests: at-least-once, idempotent, may arrive out of order or twice. */
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

  /** Callbacks run on the controller thread, never inside a {@link #send} call. */
  interface Listener {

    void onFrame(NodeId node, Channel channel, ByteBuffer frame);

    void onConnected(NodeId node);

    /** The link is gone (process death or reset). Not the same as an unresponsive node. */
    void onDisconnected(NodeId node);
  }

  /**
   * Frames are copied or fully consumed before returning, so the caller may reuse the buffer. A
   * send to a node that is not connected is discarded, with no error return, because backpressure
   * is decided from the node's reported queue depth, not the socket.
   */
  void send(NodeId node, Channel channel, ByteBuffer frame);

  void connect(NodeId node, String endpoint);

  /** Installed once, before any {@link #connect}. */
  void listen(Listener listener);
}
