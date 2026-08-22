package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.ArrayList;
import java.util.List;
import maestro.fbs.PathType;
import org.junit.jupiter.api.Test;

/**
 * Boundary cases driven by a scripted trace. A generated workload reaches these only by luck, and
 * each one is a statement about what the mod is promised: exactly one callback per request.
 */
class WorldBoundaryTest {

  private static final long TICKS = 30;
  private static final int CHUNK_SIZE = 16;
  private static final short MOB_CLASS = 1;
  private static final int MOB = 1;
  private static final Pos HERE = new Pos(4, 40, 4);
  private static final Pos THERE = new Pos(20, 40, 20);

  @Test
  void aMobRemovedWhileWaitingIsDroppedExactlyOnce() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.MobSpawned(0, MOB, MOB_CLASS, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, HERE, THERE));
    script.add(new WorldEvent.MobRemoved(3, MOB));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.controller.requestsIssued(), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.MOB_GONE), run.describe());
  }

  @Test
  void aRequestForAnUnknownMobIsDropped() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.PathRequested(2, 99, HERE, THERE));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.MOB_GONE), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  @Test
  void aSecondRequestForOneMobSupersedesTheFirst() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.MobSpawned(0, MOB, MOB_CLASS, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, HERE, THERE));
    script.add(new WorldEvent.PathRequested(3, MOB, HERE, HERE));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(2, run.controller.requestsIssued(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.SUPERSEDED), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
  }

  @Test
  void aRequestIsDroppedWhileNoNodeHoldsTheRegion() {
    // No chunks load, so no region is ever snapshottable and no node can answer.
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.MobSpawned(0, MOB, MOB_CLASS, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, HERE, THERE));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(!node.hasState(), run.describe());
    }
  }

  @Test
  void aBlockChangeBeforeTheSnapshotIsAbsorbedByIt() {
    // The change lands while the region is still unreadable, so it must not be
    // sent as a delta: the snapshot that follows already contains it.
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.BlockChanged(2, new Pos(4, 40, 4), (byte) PathType.WATER));
    script.addAll(chunkLoads(5));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().counter("offload.submitted", "job", "delta"), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(node.hasState(), run.describe());
      assertEquals(1, node.lastSequence(), run.describe());
    }
  }

  private static List<WorldEvent> chunkLoads(long tick) {
    int chunksPerSide = SimRun.REGION_SIZE * SimRun.REGIONS_PER_SIDE / CHUNK_SIZE;
    List<WorldEvent> events = new ArrayList<>();
    for (int chunkX = 0; chunkX < chunksPerSide; chunkX++) {
      for (int chunkZ = 0; chunkZ < chunksPerSide; chunkZ++) {
        events.add(new WorldEvent.ChunkLoaded(tick, chunkX, chunkZ));
      }
    }
    return events;
  }
}
