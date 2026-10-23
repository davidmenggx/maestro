package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.core.Controller;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import java.util.ArrayList;
import java.util.List;

/** The properties a run must satisfy regardless of network behavior. Returns every violation. */
public final class Invariants {

  private Invariants() {}

  public static List<String> check(SimRun run) {
    List<String> violations = new ArrayList<>();

    // World frames the real node would refuse.
    for (ModeledNode node : run.sim.nodes()) {
      violations.addAll(node.violations());
    }

    // A region's sequence never goes back, and a checkpointed node holds what the world held.
    violations.addAll(run.sim.streamViolations());

    // The transport always opens a link with a valid `Join`.
    long refused = run.sim.metrics().total("transport.refused");
    if (refused > 0) {
      violations.add(refused + " connections were refused by their node");
    }

    // The controller's own bookkeeping, of every process the run had.
    violations.addAll(run.retiredViolations());
    violations.addAll(run.controller.selfCheck());

    // A node is sent each profile at most once per link, so the controller forgets none it sent.
    int classes = run.scenario.counts().get(Scenario.Count.MOB_CLASSES);
    for (ModeledNode node : run.sim.nodes()) {
      String label = node.id().toString();
      long registered = run.sim.metrics().counter("mobclass.registered", "node", label);
      long links = run.sim.metrics().counter("transport.connected", "node", label);
      if (registered > classes * links) {
        violations.add(
            label
                + " was sent "
                + registered
                + " profiles over "
                + links
                + " links, but the world has "
                + classes);
      }
    }

    // Every frame fits its channel.
    long oversize = run.sim.metrics().total("transport.oversize");
    if (oversize > 0) {
      violations.add(oversize + " frames were larger than their channel carries");
    }

    // The network never creates a frame, so received exceeds sent only by duplicates.
    for (ModeledNode node : run.sim.nodes()) {
      String label = node.id().toString();
      for (Channel channel : Channel.values()) {
        long sent =
            run.sim.metrics().counter("transport.sent", "node", label, "channel", channel.name());
        long received =
            run.sim
                .metrics()
                .counter("transport.received", "node", label, "channel", channel.name());
        long duplicated =
            run.sim
                .metrics()
                .counter("transport.duplicated", "node", label, "channel", channel.name());
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

    // Every live node has caught up and holds the same state as a fresh snapshot.
    for (ModeledNode node : run.sim.nodes()) {
      if (caughtUp(run.controller, node)) {
        violations.addAll(checkAgainstFreshSnapshot(run, node));
      } else if (run.sim.transport().alive(node.id())) {
        violations.add(
            node.id()
                + " is alive but has not caught up: it holds sequence "
                + node.lastSequence()
                + " of "
                + run.controller.heldSequence(node.id()));
      }
    }

    // Every request is answered or explicitly dropped, exactly once.
    violations.addAll(run.out.ledgerViolations());

    // An answer is accepted only if its node held the chunk the search starts in.
    if (run.out.answeredOverAir() > 0) {
      violations.add(run.out.answeredOverAir() + " answers searched over air were accepted");
    }
    if (run.controller.outstandingCount() != 0) {
      violations.add(
          run.controller.outstandingCount() + " requests still outstanding at end of run");
    }

    // A node that cannot answer is sent no more than a few probing requests.
    violations.addAll(run.sim.failOpenViolations());

    if (run.heldToTheSlo()) {
      violations.addAll(checkSlo(run.out.latencies(), run.out.sloMisses()));
    }
    if (run.fitsCapacity()) {
      // Nothing here stops a node answering in time, so taking one out of service is wrong.
      long suspected = run.sim.metrics().total("node.suspected");
      long opened = run.sim.metrics().total("node.quiet");
      if (suspected + opened > 0) {
        violations.add(
            "a healthy node was taken out of service: "
                + suspected
                + " suspicions, "
                + opened
                + " quiet request paths");
      }
    }

    return violations;
  }

  /**
   * The latency SLO: p99 below {@link SimRun#TICK_BUDGET}. A miss is a request rejected for queue
   * depth, never answered in time, or dropped for want of a node. Misses rank above any answer.
   */
  static List<String> checkSlo(List<Double> latencies, long misses) {
    double p99 = SimMetrics.percentileWithMisses(latencies, 0.99, misses);
    if (!Double.isNaN(p99) && p99 >= SimRun.TICK_BUDGET) {
      return List.of(
          "p99 latency "
              + p99
              + " ticks is not below the SLO of "
              + SimRun.TICK_BUDGET
              + " ("
              + misses
              + " missed)");
    }
    return List.of();
  }

  /** A node has caught up when it holds its region and has applied everything produced for it. */
  public static boolean caughtUp(Controller controller, ModeledNode node) {
    return node.assigned() && node.lastSequence() == controller.heldSequence(node.id());
  }

  /**
   * State after a failover must equal a fresh recomputation. The expected state is read from the
   * world, not through the encoder, so an encoding bug cannot cancel out.
   */
  public static List<String> checkAgainstFreshSnapshot(SimRun run, ModeledNode node) {
    int region = run.regions.regionOf(node.id());
    if (region == RegionMap.NONE) {
      return List.of(node.id() + " owns no region to compare");
    }
    if (!node.assigned()) {
      return List.of("cannot compare state: " + node.id() + " holds no region");
    }
    long expected = freshDigest(run, region);
    if (node.stateDigest() != expected) {
      return List.of(
          "region state differs after failover: "
              + node.id()
              + " "
              + Long.toHexString(node.stateDigest())
              + " vs fresh "
              + Long.toHexString(expected));
    }
    return List.of();
  }

  /** The state a node of {@code regionId} holds with every frame applied: air for unheld chunks. */
  static long freshDigest(SimRun run, int regionId) {
    SimWorld world = run.world;
    Region region = run.regions.region(regionId);
    int perSide = region.size() / Chunks.SIZE;
    ModeledNode.AreaDigest digest =
        new ModeledNode.AreaDigest(regionId, region.size(), world.minY(), world.height());
    for (int cx = 0; cx < perSide; cx++) {
      for (int cz = 0; cz < perSide; cz++) {
        int chunkX = Chunks.of(region.minX()) + cx;
        int chunkZ = Chunks.of(region.minZ()) + cz;
        digest.chunk(cx * perSide + cz, world.chunkHash(chunkX, chunkZ));
      }
    }
    return digest.value();
  }
}
