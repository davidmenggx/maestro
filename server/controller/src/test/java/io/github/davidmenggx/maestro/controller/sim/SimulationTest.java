package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.core.NodeHealth;
import io.github.davidmenggx.maestro.controller.core.NodeLinks;
import io.github.davidmenggx.maestro.controller.core.WorldStreams;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.RequestTrace;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Chance;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.ArrayList;
import java.util.Collections;
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

  /** How long that node stays away: several replay windows, past the resume point. */
  private static final long AWAY_TICKS = 4L * WorldStreams.RETENTION_FRAMES;

  /** Quiet ticks after the partition ends, for the new snapshot to arrive and the run to settle. */
  private static final long TAIL_TICKS = 50;

  /** Tick at which the control channel stalls, before profiles register. */
  private static final long STALL_TICK = 2;

  /** Long enough for several requests to name a profile that is still held behind the stall. */
  private static final long STALL_TICKS = 15;

  /** Four workers serve each tick's few requests at once; a batch leaves a tick before due. */
  private static final Scenario EDGE_SCENARIO =
      Scenario.DEFAULT.with(Count.WORKERS, 4).with(Count.SERVER_BUSY_PERCENT, 100);

  /** Holds the origin's region, where the spawn chunks keep the world loaded. */
  private static final NodeId SPAWN_HOLDER = NodeId.of(0);

  /**
   * The default square with no spare, so a node that cannot answer keeps its region however long it
   * is out, and has no stand-in.
   */
  private static final Topology ALONE = Topology.DEFAULT.withNodes(4).withSpares(0);

  /** One node holding a one-cell square, with no spare to relieve it. */
  private static final Topology LONE_NODE =
      Topology.DEFAULT.withCells(1).withNodes(1).withSpares(0);

  /** Block changes per tick, enough that most regions see one on most ticks. */
  private static final int CHANGES_PER_TICK = 2 * Topology.DEFAULT.nodeCount();

  @Test
  void sameSeedReplaysIdentically() {
    // Identity hash codes differ between runs, so identity-ordered collections break the digest.
    SimRun first = SimRun.of(7L, FaultPlan.reliable(), TICKS).go();
    SimRun second = SimRun.of(7L, FaultPlan.reliable(), TICKS).go();

    assertEquals(first.sim.digest().value(), second.sim.digest().value(), first.describe());
    assertEquals(first.out.results(), second.out.results());
    assertEquals(first.sim.metrics().counters(), second.sim.metrics().counters());
  }

  @Test
  void eachChunkIsSentOnceToEachAreaHoldingItThenStreamedAsDeltas() {
    SimRun run = SimRun.of(3L, FaultPlan.reliable(), TICKS).go();

    // A chunk in a halo is in the areas of every region beside it.
    long areaChunks = 0;
    for (int i = 0; i < run.world.loadedCount(); i++) {
      long chunk = run.world.loadedChunk(i);
      areaChunks +=
          run.regions()
              .reaching((int) (chunk >> 32) * Chunks.SIZE, (int) chunk * Chunks.SIZE)
              .size();
    }
    assertEquals(run.chunkCount(), run.world.loadedCount(), run.describe());
    assertEquals(
        areaChunks, run.sim.metrics().counter("offload.submitted", "job", "chunk"), run.describe());
    assertTrue(
        run.sim.metrics().counter("offload.submitted", "job", "delta")
            > run.regions().regions().size(),
        run.describe());
    // A region where no chunk was ever loaded has nothing to stream.
    Set<Integer> loaded = new TreeSet<>();
    for (int i = 0; i < run.world.loadedCount(); i++) {
      long chunk = run.world.loadedChunk(i);
      Region region =
          run.regions().regionAt((int) (chunk >> 32) * Chunks.SIZE, (int) chunk * Chunks.SIZE);
      if (region != null) {
        loaded.add(region.id());
      }
    }
    assertFalse(loaded.isEmpty(), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      if (loaded.contains(run.regions().regionOf(node.id()))) {
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
    // Each answer's latency is recorded once, under its node and whether its mob hunted.
    long labeled = 0;
    for (NodeId node : run.fleet) {
      for (String hunting : List.of("true", "false")) {
        labeled +=
            run.sim
                .metrics()
                .recorded("path.latency.ticks", "node", node.toString(), "hunting", hunting)
                .size();
      }
    }
    assertEquals(run.out.answered(), labeled, run.describe());
  }

  /** Each way takes the most at which one request at the highest cost is still answered in time. */
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

  /** The negative control: a little past the edge, every answer is a tick late. */
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
    // A lost request times out and counts as a miss, so a lossy run skips the SLO.
    SimRun run = SimRun.of(3L, FaultPlan.reliable().withDropRate(0.05), TICKS).go();

    assertFalse(run.fitsCapacity(), run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  @Test
  void aDuplicatedRequestIsAnsweredOnceByTheController() {
    SimRun run = SimRun.of(5L, FaultPlan.reliable().withDuplicateRate(0.4), TICKS).go();

    // A retry gets identical bytes, so the controller must discard the extra responses.
    assertTrue(run.sim.nodes().stream().mapToLong(ModeledNode::duplicateRequests).sum() > 0);
    assertTrue(run.sim.metrics().counter("path.response.stale") > 0, run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  @Test
  void everyNodeIsToldAProfileBeforeItIsAskedToUseIt() {
    SimRun run = SimRun.of(17L, FaultPlan.reliable(), TICKS).go();

    // Registration costs one request per profile.
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
    // The stall holds back early profile registrations while requests naming them arrive over UDP.
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
    // Each was dropped at once, with the registering request's reason.
    assertTrue(run.out.dropped(DropReason.CLASS_UNREGISTERED) >= unknown, run.describe());
    assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
    assertTrue(run.fleetCounter("transport.held", Channel.CONTROL) > 0, run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
  }

  @Test
  void aFailedEncodeIsRetriedUnderTheSameSequence() {
    SimRun run = SimRun.of(41L, FaultPlan.reliable().withOffloadFailureRate(0.3), TICKS).go();

    // A retry reuses the frame and sequence, so the stream has no gap.
    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(run.sim.metrics().total("offload.failed") > 0, run.describe());
    // Each failure is retried, unless it was the last attempt.
    assertEquals(
        run.sim.metrics().total("offload.failed"),
        run.sim.metrics().total("offload.retried") + run.sim.metrics().total("offload.abandoned"),
        run.describe());
    assertTrue(run.sim.metrics().counter("offload.retried", "job", "chunk") > 0, run.describe());
    assertTrue(run.sim.metrics().counter("offload.retried", "job", "delta") > 0, run.describe());
    for (NodeId holder : run.regions().nodes()) {
      assertTrue(
          Invariants.caughtUp(run.controller, run.sim.node(holder)), holder + " " + run.describe());
    }
  }

  @Test
  void aRequestBatchThatOutlivesItsConnectionIsNotSent() {
    // Encodes fail often, so a batch built before the node dies may still be retrying after it
    // returns. Its profiles are not registered on the new link, so it must not be sent.
    NodeId victim = NodeId.of(0);
    long back = 2 * NodeLinks.RECONNECT_TICKS;
    FaultPlan plan =
        FaultPlan.reliable()
            .withOffloadFailureRate(0.7)
            .with(
                new FaultPlan.NodeDied(back - 1, victim),
                new FaultPlan.NodeRecovered(back, victim));

    long discarded = 0;
    for (long seed = 1; seed <= 30; seed++) {
      SimRun run = SimRun.of(seed, plan, TICKS, Scenario.DEFAULT.with(Chance.REQUEST, 0.3)).go();
      assertEquals(List.of(), run.violations(), run.describe());
      assertEquals(0, run.sim.metrics().total("path.profile.unknown"), run.describe());
      discarded += run.sim.metrics().total("requests.discarded");
    }
    assertTrue(discarded > 0, "no batch outlived its connection");
  }

  @Test
  void aStreamedRequestWhoseEveryEncodeFailsLeavesInItsNodesNextBatch() {
    // Encodes fail so often that some streamed request fails every attempt. Mobs ask seldom and
    // never cancel or leave, so a request left unsent would not be replaced by another.
    Scenario streamed =
        Scenario.DEFAULT
            .with(Count.STREAM, 1)
            .with(Chance.REQUEST, 0.05)
            .with(Chance.CANCEL, 0.0)
            .with(Chance.MOB_CHURN, 0.0);
    SimRun run =
        SimRun.of(41L, FaultPlan.reliable().withOffloadFailureRate(0.7), TICKS, streamed).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(
        run.sim.metrics().counter("offload.abandoned", "job", "request") > 0, run.describe());
    // Each went out with a batch instead, unless its mob asked again first.
    for (RequestTrace trace : run.sim.tracing().traces()) {
      if (trace.encodingNanos() != RequestTrace.NEVER
          && !trace.outcome().equals(DropReason.SUPERSEDED.name())) {
        assertTrue(trace.sentNanos() != RequestTrace.NEVER, trace + " " + run.describe());
      }
    }
    assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
  }

  @Test
  void aChunkThatCannotBeReadIsSentOnceItCanBeOrClearedOnceItLeaves() {
    List<WorldEvent> script = new ArrayList<>();
    for (int x = 0; x < 3; x++) {
      for (int z = 0; z < 3; z++) {
        script.add(new WorldEvent.ChunkLoaded(0, x, z));
      }
    }
    // Neither can be read at first. One stays and can be read again; the other leaves.
    script.add(new WorldEvent.ChunkUnloaded(40, 2, 2));
    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), 120, script);
    ((ScriptedWorkload) run.workload).unreadable(1, 1, 0, 60).unreadable(2, 2, 0, 60);
    run.go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(run.sim.metrics().total("world.chunks.unreadable") > 0, run.describe());
    for (Region region : run.regions().regions()) {
      assertTrue(run.controller.regionSettled(region.id()), region + " " + run.describe());
    }
    for (NodeId holder : run.regions().nodes()) {
      ModeledNode node = run.sim.node(holder);
      assertTrue(Invariants.caughtUp(run.controller, node), holder + " " + run.describe());
      assertEquals(List.of(), Invariants.checkAgainstFreshSnapshot(run, node), run.describe());
    }
  }

  @Test
  void aHolderOfAnAreaWithFewerChunksThanAreLoadedIsSentEveryOneOfThem() {
    // Every chunk of the world is loaded, more than any region's area holds.
    Topology topology = Topology.DEFAULT;
    int minChunk = Chunks.of(topology.min());
    List<WorldEvent> script = new ArrayList<>();
    for (int x = minChunk; x < minChunk + topology.chunksPerSide(); x++) {
      for (int z = minChunk; z < minChunk + topology.chunksPerSide(); z++) {
        script.add(new WorldEvent.ChunkLoaded(0, x, z));
      }
    }
    // A holder dies after its area is sent, and is sent it afresh when it returns.
    NodeId victim = NodeId.of(0);
    FaultPlan plan =
        FaultPlan.reliable()
            .with(new FaultPlan.NodeDied(200, victim), new FaultPlan.NodeRecovered(202, victim));

    SimRun run = SimRun.scripted(1L, plan, 450, topology, Scenario.DEFAULT, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    for (Region region : run.regions().regions()) {
      int areaChunks = region.areaSizeX() / Chunks.SIZE * (region.areaSizeZ() / Chunks.SIZE);
      assertTrue(run.world.loadedCount() > areaChunks, run.describe());
    }
    assertTrue(
        run.sim.metrics().counter("world.resnapshot", "node", victim.toString()) > 0,
        run.describe());
    for (NodeId holder : run.regions().nodes()) {
      ModeledNode node = run.sim.node(holder);
      assertTrue(Invariants.caughtUp(run.controller, node), holder + " " + run.describe());
      assertEquals(List.of(), Invariants.checkAgainstFreshSnapshot(run, node), run.describe());
    }
  }

  @Test
  void saturationIsRejectedBeforeSendingRatherThanMissedLater() {
    SimRun run = SimRun.of(SimRun.baseSeed(), FaultPlan.reliable(), TICKS, Scenario.SATURATED).go();

    assertTrue(run.out.dropped(DropReason.SHED) > 0, run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
    // Requests the controller gave up on still counted against their node.
    assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
    // A node here cannot serve even its assumed capacity in a tick at worst cost, so not even what
    // is admitted is held to the 3-tick SLO.
    assertFalse(run.heldToTheSlo(), run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  /**
   * Shedding exists for this case: far more demand than the node serves, yet what it admits is
   * answered within the SLO, at an assumed capacity the node serves within a tick.
   */
  @Test
  void anOverloadedNodeMeetsTheSloForWhatItAdmits() {
    SimRun run = SimRun.of(3L, FaultPlan.reliable(), 400, LONE_NODE, overloaded(75)).go();

    assertFalse(run.fitsCapacity(), run.describe());
    assertTrue(run.admissionFits(), run.describe());
    assertTrue(run.heldToTheSlo(), run.describe());
    assertTrue(run.out.dropped(DropReason.SHED) > run.out.answered(), run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  /**
   * A node serving half the assumed capacity is taken at the rate its searches show, so admission
   * keeps within the SLO after the first few seconds. Taken at the assumed rate, p99 is a tick
   * over.
   */
  @Test
  void aNodeSlowerThanAssumedIsTakenAtItsMeasuredRate() {
    Scenario overestimated = overloaded(200);
    SimRun run = SimRun.of(3L, FaultPlan.reliable(), 2000, LONE_NODE, overestimated).go();

    String node = run.fleet.get(0).toString();
    assertEquals(
        overestimated.nodeCapacity(),
        run.sim.metrics().gauge("node.capacity", "node", node),
        run.describe());
    assertTrue(overestimated.assumedCapacity() > overestimated.nodeCapacity());
    assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
    assertEquals(
        List.of(),
        Invariants.checkSlo(run.out.latencies(), run.out.admittedMisses()),
        run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  /**
   * A server that works all tick hands over before its tick-end batch is encoded, so the batch
   * leaves a hand-over late. It still leaves before the next tick's requests, which must not wait
   * behind it: one request a tick fits even a share below one of a node serving two.
   */
  @Test
  void aBatchStillEncodingDoesNotCountAheadOfTheNextTicksRequests() {
    Scenario busy =
        Scenario.DEFAULT
            .with(Count.SERVER_BUSY_PERCENT, 100)
            .with(Count.WORKERS, 1)
            .with(Count.WORKER_CAPACITY, 2)
            .with(Count.COST_SPREAD_PERCENT, 0);
    Pos here = new Pos(20, 40, 20);
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.ChunkLoaded(0, Chunks.of(here.x()), Chunks.of(here.z())));
    script.add(new WorldEvent.PlayerMoved(0, 1, here));
    // Four mobs taking turns, so each waits for its answer before it asks again.
    for (int mob = 1; mob <= 4; mob++) {
      script.add(new WorldEvent.MobSpawned(0, mob, here));
    }
    WorldEvent.Search search = new WorldEvent.Search(0, 16f, 256, 3);
    for (long tick = 10; tick < 100; tick++) {
      // Not hunting, so its share is below one.
      script.add(
          new WorldEvent.PathRequested(
              tick, 1 + (int) (tick % 4), (short) 1, here, here, search, false));
    }

    SimRun run = SimRun.scripted(3L, FaultPlan.reliable(), 120, LONE_NODE, busy, script).go();

    assertEquals(0, run.out.dropped(DropReason.SHED), run.describe());
    assertTrue(run.out.answered() > 80, run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  /**
   * Saturation on one node that serves four requests a tick, each at the same cost, which the
   * controller assumes is {@code estimatePercent} of that.
   */
  private static Scenario overloaded(int estimatePercent) {
    return Scenario.SATURATED
        .with(Count.WORKER_CAPACITY, 4)
        .with(Count.COST_SPREAD_PERCENT, 0)
        .with(Count.CAPACITY_ESTIMATE_PERCENT, estimatePercent);
  }

  @Test
  void aSaturatedNodeDropsTheRequestsItsMobsNoLongerWant() {
    SimRun run = SimRun.of(SimRun.baseSeed(), FaultPlan.reliable(), TICKS, Scenario.SATURATED).go();

    assertEquals(List.of(), run.violations(), run.describe());
    // Mobs ask again behind a backlog, so cancels reach requests the node has not started.
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
        first.out.dropped(DropReason.NODE_LOST) + first.out.dropped(DropReason.UNAVAILABLE) > 0,
        first.describe());
    // The stream restarts instead of resuming into a gap, and must equal a fresh recomputation.
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
    // The reset shows as the link lost once and opened again.
    assertEquals(
        1,
        run.sim.metrics().counter("node.link", "node", victim.toString(), "event", "lost"),
        run.describe());
    assertEquals(
        2,
        run.sim.metrics().counter("node.link", "node", victim.toString(), "event", "connected"),
        run.describe());
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
                ALONE,
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
        run.regions().nodes().stream()
            .anyMatch(
                node ->
                    run.sim
                            .metrics()
                            .percentile("node.queue.reported", 1.0, "node", node.toString())
                        > 0),
        run.describe());
    long searched = 0;
    for (ModeledNode node : run.sim.nodes()) {
      String label = node.id().toString();
      // Each heartbeat sets the node's depth gauge to the depth it reported.
      assertEquals(
          run.sim.metrics().recorded("node.queue.reported", "node", label),
          run.sim.metrics().gaugeValues("node.queue.depth", "node", label),
          label);
      // Heartbeats count the searches finished since the last, so none is counted twice.
      long searches = run.sim.metrics().counter("node.searches", "node", label);
      assertTrue(searches <= node.servedRequests(), label + " " + run.describe());
      searched += searches;
    }
    assertTrue(searched > 0, run.describe());
  }

  @Test
  void aPartitionedNodeIsTakenOutOfServiceAndProbedBackOnceItAnswers() {
    NodeId victim = SPAWN_HOLDER;
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Partitioned(40, victim, true),
                new FaultPlan.Partitioned(100, victim, false));

    SimRun run = SimRun.of(13L, plan, 200, ALONE, Scenario.DEFAULT).go();

    assertEquals(List.of(), run.violations(), run.describe());
    // No reset tells the controller. Unanswered requests do, so the rest are dropped or
    // refused and their mobs search on the server.
    assertEquals(1, opened(run, victim), run.describe());
    assertTrue(run.out.dropped(DropReason.NODE_LOST) > 0, run.describe());
    assertTrue(
        run.sim.metrics().counter("path.rejected", "reason", "unavailable") > 0, run.describe());
    assertTrue(run.sim.failOpenJudged() > 0, run.describe());
    // A probe during the partition goes unanswered, and one after it brings the node back.
    assertTrue(circuit(run, victim, "probing") >= 2, run.describe());
    assertEquals(1, circuit(run, victim, "closed"), run.describe());
    assertEquals(1, run.sim.metrics().gauge("node.in_service", "node", victim.toString()));
    // Frames are held, not lost, so the stream has no gap once the partition ends.
    assertTrue(run.fleetCounter("transport.held", Channel.WORLD) > 0, run.describe());
    assertTrue(Invariants.caughtUp(run.controller, run.sim.node(victim)), run.describe());
  }

  @Test
  void aPausedNodeIsSuspectedFromItsHeartbeatsAndServedAgainOnceItResumes() {
    NodeId victim = SPAWN_HOLDER;
    // Long enough for heartbeats to be overdue even with no request in flight.
    FaultPlan plan =
        FaultPlan.reliable()
            .with(new FaultPlan.Paused(40, victim, true), new FaultPlan.Paused(140, victim, false));

    SimRun run = SimRun.of(13L, plan, 220, ALONE, Scenario.DEFAULT.with(Chance.REQUEST, 0.0)).go();

    assertEquals(List.of(), run.violations(), run.describe());
    // The links stay up, so only the heartbeats show it.
    assertEquals(
        1, run.sim.metrics().counter("node.suspected", "node", victim.toString()), run.describe());
    assertEquals(0, opened(run, victim), run.describe());
    assertEquals(1, run.sim.metrics().gauge("node.in_service", "node", victim.toString()));
    assertTrue(Invariants.caughtUp(run.controller, run.sim.node(victim)), run.describe());
    // Its phi crossed the threshold while paused and fell back once it resumed.
    List<Double> phi = run.sim.metrics().gaugeValues("node.phi", "node", victim.toString());
    assertTrue(Collections.max(phi) >= NodeHealth.PHI_THRESHOLD, run.describe());
    assertTrue(phi.get(phi.size() - 1) < NodeHealth.PHI_THRESHOLD, run.describe());
  }

  @Test
  void aPausedNodeWithRequestsInFlightIsTakenOutBeforeItsHeartbeatsAreOverdue() {
    NodeId victim = SPAWN_HOLDER;
    FaultPlan plan =
        FaultPlan.reliable()
            .with(new FaultPlan.Paused(40, victim, true), new FaultPlan.Paused(70, victim, false));

    SimRun run = SimRun.of(13L, plan, TICKS, ALONE, Scenario.DEFAULT).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, opened(run, victim), run.describe());
    assertEquals(
        0, run.sim.metrics().counter("node.suspected", "node", victim.toString()), run.describe());
    assertTrue(run.out.dropped(DropReason.NODE_LOST) > 0, run.describe());
    assertTrue(Invariants.caughtUp(run.controller, run.sim.node(victim)), run.describe());
  }

  @Test
  void aNodeWhoseDatagramsAreLostIsAvoidedThoughItsLinkAndHeartbeatsCarryOn() {
    NodeId victim = SPAWN_HOLDER;
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Blackholed(40, victim, true),
                new FaultPlan.Blackholed(100, victim, false));

    SimRun run = SimRun.of(17L, plan, 200, ALONE, Scenario.DEFAULT).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(
        0, run.sim.metrics().counter("node.suspected", "node", victim.toString()), run.describe());
    assertEquals(1, opened(run, victim), run.describe());
    assertTrue(
        run.sim.metrics().counter("path.rejected", "reason", "unavailable") > 0, run.describe());
    assertEquals(1, circuit(run, victim, "closed"), run.describe());
    assertTrue(run.sim.failOpenJudged() > 0, run.describe());
    // Its link never broke, so it kept its stream and nothing was sent twice.
    assertEquals(0, run.sim.metrics().total("world.resnapshot") - nodes(run), run.describe());
  }

  @Test
  void aServerThatStallsTakesNoNodeOutOfService() {
    // Heartbeats keep arriving while the server hears nothing, and are read by when they came.
    FaultPlan plan =
        FaultPlan.reliable()
            .with(new FaultPlan.ServerStalled(40, 3 * 20 * Simulation.STEPS_PER_TICK));

    SimRun run = SimRun.of(17L, plan, 200, ALONE, Scenario.DEFAULT).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(run.fitsCapacity(), run.describe());
    assertFalse(run.heldToTheSlo(), run.describe());
    assertEquals(0, run.sim.metrics().total("node.suspected"), run.describe());
    assertEquals(0, run.sim.metrics().total("node.quiet"), run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
  }

  @Test
  void aServerThatFallsBehindTakesNoNodeOutOfService() {
    SimRun run =
        SimRun.of(
                17L, FaultPlan.reliable(), 200, ALONE, Scenario.DEFAULT.with(Chance.SLOW_TICK, 0.2))
            .go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(run.fitsCapacity(), run.describe());
    assertEquals(0, run.sim.metrics().total("node.suspected"), run.describe());
    assertEquals(0, run.sim.metrics().total("node.quiet"), run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
  }

  @Test
  void aRestartedControllerStartsEveryStreamOverAndItsOldAnswersAreIgnored() {
    FaultPlan plan = FaultPlan.reliable().with(new FaultPlan.ControllerRestarted(40));

    SimRun run = SimRun.of(13L, plan, TICKS, Scenario.DEFAULT.with(Chance.REQUEST, 0.3)).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(2, run.sim.incarnations(), run.describe());
    // Every node still holds the old run's area, which the new run cannot resume.
    assertEquals(
        2L * run.regions().nodes().size(),
        run.sim.metrics().total("world.resnapshot"),
        run.describe());
    assertEquals(0, run.sim.metrics().total("world.resume"), run.describe());
    assertTrue(run.out.answered() > 0, run.describe());
    for (NodeId holder : run.regions().nodes()) {
      assertTrue(
          Invariants.caughtUp(run.controller, run.sim.node(holder)), holder + " " + run.describe());
    }
  }

  /**
   * After a world frame is given up on, holders hold the region as air until its chunks return, so
   * an answer searched meanwhile must not be accepted. The controller reads that from residency.
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
                Topology.DEFAULT.withCells(3).withNodes(10),
                Scenario.DEFAULT.with(Count.PLAYERS, 3))
            .go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(run.sim.metrics().total("world.chunks.deferred") > 0, run.describe());
    assertTrue(run.sim.checkpointsCompared() > 0, run.describe());
  }

  private static List<NodeId> nodeIds() {
    return IntStream.range(0, Topology.DEFAULT.nodeCount()).mapToObj(NodeId::of).toList();
  }

  /** Times the node's request path went quiet and was taken out of service. */
  private static long opened(SimRun run, NodeId node) {
    return run.sim.metrics().counter("node.quiet", "node", node.toString());
  }

  private static long circuit(SimRun run, NodeId node, String state) {
    return run.sim.metrics().counter("node.circuit", "node", node.toString(), "state", state);
  }

  /** A run's fleet first greets with nothing held, so each node is resnapshotted once. */
  private static long nodes(SimRun run) {
    return run.sim.nodes().size();
  }
}
