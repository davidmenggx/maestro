package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.core.NodeHealth;
import io.github.davidmenggx.maestro.controller.core.RequestTracker;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import org.junit.jupiter.api.Test;

/** Checks harness constants against each other; drift silently stops a sweep reaching its case. */
class HarnessConstantsTest {

  @Test
  void anEdgeLatencyLeavesNoSlackForOneRequest() {
    // At the edge, a top-cost request is answered on the last step read; one more is late.
    for (int cost : new int[] {1, 125, 500, 997}) {
      int edge = FaultPlan.edgeLatency(cost);
      assertTrue(2 * edge + cost <= Simulation.STEPS_PER_TICK - 1, "cost " + cost);
      assertTrue(2 * (edge + 1) + cost > Simulation.STEPS_PER_TICK - 1, "cost " + cost);
    }
    assertEquals(0, FaultPlan.edgeLatency(Simulation.STEPS_PER_TICK));
    assertTrue(
        FaultPlan.EDGE_SPAN_STEPS < FaultPlan.edgeLatency(Scenario.DEFAULT.maxCostSteps(1)),
        "the default fleet has no edge plan");
  }

  @Test
  void aTickIsTheSameLengthInStepsAsInNanoseconds() {
    assertEquals(Simulation.NANOS_PER_TICK, Simulation.NANOS_PER_STEP * Simulation.STEPS_PER_TICK);
  }

  @Test
  void theShortestFuzzedRunHasRoomForAFault() {
    // A fault starts inside the active window and, unless the node is lost
    // permanently, ends there too.
    assertTrue(
        SimRun.MIN_FUZZED_TICKS - SimRun.DRAIN_TICKS > FaultPlan.MAX_FAULT_SPAN_TICKS,
        "the shortest fuzzed run has no room for a fault");
    assertTrue(SimRun.MAX_FUZZED_TICKS >= SimRun.MIN_FUZZED_TICKS, "run length range is inverted");
  }

  @Test
  void aLongFaultOutlastsBothWaysOfNoticingIt() {
    // A node whose heartbeats stop is suspected within this many ticks, judged against the
    // interval the detector assumes before it has seen any.
    double mean = NodeHealth.FIRST_INTERVAL_NANOS + NodeHealth.ACCEPTABLE_PAUSE_NANOS;
    double deviation =
        Math.max(NodeHealth.FIRST_INTERVAL_NANOS / 4, NodeHealth.MIN_DEVIATION_NANOS);
    long suspectedAfter = 0;
    while (NodeHealth.phi(suspectedAfter * Simulation.NANOS_PER_TICK, mean, deviation)
        < NodeHealth.PHI_THRESHOLD) {
      suspectedAfter++;
    }
    assertTrue(
        FaultPlan.MAX_LONG_FAULT_SPAN_TICKS > 2 * suspectedAfter,
        "a long fault ends before its node can be suspected: " + suspectedAfter + " ticks");
    // A quiet request path is noticed, waits out its backoff, and fails a probe.
    assertTrue(
        FaultPlan.MAX_LONG_FAULT_SPAN_TICKS
            > NodeHealth.QUIET_TICKS + NodeHealth.FIRST_BACKOFF_TICKS + NodeHealth.PROBE_TICKS + 1,
        "a long fault ends before a probe can fail");
    assertTrue(FaultPlan.MAX_LONG_FAULT_SPAN_TICKS > FaultPlan.MAX_FAULT_SPAN_TICKS);
  }

  @Test
  void theQuietWindowKeepsARequestPastItsBudget() {
    // The oldest request must still count when it has gone unanswered long enough.
    assertTrue(NodeHealth.QUIET_WINDOW_TICKS > NodeHealth.QUIET_TICKS);
    // A probe is judged after its batch leaves and the tick budget passes.
    assertTrue(NodeHealth.PROBE_TICKS >= RequestTracker.SEND_TICKS + RequestTracker.MAX_TICKS_LATE);
    // A later probe is never allowed to leave before the one before it.
    assertTrue(
        NodeHealth.PROBE_TICKS + 1 + 2 * NodeHealth.FIRST_BACKOFF_TICKS
            > FailOpenCheck.LATE_SEND_TICKS);
  }

  @Test
  void answersSentBeforeANodeStopsAreHeardWithinTheStrayWindow() {
    // The slowest datagram arrives within a tick and waits at most a tick more.
    int slowest = FaultPlan.MAX_MIN_LATENCY_STEPS + FaultPlan.MAX_LATENCY_SPAN_STEPS;
    assertTrue(slowest < Simulation.STEPS_PER_TICK, "a datagram can take more than a tick");
    assertTrue(FailOpenCheck.STRAY_ANSWER_TICKS >= 2);
  }

  @Test
  void theSloIsTighterThanTheAdmissionBound() {
    // Answers later than the admission bound are abandoned, so an SLO at or past it cannot fail.
    assertTrue(
        SimRun.TICK_BUDGET < RequestTracker.MAX_TICKS_LATE,
        "the SLO budget is no tighter than what admission control already guarantees");
  }

  @Test
  void everySampledWorldFitsTheShapeItsConstructorDemands() {
    assertTrue(Topology.MAX_HEIGHT >= Topology.MIN_HEIGHT, "height range is inverted");
    assertTrue(
        Topology.MAX_RELIEF <= Topology.MIN_HEIGHT / 4, "a sampled hill can outgrow its column");
    assertEquals(0, RegionLayout.CELL % Chunks.SIZE, "cells are not chunk aligned");
    assertEquals(0, Region.HALO % Chunks.SIZE, "halos are not chunk aligned");
    // Four pathfinding horizons of about 64 blocks, the smallest region the spec allows.
    assertTrue(RegionLayout.CELL >= 4 * 64, "regions are smaller than the spec allows");
    assertTrue(Region.HALO >= 64, "a halo is narrower than one pathfinding horizon");
  }

  @Test
  void theNodeKeepsAnswersLongerThanTheControllerWaits() {
    long waitSteps = (long) RequestTracker.MAX_TICKS_LATE * Simulation.STEPS_PER_TICK;
    assertTrue(ModeledNode.RETENTION_STEPS > waitSteps, "a resend can miss its kept answer");
    assertTrue(ModeledNode.MAX_WAIT_STEPS > waitSteps, "a node drops a request still awaited");
  }

  @Test
  void everySampledSettingHasAUsableRange() {
    for (Scenario.Count setting : Scenario.Count.values()) {
      assertTrue(setting.ceiling() >= setting.floor(), setting + " has an inverted sampling range");
    }
    for (Scenario.Chance setting : Scenario.Chance.values()) {
      assertTrue(
          setting.ceiling() > 0 && setting.ceiling() <= 1,
          setting + " has a ceiling outside (0, 1]");
    }
  }
}
