package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.PathResult;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import maestro.fbs.PathType;
import org.junit.jupiter.api.Test;

/**
 * Each check of {@link Invariants} fires when its property breaks. A clean run has no violation, so
 * each test breaks one property of one and finds it reported.
 */
class InvariantsTest {

  private static final long TICKS = 150;
  private static final NodeId NODE = NodeId.of(0);

  private static final short MOB_CLASS = 1;
  private static final int MOB = 1;
  private static final int WARMUP_MOB = 2;
  private static final int PLAYER = 1;

  /** In the middle of the region on the positive side of the origin's, as a script places mobs. */
  private static final Pos HERE =
      new Pos(RegionLayout.cellMin(1) + 132, 40, RegionLayout.cellMin(1) + 132);

  private static final Pos THERE = new Pos(-20, 40, -20);
  private static final WorldEvent.Search SEARCH = new WorldEvent.Search(0, 16f, 256, 3);

  @Test
  void anAnswerSearchedOverAirThatWasAcceptedIsReported() {
    SimRun run = clean();

    run.out.pathComputed(
        new PathResult(MOB, HERE, false, List.of(), ModeledNode.SEARCHED_OVER_AIR, 0));

    assertReported(run, "1 answers searched over air were accepted");
  }

  @Test
  void aHealthyNodeTakenOutOfServiceIsReported() {
    SimRun run = clean();
    assertTrue(run.judgedHealthy(), run.describe());

    run.sim.metrics().count("node.quiet", 1, "node", NODE.toString());

    assertReported(run, "a healthy node was taken out of service");
  }

  @Test
  void aProfileSentTwiceOnOneLinkIsReported() {
    SimRun run = clean();
    String label = NODE.toString();
    long classes = run.scenario.counts().get(Count.MOB_CLASSES);
    long links = run.sim.metrics().counter("transport.connected", "node", label);
    long registered = run.sim.metrics().counter("mobclass.registered", "node", label);

    run.sim.metrics().count("mobclass.registered", classes * links - registered + 1, "node", label);

    assertReported(run, label + " was sent " + (classes * links + 1) + " profiles over " + links);
  }

  @Test
  void aFrameLargerThanItsChannelCarriesIsReported() {
    SimRun run = clean();

    run.sim
        .env()
        .transport()
        .send(NODE, Channel.REQUEST, ByteBuffer.wrap(new byte[Frames.MAX_DATAGRAM_BYTES + 1]));

    assertReported(run, "1 frames were larger than their channel carries");
  }

  @Test
  void aFrameReceivedMoreOftenThanItWasSentOrDuplicatedIsReported() {
    SimRun run = clean();
    String label = NODE.toString();
    String channel = Channel.REQUEST.name();
    long sent = run.sim.metrics().counter("transport.sent", "node", label, "channel", channel);
    long duplicated =
        run.sim.metrics().counter("transport.duplicated", "node", label, "channel", channel);
    long received =
        run.sim.metrics().counter("transport.received", "node", label, "channel", channel);

    run.sim
        .metrics()
        .count(
            "transport.received",
            sent + duplicated - received + 1,
            "node",
            label,
            "channel",
            channel);

    assertReported(run, label + " REQUEST received " + (sent + duplicated + 1));
  }

  @Test
  void aRequestStillOutstandingWhenTheRunEndsIsReported() {
    // Ends while the request's batch encodes, before the node serves it.
    SimRun cut = SimRun.scripted(1L, FaultPlan.reliable(), 3, oneRequest()).go();
    assertReported(cut, "1 requests still outstanding at end of run");

    SimRun drained = SimRun.scripted(1L, FaultPlan.reliable(), 30, oneRequest()).go();
    assertEquals(List.of(), drained.violations(), drained.describe());
  }

  /**
   * A controller that waits far past the grace the run expects ends the run with a region left to a
   * holder that cannot answer, while a spare is up.
   */
  @Test
  void aRegionLeftAtTheEndToHoldersThatCannotServeWhileASpareIsUpIsReported() {
    NodeId holder = NodeId.of(0);
    FaultPlan plan = FaultPlan.reliable().with(new FaultPlan.Blackholed(40, holder, true));
    Scenario shortGrace = Scenario.DEFAULT.with(Count.GRACE_TICKS, 30);

    SimRun late =
        SimRun.withControllerGrace(11L, plan, 300, Topology.DEFAULT, shortGrace, 1000).go();

    assertTrue(late.settles(), late.describe());
    assertReported(late, "region 1 is held only by nodes that cannot serve [" + holder + "]");
  }

  /** A block the controller never heard of leaves a caught-up holder unlike a fresh snapshot. */
  @Test
  void aHolderWhoseStateDiffersFromAFreshSnapshotIsReported() {
    SimRun run = clean();
    assertEquals(List.of(), run.violations(), run.describe());

    Pos changed = null;
    NodeId changedHolder = null;
    for (NodeId holder : run.fleet) {
      int regionId = run.regions().regionOf(holder);
      if (regionId == Regions.NONE || changed != null) {
        continue;
      }
      Region region = run.regions().region(regionId);
      for (int i = 0; i < run.world.loadedCount() && changed == null; i++) {
        long chunk = run.world.loadedChunk(i);
        int x = (int) (chunk >> 32) * Chunks.SIZE;
        int z = (int) chunk * Chunks.SIZE;
        if (x >= region.areaMinX()
            && x < region.areaMinX() + region.areaSizeX()
            && z >= region.areaMinZ()
            && z < region.areaMinZ() + region.areaSizeZ()) {
          changed = new Pos(x, run.world.minY(), z);
          changedHolder = holder;
        }
      }
    }
    assertNotNull(changed, "no holder has a loaded chunk in its area: " + run.describe());
    assertTrue(Invariants.caughtUp(run.controller, run.sim.node(changedHolder)), run.describe());

    boolean blocked =
        run.world.pathTypeAt(changed.x(), changed.y(), changed.z()) == PathType.BLOCKED;
    run.world.set(
        changed.x(),
        changed.y(),
        changed.z(),
        (byte) (blocked ? PathType.OPEN : PathType.BLOCKED),
        blocked ? 0 : Cells.FULL_FLOOR);

    List<String> found = Invariants.checkAgainstFreshSnapshot(run, run.sim.node(changedHolder));
    assertEquals(1, found.size(), found.toString());
    assertTrue(found.get(0).startsWith("region state differs after failover"), found.toString());
    assertReported(run, "region state differs after failover: " + changedHolder);
  }

  @Test
  void anOutcomeTheRequestMetricsMissedIsReported() {
    SimRun answers = clean();
    answers.sim.metrics().count("path.answered", 1);
    assertReported(answers, "answers were counted, but");

    SimRun drops = clean();
    drops.sim.metrics().count("path.dropped", 1, "reason", DropReason.SHED.name());
    assertReported(drops, "SHED drops were counted, but");
  }

  /** A run with no fault and spare capacity, which breaks no invariant. */
  private static SimRun clean() {
    SimRun run = SimRun.of(3L, FaultPlan.reliable(), TICKS).go();
    assertEquals(List.of(), run.violations(), run.describe());
    return run;
  }

  /**
   * One request admitted at tick 2, once a warm-up request registered its profile, from a chunk the
   * owner of its region holds by then.
   */
  private static List<WorldEvent> oneRequest() {
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.ChunkLoaded(0, Chunks.of(HERE.x()), Chunks.of(HERE.z())));
    script.add(new WorldEvent.ChunkLoaded(0, Chunks.of(THERE.x()), Chunks.of(THERE.z())));
    script.add(new WorldEvent.PlayerMoved(0, PLAYER, HERE));
    script.add(new WorldEvent.MobSpawned(0, WARMUP_MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, WARMUP_MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    script.add(new WorldEvent.MobRemoved(2, WARMUP_MOB));
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH, true));
    return script;
  }

  private static void assertReported(SimRun run, String expected) {
    List<String> violations = run.violations();
    assertTrue(
        violations.stream().anyMatch(violation -> violation.contains(expected)),
        "no violation containing '" + expected + "' in " + violations + ": " + run.describe());
  }
}
