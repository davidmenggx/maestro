package io.github.davidmenggx.maestro.controller.wire;

import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import java.util.List;

/** A frame from a node, read in full before any of it is used. */
public sealed interface Reply {

  /** A {@code PathResponse}. Positions are relative to the node's area. */
  record Answer(
      long requestId, boolean reached, List<WorldOutput.Node> nodes, float cost, long worldSequence)
      implements Reply {}

  /** An {@code UnknownProfile}. */
  record Refusal(long requestId, int classId) implements Reply {}

  /** A {@code Hello}. */
  record Greeting(long run, long regionId, long lastSequence, int pathfinder) implements Reply {}

  /**
   * A {@code Heartbeat}: the requests waiting or running, and the searches {@code workers} threads
   * finished since the last one, which took {@code searchMicros} of worker time.
   */
  record Beat(long queueDepth, long searches, long searchMicros, int workers) implements Reply {}

  /** A response meant for another controller run. */
  record Foreign() implements Reply {}
}
