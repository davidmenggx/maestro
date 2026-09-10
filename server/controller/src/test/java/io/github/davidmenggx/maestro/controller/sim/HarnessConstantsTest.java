package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.world.Chunks;
import org.junit.jupiter.api.Test;

/**
 * The one place a harness constant is checked against another. Several are only correct relative to
 * something else, and left to drift apart they fail silently: the sweep still passes, having
 * quietly stopped reaching the case it was sized for. Nothing here runs a simulation.
 */
class HarnessConstantsTest {

  @Test
  void aSampledLatencyStaysWellInsideATick() {
    // Any wider and a response is late whatever the controller does, so the latency
    // invariant would be measuring the sampler instead of the code under test.
    assertTrue(
        FaultPlan.MAX_MIN_LATENCY_STEPS + FaultPlan.MAX_LATENCY_SPAN_STEPS
            <= Simulation.STEPS_PER_TICK / 2,
        "sampled latency can reach half a tick");
  }

  @Test
  void aTickIsTheSameLengthInStepsAsInNanoseconds() {
    assertEquals(Simulation.NANOS_PER_TICK, Simulation.NANOS_PER_STEP * Simulation.STEPS_PER_TICK);
  }

  @Test
  void theShortestFuzzedRunHasRoomForAFault() {
    // A fault starts inside the active window and, unless the node is lost for good,
    // ends there too.
    assertTrue(
        SimRun.MIN_FUZZED_TICKS - SimRun.DRAIN_TICKS > FaultPlan.MAX_FAULT_SPAN_TICKS,
        "the shortest fuzzed run has no room for a fault");
    assertTrue(SimRun.MAX_FUZZED_TICKS >= SimRun.MIN_FUZZED_TICKS, "run length range is inverted");
  }

  @Test
  void theSloIsTighterThanTheAdmissionBound() {
    // A saturated fleet is only held to the bound admission control promises, so the
    // budget the invariants default to has to be the stricter of the two.
    assertTrue(
        SimRun.TICK_BUDGET < ProbeController.MAX_TICKS_LATE,
        "the SLO budget is no tighter than what admission control already guarantees");
  }

  @Test
  void everySampledWorldFitsTheShapeItsConstructorDemands() {
    assertTrue(Topology.MAX_HEIGHT >= Topology.MIN_HEIGHT, "height range is inverted");
    assertTrue(
        Topology.MAX_RELIEF <= Topology.MIN_HEIGHT / 4, "a sampled hill can outgrow its column");
    for (int size : Topology.regionSizes()) {
      assertEquals(0, size % Chunks.SIZE, "region size " + size + " is not chunk aligned");
    }
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

  @Test
  void aComparisonNodeCannotCollideWithARealOne() {
    // Fresh nodes are built outside the fleet at `FRESH_NODE + region`, so the block
    // they sit in has to start above every id a sampled grid hands out.
    assertTrue(
        Invariants.FRESH_NODE >= Topology.maxNodeCount(),
        "a comparison node can collide with a real one");
  }
}
