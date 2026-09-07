package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Seed sweeps. The suite grows with {@code -Dmaestro.dst.runs} rather than by adding cases, and a
 * failing seed replays with `make fuzz SEED=...`.
 *
 * <p>Each sweep carries a tier alongside the {@code fuzz} tag, and a tier runs as its own task and
 * its own CI job. What a seed costs varies by more than an order of magnitude across these, so one
 * seed budget for all of them would be set by the most expensive sweep and waste the cheap ones.
 * Splitting also means a red build names what broke before anyone opens a log.
 */
@Tag("fuzz")
class FuzzTest {

  /**
   * One run a seed, every setting pinned: the seed moves the event ordering and what the network
   * does, nothing else. Cheapest per seed, so this tier sweeps the most of them.
   */
  static final String SHALLOW = "shallow";

  /**
   * Several runs a seed, or a run whose whole configuration is drawn from the seed. Costs a
   * multiple of a shallow seed, so it sweeps proportionally fewer.
   */
  static final String DEEP = "deep";

  /**
   * A claim about what the sampler reaches rather than about any one run, so its size is fixed and
   * neither shards nor grows with the run count.
   */
  static final String COVERAGE = "coverage";

  private static final long TICKS = 150;

  private static final int MIN_COVERAGE_SEEDS = 300;

  private static final int MAX_COVERAGE_SEEDS = 2000;

  @Test
  @Tag(SHALLOW)
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
  @Tag(SHALLOW)
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
  @Tag(SHALLOW)
  void lossHitsTheRequestStreamAndNeverTheWorldStream() {
    FaultPlan lossy = FaultPlan.reliable().withLoss(0.05, 0.05);
    long duplicatesSeen = 0;
    long requestFramesDropped = 0;

    for (int i = 0; i < SimRun.runCount(); i++) {
      SimRun run = SimRun.of(SimRun.baseSeed() + i, lossy, TICKS).go();
      assertEquals(List.of(), run.violations(), run.describe());
      duplicatesSeen += run.sim.nodes().stream().mapToLong(ModeledNode::duplicateRequests).sum();
      requestFramesDropped += counter(run, "transport.dropped", Channel.REQUEST);
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
  @Tag(DEEP)
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
  @Tag(DEEP)
  void fuzzedRunsHoldTheInvariantsAndReplay() {
    Set<Scenario> scenarios = new LinkedHashSet<>();
    long answered = 0;

    for (int i = 0; i < SimRun.runCount(); i++) {
      long seed = SimRun.baseSeed() + i;
      SimRun run = SimRun.fuzzed(seed).go();
      scenarios.add(run.scenario);
      answered += run.out.answered();

      // The fleet may be saturated at these settings, so the bound to check is
      // the one admission control promises. The 3-tick SLO is only promised on a
      // fleet with headroom.
      assertEquals(List.of(), run.violations(ProbeController.MAX_TICKS_LATE), run.describe());
      assertEquals(
          run.sim.digest().value(), SimRun.fuzzed(seed).go().sim.digest().value(), run.describe());
    }

    assertEquals(SimRun.runCount(), scenarios.size(), "two seeds drew the same scenario");
    assertTrue(answered > 0, "no seed in the sweep produced a path");
  }

  /** Asserts the sweep actually produces every outcome the invariants are written against. */
  @Test
  @Tag(COVERAGE)
  void theSweepReachesEveryOutcomeItClaimsTo() {
    Set<DropReason> drops = EnumSet.noneOf(DropReason.class);
    Set<Integer> regionSizes = new LinkedHashSet<>();
    Set<Integer> gridWidths = new LinkedHashSet<>();
    long resumes = 0;
    long resnapshots = 0;
    long duplicates = 0;
    long held = 0;
    long linkResets = 0;
    long compared = 0;
    long deepWorlds = 0;
    long slowFleets = 0;
    long emptyServers = 0;

    for (int i = 0; i < coverageSeeds(); i++) {
      SimRun run = SimRun.fuzzed(SimRun.baseSeed() + i).go();
      assertEquals(List.of(), run.violations(ProbeController.MAX_TICKS_LATE), run.describe());

      drops.addAll(run.out.drops().keySet());
      regionSizes.add(run.topology.regionSize());
      gridWidths.add(run.topology.regionsPerSide());
      deepWorlds += run.topology.minY() < 0 ? 1 : 0;
      slowFleets += run.scenario.slowNodes() > 0 ? 1 : 0;
      emptyServers += run.scenario.playerCount() == 0 ? 1 : 0;
      resumes += run.sim.metrics().total("world.resume");
      resnapshots += run.sim.metrics().total("world.resnapshot");
      duplicates += run.sim.nodes().stream().mapToLong(ModeledNode::duplicateRequests).sum();
      linkResets += run.sim.metrics().total("transport.link.reset");
      held += counter(run, "transport.held", Channel.WORLD);
      for (ModeledNode node : run.sim.nodes()) {
        compared += Invariants.caughtUp(run.controller, node) ? 1 : 0;
      }
    }

    assertEquals(EnumSet.allOf(DropReason.class), drops, "a drop reason the sweep never reaches");
    assertTrue(regionSizes.size() > 1, "region size never varied");
    assertTrue(gridWidths.size() > 1, "the region grid never changed shape");
    assertTrue(deepWorlds > 0, "no run put the world floor below zero");
    assertTrue(slowFleets > 0, "no run gave the fleet a node slower than the rest");
    assertTrue(emptyServers > 0, "no run ran without a player online");
    assertTrue(resumes > 0, "no node ever resumed its world stream");
    assertTrue(resnapshots > 0, "no node was ever resnapshotted");
    assertTrue(duplicates > 0, "no request was ever delivered twice");
    assertTrue(held > 0, "no world frame was ever held behind a partition");
    assertTrue(linkResets > 0, "no connection was ever reset with the process alive");
    assertTrue(compared > 0, "no node ever caught up far enough to compare its state");
  }

  /**
   * Seeds the coverage sweep runs. It claims something about what the sampler reaches rather than
   * about any one run, so it takes a size of its own whatever {@code maestro.dst.runs} says: a
   * handful of seeds cannot reach every outcome, and a very large one only repeats the answer.
   */
  private static int coverageSeeds() {
    return Math.clamp(SimRun.runCount(), MIN_COVERAGE_SEEDS, MAX_COVERAGE_SEEDS);
  }

  /** Counters are per node and per channel, so a whole-fleet total has to be summed. */
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
