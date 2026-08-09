package com.smpler.maestro.controller.world;

import java.util.List;

/**
 * What the controller hands back to the server, on the main thread. Exactly one call per request:
 * either a path or a drop, never both and never neither.
 */
public interface WorldOutput {

  /** Why a request will never produce a computed path. The server then falls back on its own. */
  enum DropReason {
    /** Rejected before sending: the owning node would answer past the tick budget. */
    QUEUE_DEPTH,
    /** No node owns the region, usually mid-handoff. */
    NO_NODE,
    /** The owner died before answering. */
    NODE_LOST,
    /** The mob was removed while the request was in flight. */
    MOB_GONE,
    /** The mob asked again before this one was answered, so the older request was abandoned. */
    SUPERSEDED,
    /** Dropped to hold the latency SLO under a saturated fleet. */
    SHED
  }

  /** {@code nodes} is the Minecraft path, one block position per node; empty means no path. */
  record PathResult(
      int mobId, boolean reached, List<Pos> nodes, float cost, long computedTick, long issuedTick) {

    public PathResult {
      nodes = List.copyOf(nodes);
    }
  }

  void pathComputed(PathResult result);

  void pathDropped(int mobId, DropReason reason);
}
