package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Chance;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Fixed-seed runs, each checking one behavior against an exact count that a sweep cannot state. */
class SimulationTest {

  private static final long TICKS = 150;

  /** Tick at which the node chosen by the retention test loses its connection. */
  private static final long CUT_TICK = 40;

  /**
   * How long that node stays away. A region emits at most one world frame a tick, so the partition
   * must last several times the replay window before the stream moves past the resume point. If the
   * margin is too short, the resume count below becomes non-zero.
   */
  private static final long AWAY_TICKS = 4L * ProbeController.RETENTION_FRAMES;

  /** Quiet ticks after the partition ends, for the new snapshot to arrive and the run to settle. */
  private static final long TAIL_TICKS = 50;

  /** Tick at which a node dies, two ticks before the controller's next reconnect attempt. */
  private static final long DEATH_TICK = 38;

  /**
   * Tick at which the control channel stalls: after the fleet connects, before profiles are
   * registered.
   */
  private static final long STALL_TICK = 2;

  /** Long enough for several requests to name a profile that is still held behind the stall. */
  private static final long STALL_TICKS = 15;

  /** Block changes per tick, enough that most regions see one on most ticks. */
  private static final int CHANGES_PER_TICK = 2 * Topology.DEFAULT.nodeCount();

  @Test
  void sameSeedReplaysIdentically() {
    // Both runs share a JVM, so identity hash codes differ between them, and an
    // identity-ordered collection anywhere produces a digest mismatch.
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
  void aFleetWithSpareCapacityIsHeldToTheSlo() {
    SimRun run = SimRun.of(3L, FaultPlan.reliable(), TICKS).go();

    assertTrue(run.fitsCapacity(), run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
    assertEquals(
        List.of(), Invariants.checkSlo(run.out.latencies(), run.out.sloMisses()), run.describe());
  }

  /**
   * Each way takes just under the most a correct controller can absorb, so a round trip takes
   * nearly a tick and the SLO leaves the controller no slack.
   */
  @Test
  void aCleanRunAtTheLatencyEdgeStillMeetsTheSlo() {
    FaultPlan edge =
        new FaultPlan(
            FaultPlan.MAX_ONE_WAY_LATENCY_STEPS - 10,
            FaultPlan.MAX_ONE_WAY_LATENCY_STEPS,
            0.0,
            0.0,
            0.0,
            List.of());
    SimRun run = SimRun.of(3L, edge, TICKS).go();

    assertTrue(run.fitsCapacity(), run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
    assertEquals(
        2.0, run.out.latencies().stream().mapToDouble(Double::doubleValue).max().orElse(0));
    // Every answer lands before the tick that checks for resends ends.
    assertEquals(0, run.sim.metrics().total("path.resent"), run.describe());
  }

  /** The negative control: a little past the edge, even this controller misses the SLO. */
  @Test
  void aLatencyPastTheEdgeMakesEvenACorrectControllerLate() {
    int late = Simulation.SERVICE_STEP + 10;
    SimRun run = SimRun.of(3L, new FaultPlan(late, late, 0.0, 0.0, 0.0, List.of()), TICKS).go();

    assertTrue(run.fitsCapacity(), run.describe());
    assertEquals(1, Invariants.checkSlo(run.out.latencies(), run.out.sloMisses()).size());
  }

  @Test
  void aRunWithAFaultIsNotHeldToTheSlo() {
    // A lost request times out, which the SLO counts as a miss, so a lossy run is held
    // only to the other invariants.
    SimRun run = SimRun.of(3L, FaultPlan.reliable().withDropRate(0.05), TICKS).go();

    assertFalse(run.fitsCapacity(), run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  @Test
  void aDuplicatedRequestIsAnsweredOnceByTheController() {
    SimRun run = SimRun.of(5L, FaultPlan.reliable().withDuplicateRate(0.4), TICKS).go();

    // The node answers a retry with identical bytes, so the controller sees more
    // responses than requests and must discard the extras, not apply them twice.
    assertTrue(run.sim.nodes().stream().mapToLong(ModeledNode::duplicateRequests).sum() > 0);
    assertTrue(run.sim.metrics().counter("path.response.stale") > 0, run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  @Test
  void everyNodeIsToldAProfileBeforeItIsAskedToUseIt() {
    SimRun run = SimRun.of(17L, FaultPlan.reliable(), TICKS).go();

    // With nothing delaying the control channel, no request arrives before its profile.
    // Registration costs one request per profile, as the drop count shows.
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
    // The control channel stalls just after the fleet connects, so profiles registered in
    // the first ticks are held back while requests naming them arrive over UDP.
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
    // Each was dropped immediately, with the same reason as the registering request,
    // and none timed out.
    assertTrue(run.out.dropped(DropReason.CLASS_UNREGISTERED) >= unknown, run.describe());
    assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
    assertTrue(run.fleetCounter("transport.held", Channel.CONTROL) > 0, run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
  }

  @Test
  void aFailedEncodeIsRetriedUnderTheSameSequence() {
    SimRun run = SimRun.of(41L, FaultPlan.reliable().withOffloadFailureRate(0.3), TICKS).go();

    // A retry builds the same frame with the same sequence number, so the stream has no
    // gap and every node ends with the same state as a fresh snapshot.
    assertEquals(List.of(), run.violations(), run.describe());
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
    // Encodes fail often, so a batch built before the node dies is still being retried
    // after it returns. Its requests were dropped with the old connection, and the
    // profiles they name are not registered on the new one, so sending it would ask the
    // node for profiles it never received.
    NodeId victim = NodeId.of(0);
    FaultPlan plan =
        FaultPlan.reliable()
            .withOffloadFailureRate(0.5)
            .with(
                new FaultPlan.NodeDied(DEATH_TICK, victim),
                new FaultPlan.NodeRecovered(DEATH_TICK + 1, victim));

    SimRun run = SimRun.of(8L, plan, TICKS, Scenario.DEFAULT.with(Chance.REQUEST, 0.3)).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(run.sim.metrics().total("requests.discarded") > 0, run.describe());
    assertEquals(0, run.sim.metrics().total("path.profile.unknown"), run.describe());
  }

  @Test
  void saturationIsRejectedBeforeSendingRatherThanMissedLater() {
    SimRun run = SimRun.of(SimRun.baseSeed(), FaultPlan.reliable(), TICKS, Scenario.SATURATED).go();

    assertTrue(run.out.dropped(DropReason.QUEUE_DEPTH) > 0, run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
    // Everything admitted is answered in time: requests the controller gave up on still
    // counted against their node until it was done with them.
    assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
    // A saturated fleet is not held to the 3-tick SLO. Shedding exists for that case.
    assertFalse(run.fitsCapacity(), run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  @Test
  void aSaturatedNodeDropsTheRequestsItsMobsNoLongerWant() {
    SimRun run = SimRun.of(SimRun.baseSeed(), FaultPlan.reliable(), TICKS, Scenario.SATURATED).go();

    assertEquals(List.of(), run.violations(), run.describe());
    // Mobs ask again while their older requests still wait behind a backlog, so the
    // cancels reach requests the node has not started.
    assertTrue(run.sim.metrics().total("path.cancels") > 0, run.describe());
    assertTrue(
        run.sim.nodes().stream().mapToLong(ModeledNode::canceledRequests).sum() > 0,
        run.describe());
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
    // The world stream restarts with a new send instead of resuming into a gap, and
    // the rebuilt node state must equal a fresh recomputation of the same world.
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
    // Once when it first joined with nothing held, and once when it returned too far behind.
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

    // Saturated, so the fleet is not held to the SLO.
    assertEquals(List.of(), run.violations(), run.describe());
    // Nothing on the control channel requests this, so the test checks that a depth arrives at all.
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
    // timeout does, which is the gap a failure detector fills.
    assertEquals(0, run.out.dropped(DropReason.NODE_LOST), run.describe());
    assertTrue(run.out.dropped(DropReason.TIMEOUT) > 0, run.describe());
    // Frames are held, not lost, so the stream has no gap once the partition ends.
    assertTrue(run.fleetCounter("transport.held", Channel.WORLD) > 0, run.describe());
    assertTrue(run.sim.node(victim).lastSequence() > 1, run.describe());
  }

  private static List<NodeId> nodeIds() {
    return IntStream.range(0, Topology.DEFAULT.nodeCount()).mapToObj(NodeId::of).toList();
  }
}
