package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.core.Priority;
import io.github.davidmenggx.maestro.controller.core.RequestTracker;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import maestro.fbs.PathNode;
import maestro.fbs.PathResponse;
import maestro.fbs.PathType;
import maestro.fbs.ResponseFrame;
import maestro.fbs.ResponseMsg;
import org.junit.jupiter.api.Test;

/** Boundary cases from a scripted trace. Each checks a request gets exactly one callback. */
class WorldBoundaryTest {

  private static final long TICKS = 30;
  private static final short MOB_CLASS = 1;
  private static final int MOB = 1;
  private static final int WARMUP_MOB = 2;
  private static final int OTHER_MOB = 3;
  private static final int CROWD = 100;
  private static final int CROWD_SIZE = 20;
  private static final short OTHER_CLASS = 2;
  private static final int PLAYER = 1;

  /**
   * The tick the probe of a node whose path went quiet at tick 16 leaves, after the first backoff,
   * and is first sent again.
   */
  private static final long PROBE_TICK = 37;

  /** A node that serves a request in a twelfth of a tick, always. */
  private static final Scenario QUICK =
      Scenario.DEFAULT
          .with(Scenario.Count.WORKER_CAPACITY, 12)
          .with(Scenario.Count.COST_SPREAD_PERCENT, 0);

  /** Corner of the region of the default grid on the positive side of the origin's. */
  private static final int HERE_REGION_MIN = RegionLayout.cellMin(1);

  /** In the middle of that region, so a search from it reads nothing past it. */
  private static final Pos HERE = new Pos(HERE_REGION_MIN + 132, 40, HERE_REGION_MIN + 132);

  /** In the origin's region. */
  private static final Pos THERE = new Pos(-20, 40, -20);

  private static final WorldEvent.Search SEARCH = new WorldEvent.Search(0, 16f, 256, 3);

  /** Registration spends a request without an id, so the first one admitted gets 1. */
  private static final long FIRST_REQUEST = 1;

  /** Holds the region holding {@code HERE}, the last of the default grid, alone. */
  private static final NodeId OWNER = NodeId.of(3);

  @Test
  void aMobRemovedWhileWaitingIsDroppedExactlyOnce() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.MobRemoved(3, MOB));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(2, run.controller.requestsIssued(), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.MOB_GONE), run.describe());
  }

  @Test
  void aRequestForAnUnknownMobIsDropped() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.PathRequested(2, 99, MOB_CLASS, HERE, THERE, SEARCH, true));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.MOB_GONE), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  @Test
  void aSecondRequestForOneMobSupersedesTheFirst() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.PathRequested(3, MOB, MOB_CLASS, HERE, HERE, SEARCH, true));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(3, run.controller.requestsIssued(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.SUPERSEDED), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
  }

  @Test
  void aRequestIsDroppedWhileNoNodeHoldsTheRegion() {
    // No chunks load, so no region can be snapshotted and no node can answer.
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.UNAVAILABLE), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertFalse(node.hasState(), run.describe());
    }
  }

  @Test
  void aBlockChangeBeforeTheSnapshotIsAbsorbedByIt() {
    // The change happens while the region is unreadable, so the snapshot carries it, not a delta.
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.BlockChanged(2, HERE, (byte) PathType.WATER, 0));
    script.addAll(chunkLoads(5));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().counter("offload.submitted", "job", "delta"), run.describe());
    // Each loaded chunk is the first and only frame of its region. The spare holds nothing.
    List<NodeId> loaded = new ArrayList<>(holders(run, THERE));
    loaded.addAll(holders(run, HERE));
    for (ModeledNode node : run.sim.nodes()) {
      boolean holds = run.regions().regionOf(node.id()) != Regions.NONE;
      assertEquals(holds, node.assigned(), run.describe());
      assertEquals(loaded.contains(node.id()) ? 1 : 0, node.lastSequence(), run.describe());
    }
  }

  @Test
  void aCanceledRequestIsDroppedRatherThanAnswered() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.PathCanceled(3, MOB));
    // A cancel for a mob with nothing outstanding changes nothing.
    script.add(new WorldEvent.PathCanceled(8, MOB));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(2, run.controller.requestsIssued(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.CANCELED), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  /** A chunk that leaves is air on the node, as in vanilla, so only it is resent. */
  @Test
  void anUnloadedChunkIsClearedOnTheNodeAndSentAgainWhenItReturns() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.ChunkUnloaded(6, Chunks.of(HERE.x()), Chunks.of(HERE.z())));
    script.add(new WorldEvent.PathRequested(8, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.ChunkLoaded(10, Chunks.of(HERE.x()), Chunks.of(HERE.z())));
    script.add(new WorldEvent.PathRequested(14, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();
    SimRun unchurned =
        SimRun.scripted(
                1L,
                FaultPlan.reliable(),
                TICKS,
                script.stream()
                    .filter(
                        event ->
                            !(event instanceof WorldEvent.ChunkUnloaded)
                                && !(event instanceof WorldEvent.ChunkLoaded loaded
                                    && loaded.tick() == 10))
                    .toList())
            .go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(4, run.controller.requestsIssued(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.UNAVAILABLE), run.describe());
    assertEquals(2, run.out.answered(), run.describe());
    // One frame to clear the chunk and one to fill it again, with no new snapshot.
    assertEquals(
        unchurned.sim.metrics().counter("offload.submitted", "job", "chunk") + 2,
        run.sim.metrics().counter("offload.submitted", "job", "chunk"),
        run.describe());
    assertEquals(
        run.regions().nodes().size(), run.sim.metrics().total("world.resnapshot"), run.describe());
  }

  @Test
  void theFirstRequestNamingAProfileRegistersItAndIsGivenUp() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.PathRequested(6, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    // A second profile costs its own request, because registration is per profile.
    script.add(new WorldEvent.PathRequested(10, MOB, OTHER_CLASS, HERE, THERE, SEARCH, true));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(3, run.controller.requestsIssued(), run.describe());
    assertEquals(2, run.out.dropped(DropReason.CLASS_UNREGISTERED), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
    assertEquals(2, run.sim.metrics().total("mobclass.registered"), run.describe());
  }

  /** A profile the world cannot produce is a controller bug, not a node bug. */
  /** A search reading past its region into the halo is sent, since the halo holds real blocks. */
  @Test
  void aSearchThatReadsIntoItsHaloIsSent() {
    int pastTheRegion = HERE.x() - HERE_REGION_MIN;
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(
        new WorldEvent.PathRequested(
            3,
            MOB,
            MOB_CLASS,
            HERE,
            THERE,
            new WorldEvent.Search(0, pastTheRegion, 16 * pastTheRegion, 3),
            true));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.out.dropped(DropReason.NO_NODE), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
  }

  /**
   * A chunk within a halo of a region's edge is in its neighbor's area too, so it and its changes
   * go to both regions' holders.
   */
  @Test
  void aChunkInAHaloGoesToEveryRegionWhoseAreaHoldsIt() {
    // In the origin's region, a chunk short of the edge with the region east of it.
    Pos nearTheEdge = new Pos(RegionLayout.cellMin(1) - 8, 40, -20);
    List<WorldEvent> script = new ArrayList<>();
    script.add(
        new WorldEvent.ChunkLoaded(0, Chunks.of(nearTheEdge.x()), Chunks.of(nearTheEdge.z())));
    script.add(new WorldEvent.BlockChanged(5, nearTheEdge, (byte) PathType.WATER, 0));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    List<NodeId> both = new ArrayList<>(holders(run, nearTheEdge));
    both.addAll(holders(run, new Pos(RegionLayout.cellMin(1), 40, -20)));
    assertEquals(2, both.size(), run.describe());
    for (NodeId holder : both) {
      ModeledNode node = run.sim.node(holder);
      // The chunk, then the change.
      assertEquals(2, node.lastSequence(), holder + " " + run.describe());
      assertEquals(
          List.of(), Invariants.checkAgainstFreshSnapshot(run, node), holder + run.describe());
    }
    assertEquals(2, run.sim.metrics().counter("offload.submitted", "job", "delta"), run.describe());
  }

  @Test
  void aRequestNamingAProfileTheWorldDoesNotHoldIsRefused() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, (short) 99, HERE, THERE, SEARCH, true));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertTrue(
        run.violations().stream().anyMatch(v -> v.contains("which the world does not hold")),
        "an unproducible profile went unreported: " + run.violations());
    assertEquals(1, run.out.dropped(DropReason.CLASS_UNREGISTERED), run.describe());
  }

  /**
   * A search may read into its region's halo, but one that could read past the area would see a
   * cliff, so it stays on the server.
   */
  @Test
  void aSearchThatCouldReadPastItsAreaIsNotSent() {
    int reachInside = HERE.x() - HERE_REGION_MIN + Region.HALO;
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(
        new WorldEvent.PathRequested(
            3,
            MOB,
            MOB_CLASS,
            HERE,
            THERE,
            new WorldEvent.Search(0, reachInside, 16 * reachInside, 3),
            true));
    script.add(new WorldEvent.PathRequested(4, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
    assertEquals(1, run.sim.metrics().counter("path.rejected", "reason", "reach"), run.describe());
    // The same mob, asking for a shorter search, is served.
    assertEquals(1, run.out.answered(), run.describe());
  }

  /** Encoding such a target would fail on every retry, so the request is never sent. */
  @Test
  void aRequestTheWireCannotCarryIsDroppedBeforeItIsEncoded() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(
        new WorldEvent.PathRequested(
            2, MOB, MOB_CLASS, HERE, new Pos(40_000, 40, 4), SEARCH, true));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
    assertEquals(0, run.sim.metrics().total("offload.retried"), run.describe());
  }

  @Test
  void aMalformedAnswerLeavesItsRequestOutstanding() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    // Ends before the node serves the request.
    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), 3, script).go();
    assertEquals(1, run.controller.outstandingCount(), run.describe());
    NodeId owner = holders(run, HERE).get(0);

    run.controller.onFrame(
        owner,
        Channel.REQUEST,
        answer(run.controller.run(), FIRST_REQUEST, 200),
        run.sim.env().clock().nowNanos());
    // A datagram that is not a frame at all, as from a sender that is not a node.
    run.controller.onFrame(
        owner,
        Channel.REQUEST,
        ByteBuffer.wrap(new byte[] {9, 0, 0, 0, 1}),
        run.sim.env().clock().nowNanos());
    assertEquals(1, run.controller.outstandingCount(), run.describe());
    assertEquals(2, run.sim.metrics().total("controller.malformed.frame"), run.describe());

    // A well-formed answer from a node the request did not go to.
    NodeId other = holders(run, THERE).get(0);
    run.controller.onFrame(
        other,
        Channel.REQUEST,
        answer(run.controller.run(), FIRST_REQUEST, PathType.WALKABLE),
        run.sim.env().clock().nowNanos());
    assertEquals(1, run.controller.outstandingCount(), run.describe());
    assertEquals(1, run.sim.metrics().total("path.response.misrouted"), run.describe());

    run.controller.onFrame(
        owner,
        Channel.REQUEST,
        answer(run.controller.run(), FIRST_REQUEST, PathType.WALKABLE),
        run.sim.env().clock().nowNanos());
    assertEquals(0, run.controller.outstandingCount(), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
  }

  /** The node answered before the chunk the search starts in reached it, so it searched air. */
  @Test
  void anAnswerSearchedBeforeItsStartChunkArrivedIsDropped() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), 3, script).go();
    NodeId owner = holders(run, HERE).get(0);

    run.controller.onFrame(
        owner,
        Channel.REQUEST,
        answer(run.controller.run(), FIRST_REQUEST, PathType.WALKABLE, 0),
        run.sim.env().clock().nowNanos());

    assertEquals(0, run.controller.outstandingCount(), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
  }

  /** The mob asks again while the first batch is in flight. It still counts against the node. */
  @Test
  void aSupersededRequestCountsAgainstItsNodeUntilItIsAnswered() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.PathRequested(3, MOB, MOB_CLASS, HERE, HERE, SEARCH, true));
    // A search takes the node a whole tick, so the run ends before the first answer arrives.
    Scenario slow =
        Scenario.DEFAULT
            .with(Scenario.Count.WORKER_CAPACITY, 1)
            .with(Scenario.Count.COST_SPREAD_PERCENT, 0);
    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), 4, slow, script).go();
    NodeId owner = holders(run, HERE).get(0);
    assertEquals(1, run.out.dropped(DropReason.SUPERSEDED), run.describe());
    assertEquals(2, run.controller.outstandingCount(), run.describe());

    run.controller.onFrame(
        owner,
        Channel.REQUEST,
        answer(run.controller.run(), FIRST_REQUEST, PathType.WALKABLE),
        run.sim.env().clock().nowNanos());
    assertEquals(1, run.controller.outstandingCount(), run.describe());
    assertEquals(0, run.out.answered(), run.describe());

    run.controller.onFrame(
        owner,
        Channel.REQUEST,
        answer(run.controller.run(), FIRST_REQUEST + 1, PathType.WALKABLE),
        run.sim.env().clock().nowNanos());
    assertEquals(0, run.controller.outstandingCount(), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
    assertEquals(List.of(), run.controller.selfCheck(), run.describe());
  }

  /** A request canceled in the tick it was made never leaves, so it costs the node nothing. */
  @Test
  void aRequestCanceledBeforeItsBatchIsBuiltIsNeverSent() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.PathCanceled(2, MOB));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.CANCELED), run.describe());
    assertEquals(0, run.sim.metrics().total("requests.frames"), run.describe());
  }

  /** The mob no longer wants the older answer, whether or not its new request is admitted. */
  @Test
  void aRepeatRequestReplacesTheOutstandingOneEvenWhenItIsRefused() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(
        new WorldEvent.PathRequested(
            3, MOB, MOB_CLASS, HERE, new Pos(40_000, 40, 4), SEARCH, true));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.SUPERSEDED), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  /**
   * Admission keeps answers within the SLO: a node serving four requests a tick takes four of a
   * crowd asking at once, the last behind a tick's work less itself.
   */
  @Test
  void admissionKeepsAnswersWithinTheSlo() {
    List<WorldEvent> script = prelude();
    int mobs = 20;
    for (int mob = 10; mob < 10 + mobs; mob++) {
      script.add(new WorldEvent.MobSpawned(0, mob, HERE));
      script.add(new WorldEvent.PathRequested(5, mob, MOB_CLASS, HERE, THERE, SEARCH, true));
    }
    // One worker serves exactly four requests a tick, as admission assumes.
    Scenario fourATick =
        Scenario.DEFAULT
            .with(Scenario.Count.WORKER_CAPACITY, 4)
            .with(Scenario.Count.WORKERS, 1)
            .with(Scenario.Count.COST_SPREAD_PERCENT, 0);

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, fourATick, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(4, run.out.answered(), run.describe());
    assertEquals(mobs - 4, run.out.dropped(DropReason.SHED), run.describe());
    assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
    assertTrue(
        run.out.latencies().stream().allMatch(ticks -> ticks < Priority.SLO_TICKS), run.describe());
  }

  /**
   * A request may wait behind its share of what its node serves in a tick, less itself: all of it
   * for a hunting mob beside a player, a twelfth less per 32 blocks to the nearest player, a sixth
   * less for a mob with no target, and never under half.
   */
  @Test
  void aRequestsShareShrinksWithDistanceFromAPlayerAndForAMobNotHunting() {
    Pos far = new Pos(HERE.x() + 96, HERE.y(), HERE.z());
    // Twelve a tick, so a share `s` admits `floor(11 s) + 1` of a crowd.
    assertEquals(12, admittedOfACrowd(HERE, true));
    assertEquals(10, admittedOfACrowd(HERE, false));
    assertEquals(9, admittedOfACrowd(far, true));
    assertEquals(7, admittedOfACrowd(far, false));
    assertEquals(6, admittedOfACrowd(null, true));
  }

  /**
   * How many of a crowd asking at once a node serving twelve requests a tick takes, with the one
   * player at {@code player}, or none if null.
   */
  private static int admittedOfACrowd(Pos player, boolean hunting) {
    List<WorldEvent> script = new ArrayList<>();
    for (WorldEvent event : prelude()) {
      if (!(event instanceof WorldEvent.PlayerMoved)) {
        script.add(event);
      }
    }
    if (player != null) {
      script.add(new WorldEvent.PlayerMoved(0, PLAYER, player));
    }
    int mobs = 20;
    for (int mob = 10; mob < 10 + mobs; mob++) {
      script.add(new WorldEvent.MobSpawned(0, mob, HERE));
      script.add(new WorldEvent.PathRequested(5, mob, MOB_CLASS, HERE, THERE, SEARCH, hunting));
    }
    Scenario twelveATick = QUICK.with(Scenario.Count.WORKERS, 1);

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, twelveATick, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(mobs - run.out.answered(), run.out.dropped(DropReason.SHED), run.describe());
    return run.out.answered();
  }

  /** A tick of requests to one node leaves in several datagram-sized frames, in any order. */
  @Test
  void aTickOfRequestsLeavesInDatagramSizedFrames() {
    List<WorldEvent> script = prelude();
    int mobs = 2 * Frames.MAX_REQUESTS_PER_FRAME + 1;
    for (int mob = 10; mob < 10 + mobs; mob++) {
      script.add(new WorldEvent.MobSpawned(0, mob, HERE));
      script.add(new WorldEvent.PathRequested(3, mob, MOB_CLASS, HERE, THERE, SEARCH, true));
    }
    // Sixty a tick, so the node takes them all.
    Scenario sixtyATick = QUICK.with(Scenario.Count.WORKERS, 5);

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, sixtyATick, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(mobs, run.out.answered(), run.describe());
    assertEquals(3, run.sim.metrics().total("requests.frames"), run.describe());
    // The frames may arrive in any order, so no request is sent again for waiting behind them.
    assertEquals(0, run.sim.metrics().total("path.resent"), run.describe());
    assertTrue(
        run.sim.metrics().percentile("frame.bytes", 1.0, "channel", Channel.REQUEST.name())
            <= Frames.MAX_REQUEST_FRAME_BYTES,
        run.describe());
  }

  /** A region with every chunk unloaded is all air on its node and on a fresh one alike. */
  @Test
  void aNodeWhoseChunksAreAllUnloadedMatchesAFreshOne() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.addAll(chunkUnloads(2));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(
        run.sim.nodes().stream().anyMatch(node -> Invariants.caughtUp(run.controller, node)),
        run.describe());
  }

  /** The chunk leaves after the change, so no delta may put the block back into it. */
  @Test
  void aBlockChangedInAChunkThatLeavesInTheSameTickIsNotSentAfterIt() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.BlockChanged(3, HERE, (byte) PathType.WATER, 0));
    script.add(new WorldEvent.ChunkUnloaded(3, Chunks.of(HERE.x()), Chunks.of(HERE.z())));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().counter("offload.submitted", "job", "delta"), run.describe());
    assertEquals(
        List.of(), Invariants.checkAgainstFreshSnapshot(run, run.sim.node(OWNER)), run.describe());
  }

  /** The chunk was sent as cleared, so a change reported in it must not be sent. */
  @Test
  void aBlockChangedInAChunkSentAsClearedIsNotSent() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.ChunkUnloaded(2, Chunks.of(HERE.x()), Chunks.of(HERE.z())));
    script.add(new WorldEvent.BlockChanged(5, HERE, (byte) PathType.WATER, 0));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().counter("offload.submitted", "job", "delta"), run.describe());
    assertEquals(
        List.of(), Invariants.checkAgainstFreshSnapshot(run, run.sim.node(OWNER)), run.describe());
  }

  /** The request leaves during the partition and is lost; a resend after it is answered. */
  @Test
  void aLostRequestIsSentAgainAndAnswered() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(4, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Partitioned(4, OWNER, true),
                new FaultPlan.Partitioned(6, OWNER, false));

    SimRun run = SimRun.scripted(1L, plan, TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
    assertTrue(run.sim.metrics().total("path.resent") > 0, run.describe());
    assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
  }

  @Test
  void aRequestIsSentAgainAFewTimesAtMostThenTimesOut() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(4, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Partitioned(4, OWNER, true),
                new FaultPlan.Partitioned(20, OWNER, false));

    SimRun run = SimRun.scripted(1L, plan, TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(RequestTracker.MAX_RESENDS, run.sim.metrics().total("path.resent"));
    assertEquals(1, run.out.dropped(DropReason.TIMEOUT), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  /**
   * One request goes unanswered, copies and all. A later one, sent once the first is past its
   * budget, is answered by its due tick, so it shows the request path works.
   */
  @Test
  void aRequestAnsweredByItsDueTickKeepsItsNodeInServiceAfterAnEarlierOneWasLost() {
    // The link is slow, so the owner holds the chunk a few ticks later than usual.
    List<WorldEvent> script = prelude(6);
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.MobSpawned(0, OTHER_MOB, HERE));
    script.add(new WorldEvent.PathRequested(10, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.PathRequested(25, OTHER_MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    // An answer takes more than a tick, so the later one comes in after the next tick starts.
    FaultPlan plan =
        new FaultPlan(
            600,
            600,
            0,
            0,
            0,
            List.of(
                new FaultPlan.Partitioned(10, OWNER, true),
                new FaultPlan.Partitioned(22, OWNER, false)));

    SimRun run = SimRun.scripted(1L, plan, 40, QUICK, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.TIMEOUT), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
    assertEquals(0, run.sim.metrics().total("node.quiet"), run.describe());
  }

  /**
   * A request canceled before it fell due may be dropped by its node, as here, so it shows nothing.
   * The only other unanswered request is then not enough to take the node out of service.
   */
  @Test
  void aRequestCanceledBeforeItFellDueShowsNothingAboutThePath() {
    List<WorldEvent> script = prelude();
    // Queued ahead of the canceled request, so the node still holds it when the cancel arrives.
    for (int i = 0; i < CROWD_SIZE; i++) {
      script.add(new WorldEvent.MobSpawned(0, CROWD + i, HERE));
      script.add(new WorldEvent.PathRequested(4, CROWD + i, MOB_CLASS, HERE, THERE, SEARCH, true));
    }
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(4, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.PathCanceled(5, MOB));
    script.add(new WorldEvent.MobSpawned(0, OTHER_MOB, HERE));
    script.add(new WorldEvent.PathRequested(17, OTHER_MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Partitioned(17, OWNER, true),
                new FaultPlan.Partitioned(30, OWNER, false));

    // The controller assumes twice the true capacity, so it lets the crowd queue more than a tick
    // of
    // work ahead of the canceled request.
    Scenario optimistic =
        QUICK.with(Scenario.Count.WORKERS, 1).with(Scenario.Count.CAPACITY_ESTIMATE_PERCENT, 200);
    SimRun run = SimRun.scripted(1L, plan, 40, optimistic, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.sim.node(OWNER).canceledRequests(), run.describe());
    assertEquals(CROWD_SIZE, run.out.answered(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.TIMEOUT), run.describe());
    assertEquals(0, run.sim.metrics().total("node.quiet"), run.describe());
  }

  /**
   * A streamed request leaves before the tick ends, so one canceled in the tick it was made is
   * canceled on its node, which drops it if it has not started it.
   */
  @Test
  void aStreamedRequestCanceledInItsTickIsCanceledOnItsNode() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(4, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.PathCanceled(4, MOB));
    // Paused, the node holds the request and the cancel that follows it, and handles both before
    // a worker can start the request.
    FaultPlan plan =
        FaultPlan.reliable()
            .with(new FaultPlan.Paused(4, OWNER, true), new FaultPlan.Paused(7, OWNER, false));

    SimRun run =
        SimRun.scripted(1L, plan, TICKS, Scenario.DEFAULT.with(Scenario.Count.STREAM, 1), script)
            .go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(
        1, run.sim.metrics().counter("path.cancels", "node", OWNER.toString()), run.describe());
    assertEquals(1, run.sim.node(OWNER).canceledRequests(), run.describe());
    assertEquals(0, run.sim.node(OWNER).servedRequests(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.CANCELED), run.describe());
  }

  /**
   * Two requests are lost, so the path goes quiet and the node leaves service. After the backoff a
   * probe leaves into the blackhole and is sent again; a copy sent once the blackhole ends is
   * answered and the node is back in service.
   */
  @Test
  void aProbeLostToABlackholeIsSentAgainUntilAnswered() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.MobSpawned(0, OTHER_MOB, HERE));
    script.add(new WorldEvent.PathRequested(4, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.PathRequested(4, OTHER_MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Blackholed(4, OWNER, true),
                new FaultPlan.Blackholed(PROBE_TICK + 1, OWNER, false));

    SimRun run = SimRun.scripted(1L, plan, PROBE_TICK + 20, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    String owner = OWNER.toString();
    assertEquals(1, run.sim.metrics().counter("node.quiet", "node", owner), run.describe());
    assertEquals(
        1,
        run.sim.metrics().counter("node.circuit", "node", owner, "state", "probing"),
        run.describe());
    assertEquals(
        1,
        run.sim.metrics().counter("node.circuit", "node", owner, "state", "closed"),
        run.describe());
    assertEquals(
        1,
        run.sim.metrics().counter("node.circuit", "node", owner, "state", "open"),
        run.describe());
    // Its check before the blackhole, and the one probe after it.
    assertEquals(2, run.sim.metrics().counter("node.probe.sent", "node", owner), run.describe());
    assertEquals(2, run.sim.node(OWNER).servedProbes(), run.describe());
    assertTrue(run.controller.inService(OWNER), run.describe());
  }

  /** Loads the world and registers {@code MOB_CLASS}, which costs one request per script. */
  private static List<WorldEvent> prelude() {
    return prelude(2);
  }

  /** As above, registering at {@code tick}, by which the owner must hold the chunk at HERE. */
  private static List<WorldEvent> prelude(long tick) {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    // A player beside the mobs, so a hunting mob's request has the whole tick budget.
    script.add(new WorldEvent.PlayerMoved(0, PLAYER, HERE));
    script.add(new WorldEvent.MobSpawned(0, WARMUP_MOB, HERE));
    script.add(
        new WorldEvent.PathRequested(tick, WARMUP_MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.MobRemoved(tick, WARMUP_MOB));
    return script;
  }

  /** An answer to {@code requestId} whose one node has the type {@code type}. */
  private static ByteBuffer answer(long run, long requestId, int type) {
    return answer(run, requestId, type, 1);
  }

  /** As above, searched at {@code worldSequence}. A script's first frame is sequence 1. */
  private static ByteBuffer answer(long run, long requestId, int type, long worldSequence) {
    FlatBufferBuilder builder = new FlatBufferBuilder(128);
    PathResponse.startNodesVector(builder, 1);
    // Positions on the wire are relative to the area, a halo below the region.
    PathNode.createPathNode(
        builder,
        (short) (HERE.x() - HERE_REGION_MIN + Region.HALO),
        (short) HERE.y(),
        (short) (HERE.z() - HERE_REGION_MIN + Region.HALO),
        type);
    int nodes = builder.endVector();
    int body =
        PathResponse.createPathResponse(builder, requestId, true, nodes, 1.0f, worldSequence);
    builder.finish(ResponseFrame.createResponseFrame(builder, ResponseMsg.PathResponse, body, run));
    return ByteBuffer.wrap(builder.sizedByteArray());
  }

  private static List<WorldEvent> chunkUnloads(long tick) {
    List<WorldEvent> events = new ArrayList<>();
    for (WorldEvent loaded : chunkLoads(tick)) {
      WorldEvent.ChunkLoaded chunk = (WorldEvent.ChunkLoaded) loaded;
      events.add(new WorldEvent.ChunkUnloaded(tick, chunk.chunkX(), chunk.chunkZ()));
    }
    return events;
  }

  /** The chunks holding {@code HERE} and {@code THERE}, the only ones a script uses. */
  private static List<WorldEvent> chunkLoads(long tick) {
    return List.of(
        new WorldEvent.ChunkLoaded(tick, Chunks.of(HERE.x()), Chunks.of(HERE.z())),
        new WorldEvent.ChunkLoaded(tick, Chunks.of(THERE.x()), Chunks.of(THERE.z())));
  }

  private static List<NodeId> holders(SimRun run, Pos pos) {
    Region region = run.regions().regionAt(pos.x(), pos.z());
    return run.regions().holders(region.id());
  }
}
