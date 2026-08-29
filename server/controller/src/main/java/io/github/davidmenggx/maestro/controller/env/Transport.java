package io.github.davidmenggx.maestro.controller.env;

import java.nio.ByteBuffer;

/**
 * Byte-level link to the compute fleet. Frames are encoded FlatBuffers, so simulation exercises the
 * same serialization the real fleet sees and a captured frame doubles as a node-side fixture.
 */
public interface Transport {

  /**
   * Independent streams to one node. Ordering holds within a channel, never across them. {@link
   * #ordered} says whether the channel may lose or reorder a frame without also breaking the
   * connection: an ordered channel is TCP-shaped, so a loss shows up as a disconnect instead.
   */
  enum Channel {
    /** World deltas and snapshots: strictly ordered, sequence numbered, resumable. */
    WORLD(true),
    /** Path requests: at-least-once, idempotent, may arrive out of order or twice. */
    REQUEST(false),
    /** Assignments and heartbeats. */
    CONTROL(true);

    private final boolean ordered;

    Channel(boolean ordered) {
      this.ordered = ordered;
    }

    public boolean ordered() {
      return ordered;
    }
  }

  /** Callbacks land on the controller thread, never inside a {@link #send} call. */
  interface Listener {

    void onFrame(NodeId node, Channel channel, ByteBuffer frame);

    void onConnected(NodeId node);

    /** The link is gone (process death or a reset). Separate from unresponsive node. */
    void onDisconnected(NodeId node);
  }

  /**
   * Frames are copied or fully consumed before returning, so the caller may reuse the buffer. A
   * send to a node that is not connected is discarded; there is no error return, because
   * backpressure is decided from reported queue depth rather than from the socket.
   */
  void send(NodeId node, Channel channel, ByteBuffer frame);

  void connect(NodeId node, String endpoint);

  void disconnect(NodeId node);

  /** Installed once, before any {@link #connect}. */
  void listen(Listener listener);
}
