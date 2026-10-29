package io.github.davidmenggx.maestro.controller.wire;

import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import java.util.List;

/** A frame from a node, read in full before any of it is used. */
public sealed interface Reply {

  /**
   * A {@code PathResponse}. Positions are relative to the node's area. {@code waitMicros} is how
   * long the request waited for a worker, and {@code searchMicros} how long its search took, 0 if
   * it did not search.
   */
  record Answer(
      long requestId,
      boolean reached,
      List<WorldOutput.Node> nodes,
      float cost,
      long worldSequence,
      long waitMicros,
      long searchMicros)
      implements Reply {}

  /** An {@code UnknownProfile}. */
  record Refusal(long requestId, int classId) implements Reply {}

  /** A {@code Hello}. */
  record Greeting(long run, long regionId, long lastSequence, int pathfinder) implements Reply {}

  /**
   * A {@code Heartbeat}: the requests waiting or running, the searches {@code workers} threads
   * finished since the last one, which took {@code workerMicros} of worker time, and the region the
   * node holds for the link's run with its last applied world sequence, 0 if none.
   */
  record Beat(
      long queueDepth, long searches, long workerMicros, int workers, long regionId, long sequence)
      implements Reply {}

  /** A response meant for another controller run. */
  record Foreign() implements Reply {}
}
