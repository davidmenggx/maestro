package io.github.davidmenggx.maestro.controller.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * The properties a run must hold whatever the network did to it. Returns every violation rather
 * than failing on the first, so one seed reports everything it broke.
 */
public final class Invariants {

  private Invariants() {}

  public static List<String> check(
      Simulation sim, ProbeController controller, RecordingOutput out, double tickBudget) {
    List<String> violations = new ArrayList<>();

    // Per-region sequence monotonicity with no gaps, plus snapshot well-formedness.
    for (ModeledNode node : sim.nodes()) {
      violations.addAll(node.violations());
    }

    // Every request is answered or explicitly dropped, exactly once.
    int accounted = out.answered() + out.dropped();
    if (accounted != controller.requestsIssued()) {
      violations.add(
          "requests issued "
              + controller.requestsIssued()
              + " but accounted "
              + accounted
              + " (answered "
              + out.answered()
              + ", dropped "
              + out.dropped()
              + ")");
    }
    if (controller.outstandingCount() != 0) {
      violations.add(controller.outstandingCount() + " requests still outstanding at end of run");
    }

    // Latency SLO, measured only over requests that were actually answered.
    double p99 = sim.metrics().percentile("path.latency.ticks", 0.99);
    if (!Double.isNaN(p99) && p99 > tickBudget) {
      violations.add("p99 latency " + p99 + " ticks exceeds budget " + tickBudget);
    }

    return violations;
  }

  /**
   * State after a failover must equal a fresh full recomputation: a node that rebuilt from a
   * snapshot plus deltas holds what a node handed a fresh snapshot of the same world holds.
   */
  public static List<String> checkStateEquality(ModeledNode resumed, ModeledNode fresh) {
    List<String> violations = new ArrayList<>();
    if (!resumed.hasState() || !fresh.hasState()) {
      violations.add("cannot compare state: one node holds no region");
      return violations;
    }
    if (resumed.stateDigest() != fresh.stateDigest()) {
      violations.add(
          "region state differs after failover: "
              + Long.toHexString(resumed.stateDigest())
              + " vs "
              + Long.toHexString(fresh.stateDigest()));
    }
    return violations;
  }
}
