package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The properties a run must satisfy regardless of network behavior. Returns every violation, not
 * just the first, so one seed reports everything it broke.
 */
public final class Invariants {

  /**
   * First id for the temporary nodes that a fresh recomputation is loaded into. One is created per
   * region, at {@code FRESH_NODE + region}, so this range must not overlap any id a fleet can
   * assign. A run gives its nodes {@code 0 .. nodeCount - 1}, and no sampled grid has more than
   * {@link Topology#maxNodeCount()} regions.
   */
  static final int FRESH_NODE = Topology.maxNodeCount();

  private Invariants() {}

  public static List<String> check(SimRun run) {
    List<String> violations = new ArrayList<>();

    // Every world frame the real node would refuse: a sequence gap, a malformed
    // snapshot, a cell outside the area or with a derived type, and a resume of
    // a stream the node does not hold.
    for (ModeledNode node : run.sim.nodes()) {
      violations.addAll(node.violations());
    }

    // A region's sequence never goes back, across replays and resnapshots alike.
    violations.addAll(run.sim.streamViolations());

    // The controller's own bookkeeping.
    violations.addAll(run.controller.selfCheck());

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
    if (run.controller.outstandingCount() != 0) {
      violations.add(
          run.controller.outstandingCount() + " requests still outstanding at end of run");
    }

    if (run.fitsCapacity()) {
      violations.addAll(checkSlo(run.sim.metrics()));
    }

    return violations;
  }

  /**
   * The latency SLO: p99 below {@link SimRun#TICK_BUDGET}. A request that timed out was never
   * answered, so it counts as a miss, later than any answer.
   */
  static List<String> checkSlo(SimMetrics metrics) {
    double p99 =
        metrics.percentileWithMisses("path.latency.ticks", 0.99, metrics.total("path.expired"));
    if (!Double.isNaN(p99) && p99 >= SimRun.TICK_BUDGET) {
      return List.of(
          "p99 latency "
              + p99
              + " ticks is not below the SLO of "
              + SimRun.TICK_BUDGET
              + " ("
              + metrics.total("path.expired")
              + " timed out)");
    }
    return List.of();
  }

  /**
   * A node has caught up when it holds its region and has applied everything the controller
   * produced for it. A node that is behind, partitioned or dead cannot be compared.
   */
  public static boolean caughtUp(ProbeController controller, ModeledNode node) {
    return node.assigned() && node.lastSequence() == controller.heldSequence(node.id());
  }

  /**
   * State after a failover must equal a fresh full recomputation: a node rebuilt from a snapshot
   * plus deltas must hold the same state as a node given a fresh snapshot of the same world.
   */
  public static List<String> checkAgainstFreshSnapshot(SimRun run, ModeledNode node) {
    int region = run.regions.indexOf(node.id());
    if (region < 0) {
      return List.of(node.id() + " owns no region to compare");
    }
    // Built outside the fleet, so comparing does not change `sim.nodes()`.
    ModeledNode fresh =
        new ModeledNode(run.sim, run.sim.transport(), NodeId.of(FRESH_NODE + region), 1);
    fresh.receive(
        Channel.WORLD,
        ByteBuffer.wrap(
            Frames.assignment(
                run.controller.run,
                region,
                run.regions.topology().regionSize(),
                false,
                run.world)));
    // A chunk the server does not hold is never sent, and is air on both sides.
    int chunksPerSide = run.regions.topology().regionSize() / Chunks.SIZE;
    int originChunkX = Chunks.of(run.regions.minX(region));
    int originChunkZ = Chunks.of(run.regions.minZ(region));
    long sequence = 0;
    for (int dx = 0; dx < chunksPerSide; dx++) {
      for (int dz = 0; dz < chunksPerSide; dz++) {
        int chunkX = originChunkX + dx;
        int chunkZ = originChunkZ + dz;
        if (!run.world.chunkLoaded(chunkX, chunkZ)) {
          continue;
        }
        fresh.receive(
            Channel.WORLD,
            ByteBuffer.wrap(
                Frames.chunkSnapshot(
                    ++sequence, dx, dz, Frames.readChunk(run.world, chunkX, chunkZ))));
      }
    }

    // A region whose chunks are all unloaded sends the fresh node nothing past its
    // assignment, which leaves it all air, as the compared node should be.
    if (!node.assigned()) {
      return List.of("cannot compare state: " + node.id() + " holds no region");
    }
    if (node.stateDigest() != fresh.stateDigest()) {
      return List.of(
          "region state differs after failover: "
              + node.id()
              + " "
              + Long.toHexString(node.stateDigest())
              + " vs fresh "
              + Long.toHexString(fresh.stateDigest()));
    }
    return List.of();
  }
}
