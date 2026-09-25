package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.world.Chunks;
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
  private static final short MOB_CLASS = 1;
  private static final int MOB = 1;
  private static final int WARMUP_MOB = 2;
  private static final short OTHER_CLASS = 2;
  private static final Pos HERE = new Pos(4, 40, 4);
  private static final Pos THERE = new Pos(20, 40, 20);
  private static final WorldEvent.Search SEARCH = new WorldEvent.Search(0, 16f, 256);

  @Test
  void aMobRemovedWhileWaitingIsDroppedExactlyOnce() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
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
    script.add(new WorldEvent.PathRequested(2, 99, MOB_CLASS, HERE, THERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.MOB_GONE), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  @Test
  void aSecondRequestForOneMobSupersedesTheFirst() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.PathRequested(3, MOB, MOB_CLASS, HERE, HERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(3, run.controller.requestsIssued(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.SUPERSEDED), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
  }

  @Test
  void aRequestIsDroppedWhileNoNodeHoldsTheRegion() {
    // No chunks load, so no region is ever snapshottable and no node can answer.
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertFalse(node.hasState(), run.describe());
    }
  }

  @Test
  void aBlockChangeBeforeTheSnapshotIsAbsorbedByIt() {
    // The change lands while the region is still unreadable, so it must not be
    // sent as a delta: the snapshot that follows already contains it.
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.BlockChanged(2, new Pos(4, 40, 4), (byte) PathType.WATER, 0));
    script.addAll(chunkLoads(5));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().counter("offload.submitted", "job", "delta"), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(node.hasState(), run.describe());
      assertEquals(1, node.lastSequence(), run.describe());
    }
  }

  @Test
  void aCanceledRequestIsDroppedRatherThanAnswered() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.PathCanceled(3, MOB));
    // A cancel for a mob with nothing outstanding changes nothing.
    script.add(new WorldEvent.PathCanceled(8, MOB));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(2, run.controller.requestsIssued(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.CANCELED), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  /**
   * A chunk that leaves reads as air on the node, which is what vanilla makes of a chunk the server
   * is not holding, so only that chunk is resent and the rest of the region keeps serving.
   */
  @Test
  void anUnloadedChunkIsClearedOnTheNodeAndTheRegionKeepsServing() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.ChunkUnloaded(6, Chunks.of(HERE.x()), Chunks.of(HERE.z())));
    script.add(new WorldEvent.PathRequested(8, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.ChunkLoaded(10, Chunks.of(HERE.x()), Chunks.of(HERE.z())));
    script.add(new WorldEvent.PathRequested(14, MOB, MOB_CLASS, HERE, THERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(4, run.controller.requestsIssued(), run.describe());
    assertEquals(0, run.out.dropped(DropReason.NO_NODE), run.describe());
    assertEquals(3, run.out.answered(), run.describe());
    // One frame to clear the chunk and one to fill it again, against no resnapshot.
    assertEquals(
        run.chunkCount() + 2,
        run.sim.metrics().counter("offload.submitted", "job", "chunk"),
        run.describe());
    assertEquals(run.regions.count(), run.sim.metrics().total("world.resnapshot"), run.describe());
  }

  @Test
  void theFirstRequestNamingAProfileRegistersItAndIsGivenUp() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.PathRequested(6, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    // A second profile costs its own request, since registration is per profile.
    script.add(new WorldEvent.PathRequested(10, MOB, OTHER_CLASS, HERE, THERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(3, run.controller.requestsIssued(), run.describe());
    assertEquals(2, run.out.dropped(DropReason.CLASS_UNREGISTERED), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
    assertEquals(2, run.sim.metrics().total("mobclass.registered"), run.describe());
  }

  /** A profile the world cannot produce is the controller's own bug, not the node's. */
  @Test
  void aRequestNamingAProfileTheWorldDoesNotHoldIsRefused() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, (short) 99, HERE, THERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertTrue(
        run.violations().stream().anyMatch(v -> v.contains("which the world does not hold")),
        "an unproducible profile went unreported: " + run.violations());
    assertEquals(1, run.out.dropped(DropReason.MOB_GONE), run.describe());
  }

  /**
   * Loads the world and registers {@code MOB_CLASS} with the node that owns it. Registration gives
   * up the request that triggers it, so every script pays one throwaway request up front.
   */
  private static List<WorldEvent> prelude() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.MobSpawned(0, WARMUP_MOB, HERE));
    script.add(new WorldEvent.PathRequested(1, WARMUP_MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.MobRemoved(1, WARMUP_MOB));
    return script;
  }

  private static List<WorldEvent> chunkLoads(long tick) {
    int chunksPerSide = Topology.DEFAULT.chunksPerSide();
    List<WorldEvent> events = new ArrayList<>();
    for (int chunkX = 0; chunkX < chunksPerSide; chunkX++) {
      for (int chunkZ = 0; chunkZ < chunksPerSide; chunkZ++) {
        events.add(new WorldEvent.ChunkLoaded(tick, chunkX, chunkZ));
      }
    }
    return events;
  }
}
