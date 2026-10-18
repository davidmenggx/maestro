package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.world.Chunks;
import org.junit.jupiter.api.Test;

/**
 * The one place harness constants are checked against each other. Several are correct only relative
 * to another constant. If they drift apart, nothing fails: the sweep still passes but no longer
 * reaches the case it was sized for. Nothing here runs a simulation.
 */
class HarnessConstantsTest {

  @Test
  void anEdgeLatencyLeavesNoSlackForOneRequest() {
    // At the edge, one request served at the highest cost is answered on the tick's
    // last step the controller still reads, and one step more is a tick late.
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
  void theSloIsTighterThanTheAdmissionBound() {
    // Answers later than the admission bound are abandoned, so an SLO at or past it
    // could never fail.
    assertTrue(
        SimRun.TICK_BUDGET < RequestTracker.MAX_TICKS_LATE,
        "the SLO budget is no tighter than what admission control already guarantees");
  }

  @Test
  void everySampledWorldFitsTheShapeItsConstructorDemands() {
    assertTrue(Topology.MAX_HEIGHT >= Topology.MIN_HEIGHT, "height range is inverted");
    assertTrue(
        Topology.MAX_RELIEF <= Topology.MIN_HEIGHT / 4, "a sampled hill can outgrow its column");
    assertEquals(0, Topology.REGION_SIZE % Chunks.SIZE, "regions are not chunk aligned");
    // Four pathfinding horizons of about 64 blocks, the smallest region the spec allows.
    assertTrue(Topology.REGION_SIZE >= 4 * 64, "regions are smaller than the spec allows");
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
