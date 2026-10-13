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
  void aSampledLatencyStaysWellInsideATick() {
    // Any wider and a response is late regardless of the controller, so the latency
    // invariant would measure the sampler instead of the code under test.
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
    // Fresh nodes are built outside the fleet at `FRESH_NODE + region`, so their
    // id range must start above every id a sampled grid assigns.
    assertTrue(
        Invariants.FRESH_NODE >= Topology.maxNodeCount(),
        "a comparison node can collide with a real one");
  }
}
