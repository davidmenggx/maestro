package com.smpler.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smpler.maestro.controller.env.NodeId;
import com.smpler.maestro.controller.env.Transport.Channel;
import com.smpler.maestro.controller.world.Pos;
import com.smpler.maestro.controller.world.WorldEvent;
import java.nio.ByteBuffer;
import java.util.List;
import maestro.fbs.PathType;
import org.junit.jupiter.api.Test;

class ModeledNodeTest {

  private static final long TICKS = 150;

  @Test
  void stateAfterFailoverMatchesAFreshSnapshot() {
    NodeId victim = NodeId.of(0);
    FaultPlan plan =
        FaultPlan.reliable()
            .with(new FaultPlan.NodeDied(40, victim), new FaultPlan.NodeRecovered(45, victim));

    SimRun run = SimRun.of(21L, plan, TICKS).go();
    ModeledNode resumed = run.sim.node(victim);
    assertTrue(resumed.hasState(), "the node never rebuilt its region: " + run.describe());

    // A node handed one fresh snapshot of the same world must agree with the one
    // that rebuilt from a snapshot plus a tick's worth of deltas at a time.
    int region = run.regions.indexOf(victim);
    ModeledNode fresh = run.sim.addNode(NodeId.of(90), SimRun.NODE_CAPACITY);
    fresh.receive(
        Channel.WORLD, ByteBuffer.wrap(ProbeController.encodeAssignment(run.regions, region)));
    fresh.receive(
        Channel.WORLD,
        ByteBuffer.wrap(ProbeController.snapshotFrame(run.world, run.regions, region, 1)));

    assertEquals(List.of(), Invariants.checkStateEquality(resumed, fresh), run.describe());
  }

  @Test
  void aGapInTheWorldStreamIsReported() {
    Simulation sim = new Simulation(3L, FaultPlan.reliable());
    ModeledNode node = sim.addNode(NodeId.of(0), 4);
    RegionMap regions = new RegionMap(16, 1, List.of(NodeId.of(0)));
    SimWorld world = new SimWorld(16, 16, sim.rng().derive("terrain"));
    world.loadChunk(0, 0);

    node.receive(Channel.WORLD, ByteBuffer.wrap(ProbeController.encodeAssignment(regions, 0)));
    node.receive(
        Channel.WORLD, ByteBuffer.wrap(ProbeController.snapshotFrame(world, regions, 0, 1)));
    assertEquals(List.of(), node.violations());
    assertEquals(1, node.lastSequence());

    List<WorldEvent.BlockChanged> cells =
        List.of(new WorldEvent.BlockChanged(0, new Pos(1, 40, 1), (byte) PathType.WATER));
    node.receive(Channel.WORLD, ByteBuffer.wrap(ProbeController.encodeDelta(2, cells)));
    assertEquals(List.of(), node.violations());

    // Sequence 3 is skipped, which is what a resumable stream must never do.
    node.receive(Channel.WORLD, ByteBuffer.wrap(ProbeController.encodeDelta(4, cells)));
    assertFalse(node.violations().isEmpty(), "a skipped sequence went unreported");
  }
}
