package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The properties a run must hold whatever the network did to it. Returns every violation rather
 * than failing on the first, so one seed reports everything it broke.
 */
public final class Invariants {

  /** Id for the throwaway node a fresh recomputation is loaded into. Outside any region grid. */
  private static final int FRESH_NODE = 90;

  private Invariants() {}

  public static List<String> check(
      Simulation sim, ProbeController controller, RecordingOutput out, double tickBudget) {
    List<String> violations = new ArrayList<>();

    // Per-region sequence monotonicity with no gaps, snapshot well-formedness, the
    // per-block cell contract, and requests naming a registered mob class.
    for (ModeledNode node : sim.nodes()) {
      violations.addAll(node.violations());
    }

    // The controller's own bookkeeping, including the admission bound.
    violations.addAll(controller.selfCheck());

    // The modeled network never invents a frame. A duplicate is sent once and
    // arrives twice, so that is the only way received may exceed sent.
    for (ModeledNode node : sim.nodes()) {
      String label = node.id().toString();
      for (Channel channel : Channel.values()) {
        long sent =
            sim.metrics().counter("transport.sent", "node", label, "channel", channel.name());
        long received =
            sim.metrics().counter("transport.received", "node", label, "channel", channel.name());
        long duplicated =
            sim.metrics().counter("transport.duplicated", "node", label, "channel", channel.name());
        if (received > sent + duplicated) {
          violations.add(
              label
                  + " "
                  + channel
                  + " received "
                  + received
                  + " of "
                  + sent
                  + " sent plus "
                  + duplicated
                  + " duplicated");
        }
      }
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
  public static List<String> checkAgainstFreshSnapshot(
      Simulation sim, ModeledNode node, SimWorld world, RegionMap regions) {
    int region = regions.indexOf(node.id());
    if (region < 0) {
      return List.of(node.id() + " owns no region to compare");
    }
    ModeledNode fresh = sim.addNode(NodeId.of(FRESH_NODE), 1);
    fresh.receive(
        Channel.WORLD, ByteBuffer.wrap(ProbeController.encodeAssignment(regions, region, 0)));
    fresh.receive(
        Channel.WORLD, ByteBuffer.wrap(ProbeController.snapshotFrame(world, regions, region, 1)));
    return checkStateEquality(node, fresh);
  }

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
