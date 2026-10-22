package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.core.Controller;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import java.util.ArrayList;
import java.util.List;

/**
 * The properties a run must satisfy regardless of network behavior. Returns every violation, not
 * just the first, so one seed reports everything it broke.
 */
public final class Invariants {

  private Invariants() {}

  public static List<String> check(SimRun run) {
    List<String> violations = new ArrayList<>();

    // Every world frame the real node would refuse: a sequence gap, a malformed
    // snapshot, a cell outside the area or with a derived type, and a resume of
    // a stream the node does not hold.
    for (ModeledNode node : run.sim.nodes()) {
      violations.addAll(node.violations());
    }

    // A region's sequence never goes back, across replays and resnapshots alike, a
    // stream starts over only where it stands, and a node at a checkpoint holds what
    // the world held then.
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

    // Every frame fits its channel: a request frame one datagram, a world frame what
    // the node reads.
    long oversize = run.sim.metrics().total("transport.oversize");
    if (oversize > 0) {
      violations.add(oversize + " frames were larger than their channel carries");
    }

    // The modeled network never creates a frame. A duplicate is sent once and
    // arrives twice, which is the only way received can exceed sent.
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

    // Every node still alive has caught up with its stream: faults end before the
    // quiet tail, which leaves time to reconnect and send a region again. Every node
    // that caught up holds the same state as a fresh snapshot, regardless of the
    // resets, replays and resnapshots before it.
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

    // A node that cannot answer stops being sent requests, past the few that show it
    // and a probe now and then, so its mobs are searched on the server instead.
    violations.addAll(run.sim.failOpenViolations());

    if (run.heldToTheSlo()) {
      violations.addAll(checkSlo(run.out.latencies(), run.out.sloMisses()));
    }
    if (run.fitsCapacity()) {
      // Nothing in such a run stops a node from answering in time, a server that stalls or
      // falls behind included, so taking one out of service would be a false alarm.
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
   * depth, never answered in time, or servable and dropped for want of a node. It ranks later than
   * any answer, so a run cannot meet the SLO by refusing work.
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

  /**
   * A node has caught up when it holds its region and has applied everything the controller
   * produced for it. A node that is behind, partitioned or dead cannot be compared.
   */
  public static boolean caughtUp(Controller controller, ModeledNode node) {
    return node.assigned() && node.lastSequence() == controller.heldSequence(node.id());
  }

  /**
   * State after a failover must equal a fresh full recomputation: a node rebuilt from a snapshot
   * plus deltas must hold what the world holds now. The expected state is read from the world
   * directly, not through the encoder, so an encoding bug cannot cancel out.
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

  /**
   * The state a node assigned {@code regionId} holds once it has every frame: each block of a chunk
   * the server holds, and air elsewhere, as vanilla reads a chunk it could not fetch.
   */
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
