package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Chance;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Fixed-seed runs, each pinning one behavior to an exact count that a sweep cannot state. */
class SimulationTest {

  private static final long TICKS = 150;

  /** Tick the node the retention test picks on loses its connection. */
  private static final long CUT_TICK = 40;

  /**
   * How long that node stays away. A region emits at most one world frame a tick, so the partition
   * has to outlast the replay window several times over before the stream rolls past the resume
   * point. Too short a margin shows up as the resume count below going non-zero.
   */
  private static final long AWAY_TICKS = 4L * ProbeController.RETENTION_FRAMES;

  /** Quiet ticks after the partition lifts, for the resnapshot to land and the run to settle. */
  private static final long TAIL_TICKS = 50;

  /** Tick a node dies at, two ticks before the controller's next reconnect attempt. */
  private static final long DEATH_TICK = 38;

  /** Tick the control channel stalls at: after the fleet is greeted, before profiles settle. */
  private static final long STALL_TICK = 2;

  /** Long enough for several requests to name a profile still held behind the stall. */
  private static final long STALL_TICKS = 15;

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
  void eachChunkIsSentOnceThenStreamedAsDeltas() {
    SimRun run = SimRun.of(3L, FaultPlan.reliable(), TICKS).go();

    assertEquals(
        run.chunkCount(),
        run.sim.metrics().counter("offload.submitted", "job", "chunk"),
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
    SimRun run = SimRun.of(5L, FaultPlan.reliable().withDuplicateRate(0.4), TICKS).go();

    // The node re-answers a retry with the same bytes, so the controller sees more
    // responses than requests and must discard the extras rather than double-apply.
    assertTrue(run.sim.nodes().stream().mapToLong(ModeledNode::duplicateRequests).sum() > 0);
    assertTrue(run.sim.metrics().counter("path.response.stale") > 0, run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  @Test
  void everyNodeIsToldAProfileBeforeItIsAskedToUseIt() {
    SimRun run = SimRun.of(17L, FaultPlan.reliable(), TICKS).go();

    // With nothing holding the control channel back, no request ever overtakes its profile.
    // Registration costs one request per profile, which the drop count shows.
    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().total("path.profile.unknown"), run.describe());
    assertTrue(run.out.dropped(DropReason.CLASS_UNREGISTERED) > 0, run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(node.knownClassCount() > 0, run.describe());
      assertTrue(node.knownClassCount() <= run.world.mobClassCount(), run.describe());
    }
  }

  @Test
  void aRequestThatOvertakesItsProfileIsDroppedAtOnce() {
    // The control channel stalls just after the fleet is greeted, so profiles registered in the
    // first ticks sit behind it while requests naming them arrive over UDP.
    FaultPlan plan = FaultPlan.reliable();
    for (NodeId node : nodeIds()) {
      plan =
          plan.with(
              new FaultPlan.Stalled(STALL_TICK, node, true),
              new FaultPlan.Stalled(STALL_TICK + STALL_TICKS, node, false));
    }
    SimRun run =
        SimRun.of(
                37L,
                plan,
                TICKS,
                Scenario.DEFAULT.with(Count.MOB_CLASSES, 4).with(Chance.REQUEST, 0.3))
            .go();

    assertEquals(List.of(), run.violations(), run.describe());
    long unknown = run.sim.metrics().total("path.profile.unknown");
    assertTrue(unknown > 0, run.describe());
    // Each one was dropped on the spot, under the same reason as the registering request, and
    // none was left to time out.
    assertTrue(run.out.dropped(DropReason.CLASS_UNREGISTERED) >= unknown, run.describe());
    assertEquals(0, run.out.dropped(DropReason.SHED), run.describe());
    assertTrue(run.fleetCounter("transport.held", Channel.CONTROL) > 0, run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
  }

  @Test
  void aFailedEncodeIsRetriedUnderTheSameSequence() {
    SimRun run = SimRun.of(41L, FaultPlan.reliable().withOffloadFailureRate(0.3), TICKS).go();

    // A retry builds the same frame under the same number, so the stream has no gap and every
    // node ends holding what a fresh snapshot holds.
    assertEquals(List.of(), run.violations(ProbeController.MAX_TICKS_LATE), run.describe());
    assertTrue(run.sim.metrics().total("offload.failed") > 0, run.describe());
    assertEquals(
        run.sim.metrics().total("offload.failed"),
        run.sim.metrics().total("offload.retried"),
        run.describe());
    assertTrue(run.sim.metrics().counter("offload.retried", "job", "chunk") > 0, run.describe());
    assertTrue(run.sim.metrics().counter("offload.retried", "job", "delta") > 0, run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(Invariants.caughtUp(run.controller, node), node.id() + " " + run.describe());
    }
  }

  @Test
  void aRequestBatchThatOutlivesItsConnectionIsNotSent() {
    // Encodes fail often, so a batch built before the node dies is still being retried once it
    // is back. Its requests were dropped with the old connection, and the profiles they name are
    // not registered on the new one, so sending it would ask the node for profiles it never got.
    NodeId victim = NodeId.of(0);
    FaultPlan plan =
        FaultPlan.reliable()
            .withOffloadFailureRate(0.5)
            .with(
                new FaultPlan.NodeDied(DEATH_TICK, victim),
                new FaultPlan.NodeRecovered(DEATH_TICK + 1, victim));

    SimRun run = SimRun.of(8L, plan, TICKS, Scenario.DEFAULT.with(Chance.REQUEST, 0.3)).go();

    assertEquals(List.of(), run.violations(ProbeController.MAX_TICKS_LATE), run.describe());
    assertTrue(run.sim.metrics().total("requests.discarded") > 0, run.describe());
    assertEquals(0, run.sim.metrics().total("path.profile.unknown"), run.describe());
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
    // The world stream restarts from a fresh send rather than resuming into a gap,
    // and what the node rebuilt has to equal a fresh recomputation of the same world.
    assertTrue(first.sim.node(victim).hasState(), first.describe());
    assertTrue(
        first.sim.metrics().counter("offload.submitted", "job", "chunk") > first.chunkCount(),
        first.describe());
    assertEquals(
        List.of(),
        Invariants.checkAgainstFreshSnapshot(first, first.sim.node(victim)),
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
    // The node kept its state across the reset, so no chunk was sent twice.
    assertEquals(
        run.chunkCount(),
        run.sim.metrics().counter("offload.submitted", "job", "chunk"),
        run.describe());
    assertEquals(
        List.of(), Invariants.checkAgainstFreshSnapshot(run, run.sim.node(victim)), run.describe());
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
                Scenario.DEFAULT.with(Count.BLOCK_CHANGES_PER_TICK, CHANGES_PER_TICK))
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
        run.sim.metrics().counter("offload.submitted", "job", "chunk") > run.chunkCount(),
        run.describe());
    assertEquals(
        List.of(), Invariants.checkAgainstFreshSnapshot(run, run.sim.node(victim)), run.describe());
  }

  @Test
  void aNodeReportsItsQueueDepthWithoutBeingAsked() {
    SimRun run = SimRun.of(31L, FaultPlan.reliable(), TICKS, Scenario.SATURATED).go();

    // Saturated, so the fleet is held to the admission bound rather than the SLO.
    assertEquals(List.of(), run.violations(ProbeController.MAX_TICKS_LATE), run.describe());
    // Nothing on the control channel asks for this, so a depth arriving at all is the claim.
    assertTrue(
        run.regions.nodes().stream()
            .anyMatch(
                node ->
                    run.sim
                            .metrics()
                            .percentile("node.queue.reported", 1.0, "node", node.toString())
                        > 0),
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
    assertTrue(run.fleetCounter("transport.held", Channel.WORLD) > 0, run.describe());
    assertTrue(run.sim.node(victim).lastSequence() > 1, run.describe());
  }

  private static List<NodeId> nodeIds() {
    return IntStream.range(0, Topology.DEFAULT.nodeCount()).mapToObj(NodeId::of).toList();
  }
}
