package io.github.davidmenggx.maestro.controller.world;

import java.util.List;
import java.util.Objects;

/**
 * What the controller returns to the server, on the main thread. Exactly one call per request:
 * either a path or a drop.
 */
public interface WorldOutput {

  /** Why a request will not produce a computed path. The server then computes its own. */
  enum DropReason {
    /** Rejected before sending because the owning node would answer past the tick budget. */
    QUEUE_DEPTH,
    /** No node owns the region. */
    NO_NODE,
    /**
     * The owning node did not have this mob profile yet. It is registered as part of the send, and
     * if the request still arrives before the registration, the node answers that it is unknown.
     */
    CLASS_UNREGISTERED,
    /** The owning node died before answering. */
    NODE_LOST,
    /** The mob was removed while the request was in flight. */
    MOB_GONE,
    /** The mob withdrew the request before it was answered. */
    CANCELED,
    /** The mob sent a new request before this one was answered, so this one was abandoned. */
    SUPERSEDED,
    /** Dropped to meet the latency SLO when the fleet is saturated. */
    SHED
  }

  /** One node of the path, with the resolved type the navigation reads when following it. */
  record Node(Pos pos, byte pathType) {
    public Node {
      Objects.requireNonNull(pos, "pos");
      if (pathType < 0 || pathType >= PathTypes.count()) {
        throw new IllegalArgumentException("unknown path type " + pathType);
      }
    }
  }

  /**
   * The arguments of {@code Path(nodes, target, reached)}, plus the tick the request was issued, so
   * the server knows how old the answer is. Empty {@code nodes} means no path.
   */
  record PathResult(
      int mobId, Pos target, boolean reached, List<Node> nodes, float cost, long issuedTick) {

    public PathResult {
      Objects.requireNonNull(target, "target");
      nodes = List.copyOf(nodes);
    }
  }

  void pathComputed(PathResult result);

  void pathDropped(int mobId, DropReason reason);
}
