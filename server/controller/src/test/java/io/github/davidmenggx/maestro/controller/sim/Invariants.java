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

  public static List<String> check(SimRun run, double tickBudget) {
    List<String> violations = new ArrayList<>();

    // Every world frame the real node would refuse: a sequence gap, a malformed
    // snapshot, a cell outside the area or with a derived type, and a resume of
    // a stream the node does not hold.
    for (ModeledNode node : run.sim.nodes()) {
      violations.addAll(node.violations());
    }

    // The controller's own bookkeeping, including the admission bound.
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

    // Every node that caught up with its stream holds the same state as a fresh
    // snapshot, regardless of the resets, replays and resnapshots before it.
    for (ModeledNode node : run.sim.nodes()) {
      if (caughtUp(run.controller, node)) {
        violations.addAll(checkAgainstFreshSnapshot(run, node));
      }
    }

    // Every request is answered or explicitly dropped, exactly once.
    int accounted = run.out.answered() + run.out.dropped();
    if (accounted != run.controller.requestsIssued()) {
      violations.add(
          "requests issued "
              + run.controller.requestsIssued()
              + " but accounted "
              + accounted
              + " (answered "
              + run.out.answered()
              + ", dropped "
              + run.out.dropped()
              + ")");
    }
    if (run.controller.outstandingCount() != 0) {
      violations.add(
          run.controller.outstandingCount() + " requests still outstanding at end of run");
    }

    // Latency SLO, measured only over requests that were answered.
    double p99 = run.sim.metrics().percentile("path.latency.ticks", 0.99);
    if (!Double.isNaN(p99) && p99 > tickBudget) {
      violations.add("p99 latency " + p99 + " ticks exceeds budget " + tickBudget);
    }

    return violations;
  }

  /**
   * A node has caught up when it holds a region and its world stream reached the last sequence the
   * controller produced for it. A node that is behind, partitioned or dead cannot be compared.
   */
  public static boolean caughtUp(ProbeController controller, ModeledNode node) {
    return node.hasState() && node.lastSequence() == controller.producedSequence(node.id());
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

    if (!node.hasState() || !fresh.hasState()) {
      return List.of("cannot compare state: one node holds no region");
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
