package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fixed-seed runs, each pinning one behavior to an exact count that a sweep cannot state: which
 * recovery a fault takes, how many snapshots it costs, and which drop reason a request gets. The
 * sweeps in {@link FuzzTest} cover the same code over the ranges these settings are drawn from, so
 * anything they already assert as an aggregate is not repeated here.
 */
class SimulationTest {

  private static final long TICKS = 150;

  /** Tick the node the retention test picks on loses its connection. */
  private static final long CUT_TICK = 40;

  /**
   * How long that node stays away. Its region emits at most one world frame a tick, and only on a
   * tick that changes a block inside it, so the partition has to outlast the replay window by
   * several times its length before the stream rolls past the resume point. A margin that stopped
   * being enough shows up as the resume count below going non-zero, not as a quiet pass.
   */
  private static final long AWAY_TICKS = 4L * ProbeController.RETENTION_FRAMES;

  /** Quiet ticks after the partition lifts, for the resnapshot to land and the run to settle. */
  private static final long TAIL_TICKS = 50;

  /** Changes a tick, enough that every region sees one most ticks rather than only some of them. */
  private static final int CHANGES_PER_TICK = 2 * Topology.DEFAULT.nodeCount();

  @Test
  void sameSeedReplaysIdentically() {
    // Both runs share a JVM, so identity hash codes differ between them and an
    // identity-ordered collection anywhere shows up as a digest mismatch.
    SimRun first = SimRun.of(7L, FaultPlan.reliable(), TICKS).go();
    SimRun second = SimRun.of(7L, FaultPlan.reliable(), TICKS).go();

    assertEquals(first.sim.digest().value(), second.sim.digest().value(), first.describe());
    assertEquals(first.out.results(), second.out.results());
    assertEquals(first.sim.metrics().counters(), second.sim.metrics().counters());
  }

  @Test
  void eachRegionIsSnapshottedOnceThenStreamedAsDeltas() {
    SimRun run = SimRun.of(3L, FaultPlan.reliable(), TICKS).go();

    assertEquals(
        run.regions.count(),
        run.sim.metrics().counter("offload.submitted", "job", "snapshot"),
        run.describe());
    assertTrue(
        run.sim.metrics().counter("offload.submitted", "job", "delta") > run.regions.count(),
        run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(node.hasState(), run.describe());
      assertTrue(node.lastSequence() > 1, run.describe());
    }
  }

  @Test
  void aDuplicatedRequestIsAnsweredOnceByTheController() {
    SimRun run = SimRun.of(5L, FaultPlan.reliable().withLoss(0.0, 0.4), TICKS).go();

    // The node re-answers a retry with the same bytes, so the controller sees more
    // responses than requests and must discard the extras rather than double-apply.
    assertTrue(run.sim.nodes().stream().mapToLong(ModeledNode::duplicateRequests).sum() > 0);
    assertTrue(run.sim.metrics().counter("path.response.stale") > 0, run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  @Test
  void everyNodeIsToldTheMobClassTableBeforeItIsAskedForAPath() {
    SimRun run = SimRun.of(17L, FaultPlan.reliable(), TICKS).go();

    assertEquals(List.of(), run.violations(), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertEquals(run.world.mobClasses().size(), node.knownClassCount(), run.describe());
    }
  }

  @Test
  void saturationIsRejectedBeforeSendingRatherThanMissedLater() {
    SimRun run = SimRun.of(SimRun.baseSeed(), FaultPlan.reliable(), TICKS, Scenario.SATURATED).go();

    assertTrue(run.out.dropped(DropReason.QUEUE_DEPTH) > 0, run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
    // Whatever admission control let through was answered inside the bound it
    // promised. The 3-tick SLO is a tighter statement that a saturated fleet does
    // not hold; that is what shedding is for.
    assertEquals(List.of(), run.violations(ProbeController.MAX_TICKS_LATE), run.describe());
  }

  @Test
  void nodeDeathDropsItsRequestsAndTheRunStaysDeterministic() {
    NodeId victim = NodeId.of(0);
    FaultPlan plan =
        FaultPlan.reliable()
            .with(new FaultPlan.NodeDied(40, victim), new FaultPlan.NodeRecovered(45, victim));

    SimRun first = SimRun.of(11L, plan, TICKS).go();
    SimRun second = SimRun.of(11L, plan, TICKS).go();

    assertEquals(first.sim.digest().value(), second.sim.digest().value(), first.describe());
    assertEquals(List.of(), first.violations(), first.describe());
    assertTrue(
        first.out.dropped(DropReason.NODE_LOST) + first.out.dropped(DropReason.NO_NODE) > 0,
        first.describe());
    // The world stream restarts from a fresh snapshot rather than resuming into a gap.
    assertTrue(first.sim.node(victim).hasState(), first.describe());
    assertTrue(
        first.sim.metrics().counter("offload.submitted", "job", "snapshot") > first.regions.count(),
        first.describe());
  }

  @Test
  void aLinkResetReplaysTheWorldStreamInsteadOfResnapshotting() {
    NodeId victim = NodeId.of(2);
    FaultPlan plan = FaultPlan.reliable().with(new FaultPlan.LinkReset(60, victim));

    SimRun run = SimRun.of(23L, plan, TICKS).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(
        1, run.sim.metrics().counter("world.resume", "node", victim.toString()), run.describe());
    // The node kept its state across the reset, so no region was snapshotted twice.
    assertEquals(
        run.regions.count(),
        run.sim.metrics().counter("offload.submitted", "job", "snapshot"),
        run.describe());
    assertEquals(
        List.of(),
        Invariants.checkAgainstFreshSnapshot(run.sim, run.sim.node(victim), run.world, run.regions),
        run.describe());
  }

  @Test
  void aNodeThatFallsPastTheRetentionWindowIsResnapshotted() {
    NodeId victim = NodeId.of(2);
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.LinkReset(CUT_TICK, victim),
                new FaultPlan.Partitioned(CUT_TICK, victim, true),
                new FaultPlan.Partitioned(CUT_TICK + AWAY_TICKS, victim, false));

    SimRun run =
        SimRun.of(
                29L,
                plan,
                CUT_TICK + AWAY_TICKS + TAIL_TICKS,
                Scenario.DEFAULT.toBuilder().maxBlockChangesPerTick(CHANGES_PER_TICK).build())
            .go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(
        0, run.sim.metrics().counter("world.resume", "node", victim.toString()), run.describe());
    // Once when it first joined holding nothing, once when it came back too far behind.
    assertEquals(
        2,
        run.sim.metrics().counter("world.resnapshot", "node", victim.toString()),
        run.describe());
    assertTrue(
        run.sim.metrics().counter("offload.submitted", "job", "snapshot") > run.regions.count(),
        run.describe());
    assertEquals(
        List.of(),
        Invariants.checkAgainstFreshSnapshot(run.sim, run.sim.node(victim), run.world, run.regions),
        run.describe());
  }

  @Test
  void aPartitionedNodeIsSilentAndItsRequestsTimeOut() {
    NodeId victim = NodeId.of(1);
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Partitioned(40, victim, true),
                new FaultPlan.Partitioned(80, victim, false));

    SimRun run = SimRun.of(13L, plan, TICKS).go();

    assertEquals(List.of(), run.violations(), run.describe());
    // No reset, so nothing tells the controller the node is gone. Only the request
    // timeout does, and that gap is what a failure detector fills.
    assertEquals(0, run.out.dropped(DropReason.NODE_LOST), run.describe());
    assertTrue(run.out.dropped(DropReason.SHED) > 0, run.describe());
    // Held rather than lost, so the stream has no gap once the partition lifts.
    assertTrue(counter(run, "transport.held", Channel.WORLD) > 0, run.describe());
    assertTrue(run.sim.node(victim).lastSequence() > 1, run.describe());
  }

  private static long counter(SimRun run, String name, Channel channel) {
    long total = 0;
    for (int i = 0; i < run.regions.count(); i++) {
      total +=
          run.sim
              .metrics()
              .counter(name, "node", NodeId.of(i).toString(), "channel", channel.name());
    }
    return total;
  }
}
