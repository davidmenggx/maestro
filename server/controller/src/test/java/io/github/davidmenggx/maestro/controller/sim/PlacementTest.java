package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regions moving between nodes: a holder's that cannot serve to a spare, and a hot one cut or
 * replicated.
 */
class PlacementTest {

  private static final long TICKS = 200;

  /** Holds the origin's region, where the spawn chunks keep the world loaded. */
  private static final NodeId SPAWN_HOLDER = NodeId.of(0);

  /** The last node of the default fleet, which starts as a spare. */
  private static final NodeId SPARE = NodeId.of(4);

  private static final Scenario SHORT_GRACE = Scenario.DEFAULT.with(Count.GRACE_TICKS, 30);

  /** One request served a tick, exactly. */
  private static final Scenario ONE_A_TICK =
      Scenario.DEFAULT
          .with(Count.WORKER_CAPACITY, 1)
          .with(Count.WORKERS, 1)
          .with(Count.COST_SPREAD_PERCENT, 0);

  @Test
  void aDeadHoldersRegionGoesToASpareOnceItsGraceRunsOut() {
    FaultPlan plan = FaultPlan.reliable().with(new FaultPlan.NodeDied(40, SPAWN_HOLDER));

    SimRun run = SimRun.of(11L, plan, TICKS, Topology.DEFAULT, SHORT_GRACE).go();

    assertEquals(List.of(), run.violations(), run.describe());
    Region spawn = run.regions().regionAt(0, 0);
    assertEquals(List.of(SPARE), run.regions().holders(spawn.id()), run.describe());
    assertEquals(Regions.NONE, run.regions().regionOf(SPAWN_HOLDER), run.describe());
    assertEquals(1, run.sim.metrics().total("region.reassigned"), run.describe());
    assertTrue(Invariants.caughtUp(run.controller, run.sim.node(SPARE)), run.describe());
    assertTrue(run.sim.node(SPARE).servedRequests() > 0, run.describe());
  }

  @Test
  void aHolderBackWithinItsGraceKeepsItsRegion() {
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Partitioned(40, SPAWN_HOLDER, true),
                new FaultPlan.Partitioned(80, SPAWN_HOLDER, false));

    SimRun run = SimRun.of(11L, plan, TICKS, Topology.DEFAULT, Scenario.DEFAULT).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.regions().regionOf(SPAWN_HOLDER), run.describe());
    assertEquals(Regions.NONE, run.regions().regionOf(SPARE), run.describe());
    assertEquals(0, run.sim.metrics().total("region.reassigned"), run.describe());
  }

  @Test
  void aDownHolderWithNoSpareKeepsItsRegionAndTakesItBack() {
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.NodeDied(40, SPAWN_HOLDER),
                new FaultPlan.NodeRecovered(120, SPAWN_HOLDER));
    Topology noSpare = Topology.DEFAULT.withNodes(4).withSpares(0);

    SimRun run = SimRun.of(11L, plan, TICKS, noSpare, SHORT_GRACE).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.regions().regionOf(SPAWN_HOLDER), run.describe());
    assertEquals(0, run.sim.metrics().total("region.reassigned"), run.describe());
    assertTrue(Invariants.caughtUp(run.controller, run.sim.node(SPAWN_HOLDER)), run.describe());
  }

  @Test
  void aRegionThatKeepsSheddingIsHalvedAndASpareTakesTheUpperHalf() {
    // One region over two by two cells, and one spare.
    Topology one = Topology.DEFAULT.withCells(2).withNodes(2).withSpares(1);
    Scenario hot = Scenario.SATURATED.with(Count.HOT_WINDOWS, 1);

    SimRun run = SimRun.of(11L, FaultPlan.reliable(), TICKS, one, hot).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.sim.metrics().total("region.split"), run.describe());
    List<Region> regions = List.copyOf(run.regions().regions());
    assertEquals(2, regions.size(), run.describe());
    RegionLayout layout = one.layout();
    // Halved across x; the old holder takes the lower half, with the origin, and the spare the
    // upper.
    assertEquals(
        new Region(2, layout.min(), layout.min(), RegionLayout.CELL, 2 * RegionLayout.CELL),
        regions.get(0));
    assertEquals(List.of(NodeId.of(0)), run.regions().holders(2), run.describe());
    assertEquals(List.of(NodeId.of(1)), run.regions().holders(3), run.describe());
    // The old holder kept what it held of the lower half instead of being sent it again. The
    // fresh-snapshot invariant checks what it kept.
    assertEquals(
        1,
        run.sim.metrics().counter("world.kept", "node", NodeId.of(0).toString()),
        run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(Invariants.caughtUp(run.controller, node), node.id() + " " + run.describe());
    }
  }

  @Test
  void aRegionCutWhileItsChunksAreStillBeingReadReadsTheRestForItsHalves() {
    // Many players' views take many ticks of the read budget, so chunks are still waiting to be
    // read when the region is cut.
    Topology one = Topology.DEFAULT.withCells(2).withNodes(2).withSpares(1);
    Scenario hot = Scenario.SATURATED.with(Count.HOT_WINDOWS, 1).with(Count.PLAYERS, 16);

    SimRun run = SimRun.of(3L, FaultPlan.reliable(), TICKS, one, hot).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.sim.metrics().total("region.split"), run.describe());
    assertTrue(run.sim.metrics().total("world.chunks.deferred") > 0, run.describe());
    assertEquals(1, run.sim.metrics().total("world.kept"), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(Invariants.caughtUp(run.controller, node), node.id() + " " + run.describe());
    }
  }

  @Test
  void aHotSingleCellRegionSharesItsMobsWithAReplicaUntilItCools() {
    Topology single = Topology.DEFAULT.withCells(1).withNodes(2).withSpares(1);
    Scenario hot = ONE_A_TICK.with(Count.HOT_WINDOWS, 1).with(Count.COLD_WINDOWS, 1);

    SimRun run =
        SimRun.scripted(11L, FaultPlan.reliable(), TICKS, single, hot, crowd(new Pos(20, 40, 20)))
            .go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().total("region.split"), run.describe());
    assertTrue(run.sim.metrics().total("region.replica.added") > 0, run.describe());
    // Its mobs split between the two holders while the load lasted, each taking the other's when
    // its own queue was full.
    assertTrue(run.sim.node(NodeId.of(0)).servedRequests() > 0, run.describe());
    assertTrue(run.sim.node(NodeId.of(1)).servedRequests() > 0, run.describe());
    assertTrue(run.sim.metrics().total("path.shared") > 0, run.describe());
    // The replica was sent the area, and the holder only empty deltas in place of its chunks.
    assertTrue(run.sim.metrics().total("world.chunks.skipped") > 0, run.describe());
    // Once the load ends, the replica goes back to the spares.
    assertTrue(run.sim.metrics().total("region.replica.returned") > 0, run.describe());
    assertEquals(Regions.NONE, run.regions().regionOf(NodeId.of(1)), run.describe());
    assertEquals(List.of(NodeId.of(0)), run.regions().holders(1), run.describe());
  }

  @Test
  void aHotRegionWhoseLoadIsInOneCellTakesAReplicaInsteadOfACut() {
    // One region over two by two cells, every request in its lowest cell.
    Topology one = Topology.DEFAULT.withCells(2).withNodes(2).withSpares(1);
    Scenario hot = ONE_A_TICK.with(Count.HOT_WINDOWS, 1).with(Count.COLD_WINDOWS, 30);

    SimRun run =
        SimRun.scripted(11L, FaultPlan.reliable(), TICKS, one, hot, crowd(new Pos(-100, 40, -100)))
            .go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().total("region.split"), run.describe());
    assertEquals(
        1,
        run.sim.metrics().counter("region.replica.added", "node", "node-1", "cells", "4"),
        run.describe());
    assertEquals(List.of(NodeId.of(0), NodeId.of(1)), run.regions().holders(1), run.describe());
    assertTrue(run.sim.node(NodeId.of(1)).servedRequests() > 0, run.describe());
  }

  @Test
  void aHolderWhoseDatagramsAreLostIsReplacedOnceItsGraceRunsOut() {
    // Its links and heartbeats carry on, so only its silence on the request path shows it.
    FaultPlan plan = FaultPlan.reliable().with(new FaultPlan.Blackholed(40, SPAWN_HOLDER, true));

    SimRun run = SimRun.of(11L, plan, TICKS, Topology.DEFAULT, SHORT_GRACE).go();

    assertEquals(List.of(), run.violations(), run.describe());
    Region spawn = run.regions().regionAt(0, 0);
    assertEquals(List.of(SPARE), run.regions().holders(spawn.id()), run.describe());
    assertEquals(1, run.sim.metrics().total("region.reassigned"), run.describe());
    // It is no spare while nothing it is sent is answered.
    assertFalse(run.controller.serves(SPAWN_HOLDER), run.describe());
  }

  @Test
  void aSpareWhoseDatagramsAreLostIsPassedOver() {
    Topology twoSpares = Topology.DEFAULT.withNodes(6).withSpares(2);
    NodeId deaf = NodeId.of(4);
    NodeId other = NodeId.of(5);
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Blackholed(1, deaf, true), new FaultPlan.NodeDied(60, SPAWN_HOLDER));

    SimRun run = SimRun.of(11L, plan, TICKS, twoSpares, SHORT_GRACE).go();

    assertEquals(List.of(), run.violations(), run.describe());
    Region spawn = run.regions().regionAt(0, 0);
    assertEquals(List.of(other), run.regions().holders(spawn.id()), run.describe());
    assertEquals(Regions.NONE, run.regions().regionOf(deaf), run.describe());
    assertTrue(
        run.sim.metrics().counter("node.check.failed", "node", deaf.toString()) > 0,
        run.describe());
    assertFalse(run.controller.serves(deaf), run.describe());
  }

  @Test
  void aDeadHoldersRegionTakesAReplicaWhenNoSpareIsLeft() {
    // Four single-cell regions and a spare, which the hot origin region takes as a replica.
    Scenario hot =
        ONE_A_TICK
            .with(Count.GRACE_TICKS, 30)
            .with(Count.HOT_WINDOWS, 1)
            .with(Count.COLD_WINDOWS, 30);
    NodeId dead = NodeId.of(1);
    FaultPlan plan = FaultPlan.reliable().with(new FaultPlan.NodeDied(80, dead));

    SimRun run =
        SimRun.scripted(11L, plan, TICKS, Topology.DEFAULT, hot, crowd(new Pos(20, 40, 20))).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.sim.metrics().total("region.replica.reclaimed"), run.describe());
    assertEquals(List.of(SPAWN_HOLDER), run.regions().holders(1), run.describe());
    assertEquals(List.of(SPARE), run.regions().holders(2), run.describe());
    assertEquals(Regions.NONE, run.regions().regionOf(dead), run.describe());
    assertTrue(Invariants.caughtUp(run.controller, run.sim.node(SPARE)), run.describe());
  }

  @Test
  void aRegionWithNoSpareToTakeKeepsShedding() {
    Topology alone = Topology.DEFAULT.withCells(1).withNodes(1).withSpares(0);
    Scenario hot = Scenario.SATURATED.with(Count.HOT_WINDOWS, 1);

    SimRun run = SimRun.of(11L, FaultPlan.reliable(), TICKS, alone, hot).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(run.out.dropped(DropReason.SHED) > 0, run.describe());
    assertEquals(0, run.sim.metrics().total("region.replica.added"), run.describe());
    assertFalse(run.fitsCapacity(), run.describe());
  }

  /**
   * Forty hunting mobs beside a player at {@code here} asking five times a tick until tick 60: a
   * backlog for a node serving one a tick.
   */
  private static List<WorldEvent> crowd(Pos here) {
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.ChunkLoaded(0, Chunks.of(here.x()), Chunks.of(here.z())));
    script.add(new WorldEvent.PlayerMoved(0, 1, here));
    int mobs = 40;
    for (int mob = 0; mob < mobs; mob++) {
      script.add(new WorldEvent.MobSpawned(0, mob, here));
    }
    WorldEvent.Search search = new WorldEvent.Search(0, 16f, 256, 3);
    for (long tick = 2; tick < 60; tick++) {
      for (int i = 0; i < 5; i++) {
        int mob = (int) ((tick * 5 + i) % mobs);
        script.add(new WorldEvent.PathRequested(tick, mob, (short) 1, here, here, search, true));
      }
    }
    return script;
  }
}
