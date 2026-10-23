package io.github.davidmenggx.maestro.controller.env;

import java.nio.ByteBuffer;

/** Byte-level link to the compute fleet. Frames are encoded FlatBuffers. */
public interface Transport {

  /**
   * Independent streams to one node, ordered within a channel only. On an ordered channel (TCP) a
   * loss shows up as a disconnect. The unordered channel is UDP, one frame per datagram.
   */
  enum Channel {
    /**
     * Region assignments, then chunk state and deltas: strictly ordered, sequence-numbered and
     * resumable.
     */
    WORLD(true),
    /**
     * Path requests: at-least-once, may arrive out of order or twice. A datagram in flight when the
     * link changes still arrives.
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
     * {@code arrivedNanos} is when the transport heard the frame, on the {@link Clock#nowNanos}
     * scale. The callback may run much later.
     */
    void onFrame(NodeId node, Channel channel, ByteBuffer frame, long arrivedNanos);

    void onConnected(NodeId node);

    /**
     * The link is gone, or a {@link #connect} attempt failed. Not the same as an unresponsive node.
     */
    void onDisconnected(NodeId node);
  }

  /**
   * The caller must not change the frame after this returns. A send to a node that is not connected
   * is discarded silently; backpressure comes from the node's reported queue depth.
   */
  void send(NodeId node, Channel channel, ByteBuffer frame);

  /**
   * Opens a link to the node, replacing any it has. Every attempt ends in exactly one {@link
   * Listener#onConnected} or {@link Listener#onDisconnected}.
   */
  void connect(NodeId node);

  /** Installed once, before any {@link #connect}. */
  void listen(Listener listener);
}
