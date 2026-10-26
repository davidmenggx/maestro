package io.github.davidmenggx.maestro.controller.world;

import java.util.List;
import java.util.Objects;

/** What the controller returns to the server, on the main thread. One call per request. */
public interface WorldOutput {

  /**
   * Why a request will not produce a computed path. The server then searches itself, unless the
   * request was shed.
   */
  enum DropReason {
    /**
     * Refused before sending: every ready holder holds more ahead of it than the request's share of
     * its queue allows, a share that shrinks with distance from the nearest player and for a mob
     * not hunting. The mob keeps its path or idles.
     */
    SHED,
    /**
     * No node can serve it: it starts outside every region, its search could read past its region's
     * area, or a position does not fit the wire. Also an answer searched before the start chunk
     * reached the node.
     */
    NO_NODE,
    /**
     * No holder of the region is linked, in service and holding the start chunk, as while a node is
     * down or its region is resent.
     */
    UNAVAILABLE,
    /**
     * The node did not hold this profile yet. It is registered with the send; a request that
     * overtakes it is answered as unknown.
     */
    CLASS_UNREGISTERED,
    /**
     * The owning node was lost before answering: its link went, it was taken out of service, or it
     * stopped holding the region.
     */
    NODE_LOST,
    /** No answer arrived in time, resends included. */
    TIMEOUT,
    /** The mob was removed while the request was in flight. */
    MOB_GONE,
    /** The mob withdrew the request before it was answered. */
    CANCELED,
    /** The mob sent a new request before this one was answered, so this one was abandoned. */
    SUPERSEDED
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
   * The arguments of {@code Path(nodes, target, reached)}, plus the tick the request was issued.
   * Empty {@code nodes} means no path.
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
