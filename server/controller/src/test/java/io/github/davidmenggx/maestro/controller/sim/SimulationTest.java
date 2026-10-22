package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.core.WorldStreams;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Chance;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
  private static final long AWAY_TICKS = 4L * WorldStreams.RETENTION_FRAMES;

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

  /**
   * Four workers serve each tick's few requests at once. The server works the whole tick, so a
   * batch leaves only when the next tick's work ends, a tick before its answers are due.
   */
  private static final Scenario EDGE_SCENARIO =
      Scenario.DEFAULT.with(Count.WORKERS, 4).with(Count.SERVER_BUSY_PERCENT, 100);

  /** Holds the origin's region, where the spawn chunks keep the world loaded. */
  private static final NodeId SPAWN_HOLDER = NodeId.of(0);

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
        run.sim.metrics().counter("offload.submitted", "job", "delta")
            > run.regions.regions().size(),
        run.describe());
    // A region where no chunk was ever loaded has nothing to stream.
    Set<Integer> loaded = new TreeSet<>();
    for (int i = 0; i < run.world.loadedCount(); i++) {
      long chunk = run.world.loadedChunk(i);
      Region region =
          run.regions.regionAt((int) (chunk >> 32) * Chunks.SIZE, (int) chunk * Chunks.SIZE);
      if (region != null) {
        loaded.add(region.id());
      }
    }
    assertFalse(loaded.isEmpty(), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      if (loaded.contains(run.regions.regionOf(node.id()))) {
        assertTrue(node.hasState(), run.describe());
        assertTrue(node.lastSequence() > 1, run.describe());
      }
    }
  }

  @Test
  void aFleetWithSpareCapacityIsHeldToTheSlo() {
    SimRun run = SimRun.of(3L, FaultPlan.reliable(), TICKS).go();

    assertTrue(run.fitsCapacity(), run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
    assertEquals(
        List.of(), Invariants.checkSlo(run.out.latencies(), run.out.sloMisses()), run.describe());
    // The run drains, so nothing counts against a node at its end.
    assertEquals(0, run.sim.metrics().gauge("controller.outstanding"), run.describe());
  }

  /**
   * Each way takes the most at which one request at the highest cost is still answered in time, so
   * a round trip and a search take nearly a tick and the SLO leaves the controller no slack.
   */
  @Test
  void aCleanRunAtTheLatencyEdgeStillMeetsTheSlo() {
    Scenario wide = EDGE_SCENARIO;
    int edgeLatency = FaultPlan.edgeLatency(wide.maxCostSteps(Topology.DEFAULT.nodeCount()));
    FaultPlan edge = new FaultPlan(edgeLatency - 10, edgeLatency, 0.0, 0.0, 0.0, List.of());
    SimRun run = SimRun.of(3L, edge, TICKS, wide).go();

    assertTrue(run.fitsCapacity(), run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
    assertEquals(
        2.0, run.out.latencies().stream().mapToDouble(Double::doubleValue).max().orElse(0));
    // Every answer lands before the tick that checks for resends ends.
    assertEquals(0, run.sim.metrics().total("path.resent"), run.describe());
  }

  /**
   * The negative control: a little past the edge for even the cheapest search, every answer is a
   * tick late, and the run is known from its inputs not to fit.
   */
  @Test
  void aLatencyPastTheEdgeMakesEvenACorrectControllerLate() {
    Scenario wide = EDGE_SCENARIO;
    int late = FaultPlan.edgeLatency(wide.serviceOf(0).minCostSteps()) + 1;
    SimRun run =
        SimRun.of(3L, new FaultPlan(late, late, 0.0, 0.0, 0.0, List.of()), TICKS, wide).go();

    assertFalse(run.fitsCapacity(), run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
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
    // A node is told a profile only once a request for it names one.
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(node.servedRequests() == 0 || node.knownClassCount() > 0, run.describe());
      assertTrue(node.knownClassCount() <= run.world.mobClassCount(), run.describe());
    }
    assertTrue(run.sim.nodes().stream().anyMatch(node -> node.knownClassCount() > 0));
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
    // Each failure is retried, unless it was the last attempt.
    assertEquals(
        run.sim.metrics().total("offload.failed"),
        run.sim.metrics().total("offload.retried") + run.sim.metrics().total("offload.abandoned"),
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
            .withOffloadFailureRate(0.7)
            .with(
                new FaultPlan.NodeDied(DEATH_TICK, victim),
                new FaultPlan.NodeRecovered(DEATH_TICK + 1, victim));

    long discarded = 0;
    for (long seed = 1; seed <= 10; seed++) {
      SimRun run = SimRun.of(seed, plan, TICKS, Scenario.DEFAULT.with(Chance.REQUEST, 0.3)).go();
      assertEquals(List.of(), run.violations(), run.describe());
      assertEquals(0, run.sim.metrics().total("path.profile.unknown"), run.describe());
      discarded += run.sim.metrics().total("requests.discarded");
    }
    assertTrue(discarded > 0, "no batch outlived its connection");
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
    NodeId victim = SPAWN_HOLDER;
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
    NodeId victim = SPAWN_HOLDER;
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
    NodeId victim = SPAWN_HOLDER;
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

  @Test
  void aPausedNodeKeepsItsLinkWhileItsRequestsTimeOut() {
    NodeId victim = SPAWN_HOLDER;
    FaultPlan plan =
        FaultPlan.reliable()
            .with(new FaultPlan.Paused(40, victim, true), new FaultPlan.Paused(70, victim, false));

    SimRun run = SimRun.of(13L, plan, TICKS).go();

    assertEquals(List.of(), run.violations(), run.describe());
    // The links stay up, so only the timeout notices, and the node catches up afterwards.
    assertEquals(0, run.out.dropped(DropReason.NODE_LOST), run.describe());
    assertTrue(run.out.dropped(DropReason.TIMEOUT) > 0, run.describe());
    assertTrue(Invariants.caughtUp(run.controller, run.sim.node(victim)), run.describe());
  }

  @Test
  void aRestartedControllerStartsEveryStreamOverAndItsOldAnswersAreIgnored() {
    FaultPlan plan = FaultPlan.reliable().with(new FaultPlan.ControllerRestarted(40));

    SimRun run = SimRun.of(13L, plan, TICKS, Scenario.DEFAULT.with(Chance.REQUEST, 0.3)).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(2, run.sim.incarnations(), run.describe());
    // Every node still holds the old run's area, which the new run cannot resume.
    assertEquals(
        2L * run.regions.nodes().size(),
        run.sim.metrics().total("world.resnapshot"),
        run.describe());
    assertEquals(0, run.sim.metrics().total("world.resume"), run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(Invariants.caughtUp(run.controller, node), node.id() + " " + run.describe());
    }
  }

  /**
   * After a world frame is given up on, the stream starts over and its holders hold the region as
   * air until its chunks arrive again, so an answer searched meanwhile must not be accepted. The
   * controller sees that from the chunk's residency, not from the sequence it had at admission.
   */
  @Test
  void anAnswerSearchedAfterTheStreamStartedOverIsNotAccepted() {
    long behind = 0;
    for (long seed = 1; seed <= 10; seed++) {
      FaultPlan plan = FaultPlan.reliable().withOffloadFailureRate(0.6);
      SimRun run = SimRun.of(seed, plan, TICKS, Scenario.DEFAULT.with(Chance.REQUEST, 0.3)).go();
      assertEquals(List.of(), run.violations(), run.describe());
      if (run.sim.metrics().total("world.restarted") > 0) {
        behind += run.sim.metrics().total("path.response.behind");
      }
      assertEquals(0, run.out.answeredOverAir(), run.describe());
    }
    assertTrue(behind > 0, "no answer was ever searched while its stream started over");
  }

  @Test
  void aWorldFrameWhoseEncodeKeepsFailingStartsItsStreamOver() {
    long restarts = 0;
    for (long seed = 1; seed <= 5; seed++) {
      // Encodes fail most of the time until the run settles, so some fail every try.
      FaultPlan plan = FaultPlan.reliable().withOffloadFailureRate(0.6);
      SimRun run = SimRun.of(seed, plan, TICKS).go();
      assertEquals(List.of(), run.violations(), run.describe());
      restarts += run.sim.metrics().total("world.restarted");
    }
    assertTrue(restarts > 0, "no world frame was ever given up on");
  }

  @Test
  void aRegionSentInFullIsReadOverSeveralTicks() {
    // The spawn chunks and three players' views, many times one tick's read budget.
    SimRun run =
        SimRun.of(
                5L,
                FaultPlan.reliable(),
                TICKS,
                Topology.DEFAULT.withRegionsPerSide(3),
                Scenario.DEFAULT.with(Count.PLAYERS, 3))
            .go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(run.sim.metrics().total("world.chunks.deferred") > 0, run.describe());
    assertTrue(run.sim.checkpointsCompared() > 0, run.describe());
  }

  private static List<NodeId> nodeIds() {
    return IntStream.range(0, Topology.DEFAULT.nodeCount()).mapToObj(NodeId::of).toList();
  }
}
