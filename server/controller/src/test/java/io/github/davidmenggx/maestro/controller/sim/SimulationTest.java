package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulationTest {

  private static final long TICKS = 150;

  @Test
  void sameSeedReplaysIdentically() {
    // Run inside one JVM on purpose: identity hash codes differ between the two
    // runs, so an identity-ordered collection anywhere shows up as a mismatch.
    SimRun first = SimRun.of(7L, FaultPlan.reliable(), TICKS).go();
    SimRun second = SimRun.of(7L, FaultPlan.reliable(), TICKS).go();

    assertEquals(first.sim.digest().value(), second.sim.digest().value(), first.describe());
    assertEquals(first.out.results(), second.out.results());
    assertEquals(first.sim.metrics().counters(), second.sim.metrics().counters());
  }

  @Test
  void differentSeedsProduceDifferentRuns() {
    SimRun first = SimRun.of(7L, FaultPlan.reliable(), TICKS).go();
    SimRun second = SimRun.of(8L, FaultPlan.reliable(), TICKS).go();

    assertNotEquals(first.sim.digest().value(), second.sim.digest().value());
  }

  @Test
  void invariantsHoldOnAReliableNetwork() {
    for (int i = 0; i < SimRun.runCount(); i++) {
      SimRun run = SimRun.of(SimRun.baseSeed() + i, FaultPlan.reliable(), TICKS).go();
      assertEquals(List.of(), run.violations(), run.describe());
      assertTrue(run.out.answered() > 0, run.describe());
      assertEquals(0, run.out.dropped(DropReason.NODE_LOST), run.describe());
    }
  }

  @Test
  void invariantsHoldWhenRequestsAreLostAndDuplicated() {
    FaultPlan lossy = FaultPlan.reliable().withLoss(0.05, 0.05);
    long duplicatesSeen = 0;

    for (int i = 0; i < SimRun.runCount(); i++) {
      SimRun run = SimRun.of(SimRun.baseSeed() + i, lossy, TICKS).go();
      assertEquals(List.of(), run.violations(), run.describe());
      duplicatesSeen += run.sim.nodes().stream().mapToLong(ModeledNode::duplicateRequests).sum();

      // The ordered stream is TCP-shaped: nothing on it is silently lost.
      assertEquals(
          run.sim.metrics().counter("transport.sent", "node", "node-0", "channel", "WORLD"),
          run.sim.metrics().counter("transport.received", "node", "node-0", "channel", "WORLD"),
          run.describe());
    }

    assertTrue(duplicatesSeen > 0, "expected the request stream to duplicate at least once");
  }

  @Test
  void saturationIsRejectedBeforeSendingRatherThanMissedLater() {
    SimRun run = SimRun.saturated(SimRun.baseSeed(), FaultPlan.reliable(), TICKS).go();

    assertTrue(run.out.dropped(DropReason.QUEUE_DEPTH) > 0, run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
    // Whatever admission control let through was answered inside the bound it
    // promised. The 3-tick SLO is a different, tighter statement and a saturated
    // fleet does not hold it, which is what the shedding policy is for.
    assertEquals(List.of(), run.violations(ProbeController.MAX_TICKS_LATE), run.describe());
  }

  @Test
  void nodeDeathDropsItsRequestsAndTheRunStaysDeterministic() {
    NodeId victim = NodeId.of(0);
    FaultPlan plan =
        FaultPlan.reliable()
            .with(new FaultPlan.NodeDied(40, victim), new FaultPlan.NodeRecovered(45, victim));

    SimRun first = SimRun.of(11L, plan, TICKS).go();
    SimRun second = SimRun.of(11L, plan, TICKS).go();

    assertEquals(first.sim.digest().value(), second.sim.digest().value(), first.describe());
    assertEquals(List.of(), first.violations(), first.describe());
    assertTrue(
        first.out.dropped(DropReason.NODE_LOST) + first.out.dropped(DropReason.NO_NODE) > 0,
        first.describe());
  }

  @Test
  void aPartitionedNodeIsSilentAndItsRequestsTimeOut() {
    NodeId victim = NodeId.of(1);
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Partitioned(40, victim, true),
                new FaultPlan.Partitioned(80, victim, false));

    SimRun run = SimRun.of(13L, plan, TICKS).go();

    assertEquals(List.of(), run.violations(), run.describe());
    // No reset, so nothing tells the controller the node is gone. Only the
    // request timeout does, which is exactly the gap a failure detector fills.
    assertEquals(0, run.out.dropped(DropReason.NODE_LOST), run.describe());
    assertTrue(run.out.dropped(DropReason.SHED) > 0, run.describe());
  }
}
