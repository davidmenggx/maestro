package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimulationTest {

  private static final long TICKS = 150;

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
  void everySeedProducesItsOwnRun() {
    // Guards against the seed being ignored somewhere in the wiring. Two runs
    // colliding by chance needs an FNV-1a collision across 64 bits, so for a
    // sweep this size the false failure rate is far below one in a billion.
    Set<Long> digests = new LinkedHashSet<>();
    for (int i = 0; i < SimRun.runCount(); i++) {
      digests.add(
          SimRun.of(SimRun.baseSeed() + i, FaultPlan.reliable(), TICKS).go().sim.digest().value());
    }
    assertEquals(SimRun.runCount(), digests.size(), "two seeds produced the same run");
  }

  @Test
  void invariantsHoldOnAReliableNetwork() {
    for (int i = 0; i < SimRun.runCount(); i++) {
      SimRun run = SimRun.of(SimRun.baseSeed() + i, FaultPlan.reliable(), TICKS).go();
      assertEquals(List.of(), run.violations(), run.describe());
      assertTrue(run.out.answered() > 0, run.describe());
      assertEquals(0, run.out.dropped(DropReason.NODE_LOST), run.describe());
      assertEquals(0, run.out.dropped(DropReason.SHED), run.describe());
    }
  }

  @Test
  void eachRegionIsSnapshottedOnceThenStreamedAsDeltas() {
    SimRun run = SimRun.of(3L, FaultPlan.reliable(), TICKS).go();

    assertEquals(
        run.regions.count(),
        run.sim.metrics().counter("offload.submitted", "job", "snapshot"),
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
  void lossHitsTheRequestStreamAndNeverTheWorldStream() {
    FaultPlan lossy = FaultPlan.reliable().withLoss(0.05, 0.05);
    long duplicatesSeen = 0;
    long requestFramesDropped = 0;

    for (int i = 0; i < SimRun.runCount(); i++) {
      SimRun run = SimRun.of(SimRun.baseSeed() + i, lossy, TICKS).go();
      assertEquals(List.of(), run.violations(), run.describe());
      duplicatesSeen += run.sim.nodes().stream().mapToLong(ModeledNode::duplicateRequests).sum();
      requestFramesDropped += counter(run, "transport.dropped", Channel.REQUEST);
      // Nothing on the ordered stream is lost.
      assertEquals(
          counter(run, "transport.sent", Channel.WORLD),
          counter(run, "transport.received", Channel.WORLD),
          run.describe());
      assertEquals(0, counter(run, "transport.dropped", Channel.WORLD), run.describe());
    }

    assertTrue(duplicatesSeen > 0, "expected the request stream to duplicate at least once");
    assertTrue(requestFramesDropped > 0, "expected the request stream to drop at least once");
  }

  @Test
  void aDuplicatedRequestIsAnsweredOnceByTheController() {
    SimRun run = SimRun.of(5L, FaultPlan.reliable().withLoss(0.0, 0.4), TICKS).go();

    // The node re-answers a retry with the same bytes, so the controller sees more
    // responses than requests and must discard the extras rather than double-apply.
    assertTrue(run.sim.nodes().stream().mapToLong(ModeledNode::duplicateRequests).sum() > 0);
    assertTrue(run.sim.metrics().counter("path.response.stale") > 0, run.describe());
    assertEquals(List.of(), run.violations(), run.describe());
  }

  @Test
  void everyNodeIsToldTheMobClassTableBeforeItIsAskedForAPath() {
    SimRun run = SimRun.of(17L, FaultPlan.reliable(), TICKS).go();

    assertEquals(List.of(), run.violations(), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertEquals(run.world.mobClasses().size(), node.knownClassCount(), run.describe());
    }
  }

  @Test
  void chunkChurnHoldsTheInvariantsAndStaysDeterministic() {
    long resnapshots = 0;
    long unreadable = 0;

    for (int i = 0; i < SimRun.runCount(); i++) {
      long seed = SimRun.baseSeed() + i;
      SimRun run = SimRun.of(seed, FaultPlan.reliable(), TICKS, Scenario.CHURN).go();
      assertEquals(List.of(), run.violations(), run.describe());
      resnapshots += run.sim.metrics().counter("offload.submitted", "job", "snapshot");
      unreadable += run.out.dropped(DropReason.NO_NODE);

      // Chunk churn adds event kinds and code paths; the run must still replay.
      SimRun again = SimRun.of(seed, FaultPlan.reliable(), TICKS, Scenario.CHURN).go();
      assertEquals(run.sim.digest().value(), again.sim.digest().value(), run.describe());
    }

    // Confirms chunks did leave and come back, so the assertions above were not vacuous.
    assertTrue(resnapshots > SimRun.runCount() * 4L, "expected regions to be snapshotted again");
    assertTrue(unreadable > 0, "expected some requests to find their region unreadable");
  }

  @Test
  void fuzzedLoadAndFaultsHoldTheInvariantsAndReplay() {
    Set<Scenario> scenarios = new LinkedHashSet<>();
    long answered = 0;

    for (int i = 0; i < SimRun.runCount(); i++) {
      long seed = SimRun.baseSeed() + i;
      SimRun run = SimRun.fuzzed(seed, TICKS).go();
      scenarios.add(run.scenario);
      answered += run.out.answered();

      // The fleet may be saturated at these settings, so the bound to check is
      // the one admission control promises. The 3-tick SLO is only promised on a
      // fleet with headroom.
      assertEquals(List.of(), run.violations(ProbeController.MAX_TICKS_LATE), run.describe());
      assertEquals(
          run.sim.digest().value(),
          SimRun.fuzzed(seed, TICKS).go().sim.digest().value(),
          run.describe());
    }

    assertEquals(SimRun.runCount(), scenarios.size(), "two seeds drew the same scenario");
    assertTrue(answered > 0, "no seed in the sweep produced a path");
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
    // The world stream restarts from a fresh snapshot rather than resuming into a gap.
    assertTrue(first.sim.node(victim).hasState(), first.describe());
    assertTrue(
        first.sim.metrics().counter("offload.submitted", "job", "snapshot") > first.regions.count(),
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
    // The node kept its state across the reset, so no region was snapshotted twice.
    assertEquals(
        run.regions.count(),
        run.sim.metrics().counter("offload.submitted", "job", "snapshot"),
        run.describe());
    assertEquals(
        List.of(),
        Invariants.checkAgainstFreshSnapshot(run.sim, run.sim.node(victim), run.world, run.regions),
        run.describe());
  }

  @Test
  void aNodeThatFallsPastTheRetentionWindowIsResnapshotted() {
    NodeId victim = NodeId.of(2);
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.LinkReset(40, victim),
                new FaultPlan.Partitioned(40, victim, true),
                new FaultPlan.Partitioned(200, victim, false));

    SimRun run = SimRun.of(29L, plan, 250).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(
        0, run.sim.metrics().counter("world.resume", "node", victim.toString()), run.describe());
    // Once when it first joined holding nothing, once when it came back too far behind.
    assertEquals(
        2,
        run.sim.metrics().counter("world.resnapshot", "node", victim.toString()),
        run.describe());
    assertTrue(
        run.sim.metrics().counter("offload.submitted", "job", "snapshot") > run.regions.count(),
        run.describe());
    assertEquals(
        List.of(),
        Invariants.checkAgainstFreshSnapshot(run.sim, run.sim.node(victim), run.world, run.regions),
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
    assertTrue(counter(run, "transport.held", Channel.WORLD) > 0, run.describe());
    assertTrue(run.sim.node(victim).lastSequence() > 1, run.describe());
  }

  private static long counter(SimRun run, String name, Channel channel) {
    long total = 0;
    for (int i = 0; i < run.regions.count(); i++) {
      total +=
          run.sim
              .metrics()
              .counter(name, "node", NodeId.of(i).toString(), "channel", channel.name());
    }
    return total;
  }
}
