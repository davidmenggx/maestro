package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.core.Dispatch;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Chance;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Seed sweeps, each tier its own task and CI job. The suite grows with {@code -Dmaestro.dst.runs},
 * and a failing seed is replayed with `make fuzz SEED=...`.
 */
@Tag("fuzz")
class FuzzTest {

  /** One run per seed with every setting fixed. Cheapest, so it sweeps the most seeds. */
  static final String SHALLOW = "shallow";

  /** Several runs per seed, or a run drawn wholly from the seed. Sweeps fewer seeds. */
  static final String DEEP = "deep";

  /** Checks what the sampler reaches, so its size is fixed. */
  static final String COVERAGE = "coverage";

  private static final long TICKS = 150;

  private static final int MIN_COVERAGE_SEEDS = 300;

  private static final int MAX_COVERAGE_SEEDS = 2000;

  @Test
  @Tag(SHALLOW)
  void everySeedProducesItsOwnRun() {
    // Catches the seed being ignored somewhere.
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
    int heldToTheSlo = 0;
    for (int i = 0; i < SimRun.runCount(); i++) {
      SimRun run = SimRun.of(SimRun.baseSeed() + i, FaultPlan.reliable(), TICKS).go();
      assertEquals(List.of(), run.violations(), run.describe());
      heldToTheSlo += run.heldToTheSlo() ? 1 : 0;
      assertTrue(run.out.answered() > 0, run.describe());
      assertEquals(0, run.out.dropped(DropReason.NODE_LOST), run.describe());
      assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
      // Nothing delays the control channel, so every profile arrives before a request names it.
      assertEquals(0, run.sim.metrics().total("path.profile.unknown"), run.describe());
    }
    // The default fleet has spare capacity, so the SLO applies to nearly every run.
    assertTrue(
        heldToTheSlo > SimRun.runCount() / 2, "the SLO applied to " + heldToTheSlo + " runs");
  }

  @Test
  @Tag(SHALLOW)
  void lossHitsTheRequestStreamAndNeverTheWorldStream() {
    FaultPlan lossy = FaultPlan.reliable().withDropRate(0.05).withDuplicateRate(0.05);
    long duplicatesSeen = 0;
    long requestFramesDropped = 0;

    for (int i = 0; i < SimRun.runCount(); i++) {
      SimRun run = SimRun.of(SimRun.baseSeed() + i, lossy, TICKS).go();
      assertEquals(List.of(), run.violations(), run.describe());
      duplicatesSeen += run.sim.nodes().stream().mapToLong(ModeledNode::duplicateRequests).sum();
      requestFramesDropped += run.fleetCounter("transport.dropped", Channel.REQUEST);
      assertEquals(
          run.fleetCounter("transport.sent", Channel.WORLD),
          run.fleetCounter("transport.received", Channel.WORLD),
          run.describe());
      assertEquals(0, run.fleetCounter("transport.dropped", Channel.WORLD), run.describe());
      // Loss on the request stream never moves a request ahead of its profile.
      assertEquals(0, run.sim.metrics().total("path.profile.unknown"), run.describe());
    }

    assertTrue(duplicatesSeen > 0, "expected the request stream to duplicate at least once");
    assertTrue(requestFramesDropped > 0, "expected the request stream to drop at least once");
  }

  @Test
  @Tag(DEEP)
  void chunkChurnHoldsTheInvariantsAndStaysDeterministic() {
    long chunkFrames = 0;
    long baseline = 0;

    for (int i = 0; i < SimRun.runCount(); i++) {
      long seed = SimRun.baseSeed() + i;
      SimRun run = SimRun.of(seed, FaultPlan.reliable(), TICKS, Scenario.CHURN).go();
      assertEquals(List.of(), run.violations(), run.describe());
      chunkFrames += run.sim.metrics().counter("offload.submitted", "job", "chunk");
      baseline += run.chunkCount();

      // Chunk churn adds event kinds and code paths, and the run must still replay.
      SimRun again = SimRun.of(seed, FaultPlan.reliable(), TICKS, Scenario.CHURN).go();
      assertEquals(run.sim.digest().value(), again.sim.digest().value(), run.describe());
    }

    // Confirms chunks were unloaded and loaded again, so the assertions above are meaningful.
    assertTrue(chunkFrames > baseline, "expected chunks to be cleared and sent again");
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

      // Held to the SLO only when the load fits and nothing can delay or lose a request.
      assertEquals(List.of(), run.violations(), run.describe());
      assertEquals(
          run.sim.digest().value(), SimRun.fuzzed(seed).go().sim.digest().value(), run.describe());
    }

    assertEquals(SimRun.runCount(), scenarios.size(), "two seeds drew the same scenario");
    assertTrue(answered > 0, "no seed in the sweep produced a path");
  }

  /** Asserts that the sweep produces every outcome the invariants check. */
  @Test
  @Tag(COVERAGE)
  void theSweepReachesEveryOutcomeItClaimsTo() {
    Set<DropReason> drops = EnumSet.noneOf(DropReason.class);
    Set<Integer> spares = new LinkedHashSet<>();
    Set<Integer> gridWidths = new LinkedHashSet<>();
    long multiCellStarts = 0;
    long splits = 0;
    long replicasAdded = 0;
    long replicasReturned = 0;
    long reassigned = 0;
    long reclaimed = 0;
    long keptHalves = 0;
    long streamedRuns = 0;
    long streamedAnswered = 0;
    long shedNearAPlayer = 0;
    long resumes = 0;
    long resnapshots = 0;
    long duplicates = 0;
    long framesHeld = 0;
    long linkResets = 0;
    long overtakenProfiles = 0;
    long encodeRetries = 0;
    long resends = 0;
    long nodesCompared = 0;
    long deepWorlds = 0;
    long slowFleets = 0;
    long emptyServers = 0;
    long heldToTheSlo = 0;
    long heldAtTheEdge = 0;
    long healthyThroughAStall = 0;
    long healthyThroughSlowTicks = 0;
    long abandonedAnswers = 0;
    long answersBehindTheirChunk = 0;
    long canceledInQueue = 0;
    long restarts = 0;
    long lostToRestart = 0;
    long foreignAnswers = 0;
    long pausedRuns = 0;
    long blackholedRuns = 0;
    long suspicions = 0;
    long quietPaths = 0;
    long probesAnswered = 0;
    long checksFailed = 0;
    long chunksSkipped = 0;
    long sharedRequests = 0;
    long multiCellReplicas = 0;
    long heldWithoutShedding = 0;
    long heldOverloaded = 0;
    long measuredSlower = 0;
    long refusedUnavailable = 0;
    long failOpenJudged = 0;
    long checkpoints = 0;
    long deferredReads = 0;

    // Uses its own size: a few seeds miss outcomes, and a very large sweep only repeats them.
    int seeds = Math.clamp(SimRun.runCount(), MIN_COVERAGE_SEEDS, MAX_COVERAGE_SEEDS);
    for (int i = 0; i < seeds; i++) {
      SimRun run = SimRun.fuzzed(SimRun.baseSeed() + i).go();
      assertEquals(List.of(), run.violations(), run.describe());

      drops.addAll(run.out.drops().keySet());
      boolean held = run.heldToTheSlo() && run.out.answered() > 0;
      boolean judgedHealthy = run.judgedHealthy() && run.out.answered() > 0;
      heldToTheSlo += held ? 1 : 0;
      heldAtTheEdge +=
          held
                  && run.faults.maxLatencySteps()
                      == FaultPlan.edgeLatency(run.scenario.maxCostSteps(run.topology.nodeCount()))
              ? 1
              : 0;
      healthyThroughAStall +=
          judgedHealthy
                  && run.faults.faults().stream()
                      .anyMatch(fault -> fault instanceof FaultPlan.ServerStalled)
              ? 1
              : 0;
      healthyThroughSlowTicks +=
          judgedHealthy && run.scenario.chances().get(Chance.SLOW_TICK) > 0 ? 1 : 0;
      abandonedAnswers += run.sim.metrics().total("path.response.abandoned");
      answersBehindTheirChunk += run.sim.metrics().total("path.response.behind");
      spares.add(run.topology.spares());
      gridWidths.add(run.topology.cells());
      multiCellStarts +=
          run.topology.regionCount() < run.topology.cells() * run.topology.cells() ? 1 : 0;
      splits += run.sim.metrics().total("region.split");
      replicasAdded += run.sim.metrics().total("region.replica.added");
      replicasReturned += run.sim.metrics().total("region.replica.returned");
      reassigned += run.sim.metrics().total("region.reassigned");
      reclaimed += run.sim.metrics().total("region.replica.reclaimed");
      keptHalves += run.sim.metrics().total("world.kept");
      if (run.scenario.dispatch() == Dispatch.STREAM) {
        streamedRuns++;
        streamedAnswered += run.out.answered();
      }
      shedNearAPlayer +=
          run.out.dropped(DropReason.SHED) > 0 && run.scenario.counts().get(Count.PLAYERS) > 0
              ? 1
              : 0;
      deepWorlds += run.topology.minY() < 0 ? 1 : 0;
      slowFleets += run.scenario.counts().get(Count.SLOW_NODES) > 0 ? 1 : 0;
      emptyServers += run.scenario.counts().get(Count.PLAYERS) == 0 ? 1 : 0;
      resumes += run.sim.metrics().total("world.resume");
      resnapshots += run.sim.metrics().total("world.resnapshot");
      duplicates += run.fleetCounter("transport.duplicated", Channel.REQUEST);
      linkResets += run.sim.metrics().total("transport.link.reset");
      overtakenProfiles += run.sim.metrics().total("path.profile.unknown");
      encodeRetries += run.sim.metrics().total("offload.retried");
      resends += run.sim.metrics().total("path.resent");
      framesHeld += run.fleetCounter("transport.held.partition", Channel.WORLD);
      restarts += run.sim.incarnations() - 1;
      lostToRestart += run.out.lostToRestart();
      foreignAnswers += run.sim.metrics().total("path.response.foreign");
      pausedRuns +=
          run.faults.faults().stream().anyMatch(fault -> fault instanceof FaultPlan.Paused) ? 1 : 0;
      blackholedRuns +=
          run.faults.faults().stream().anyMatch(fault -> fault instanceof FaultPlan.Blackholed)
              ? 1
              : 0;
      suspicions += run.sim.metrics().total("node.suspected");
      quietPaths += run.sim.metrics().total("node.quiet");
      probesAnswered += run.sim.metrics().total("node.circuit", "state", "closed");
      checksFailed += run.sim.metrics().total("node.check.failed");
      chunksSkipped += run.sim.metrics().total("world.chunks.skipped");
      sharedRequests += run.sim.metrics().total("path.shared");
      multiCellReplicas +=
          run.sim.metrics().total("region.replica.added")
              - run.sim.metrics().total("region.replica.added", "cells", "1");
      heldWithoutShedding += run.heldWithoutShedding() && run.out.answered() > 0 ? 1 : 0;
      heldOverloaded += held && !run.fitsCapacity() && run.out.dropped(DropReason.SHED) > 0 ? 1 : 0;
      for (ModeledNode node : run.sim.nodes()) {
        double rate = run.sim.metrics().gauge("node.capacity", "node", node.id().toString());
        if (rate > 0 && rate < run.scenario.assumedCapacity()) {
          measuredSlower++;
          break;
        }
      }
      refusedUnavailable += run.sim.metrics().counter("path.rejected", "reason", "unavailable");
      failOpenJudged += run.sim.failOpenJudged();
      checkpoints += run.sim.checkpointsCompared();
      deferredReads += run.sim.metrics().total("world.chunks.deferred");
      for (ModeledNode node : run.sim.nodes()) {
        nodesCompared += Invariants.caughtUp(run.controller, node) ? 1 : 0;
        canceledInQueue += node.canceledRequests();
      }
    }

    assertEquals(EnumSet.allOf(DropReason.class), drops, "a drop reason the sweep never reaches");
    assertTrue(spares.size() > 1, "no run varied how many nodes start as spares");
    assertTrue(gridWidths.size() > 1, "the covered square never changed size");
    assertTrue(multiCellStarts > 0, "no run started with a region of several cells");
    assertTrue(splits > 0, "no hot region was ever cut");
    assertTrue(replicasAdded > 0, "no hot region ever took a replica");
    assertTrue(replicasReturned > 0, "no cold replica ever returned to the spares");
    assertTrue(reassigned > 0, "no down holder's region ever went to a spare");
    assertTrue(keptHalves > 0, "no holder of a cut region ever kept its lower side");
    assertTrue(streamedRuns > 0 && streamedAnswered > 0, "no streamed request was ever answered");
    assertTrue(shedNearAPlayer > 0, "no run shed requests with a player online");
    assertTrue(deepWorlds > 0, "no run put the world floor below zero");
    assertTrue(slowFleets > 0, "no run gave the fleet a node slower than the rest");
    assertTrue(emptyServers > 0, "no run ran without a player online");
    assertTrue(resumes > 0, "no node ever resumed its world stream");
    assertTrue(resnapshots > 0, "no node was ever resnapshotted");
    assertTrue(duplicates > 0, "the network never delivered a request frame twice");
    assertTrue(framesHeld > 0, "no world frame was ever held behind a partition");
    assertTrue(linkResets > 0, "no connection was ever reset with the process alive");
    assertTrue(overtakenProfiles > 0, "no request ever overtook the profile it names");
    assertTrue(encodeRetries > 0, "no failed encode was ever retried");
    assertTrue(resends > 0, "no unanswered request was ever sent again");
    assertTrue(nodesCompared > 0, "no node ever caught up far enough to compare its state");
    assertTrue(heldToTheSlo > 0, "no run that answered requests was held to the latency SLO");
    assertTrue(heldAtTheEdge > 0, "no run at the latency edge was held to the SLO");
    assertTrue(healthyThroughAStall > 0, "no healthy fleet was judged through a stalled server");
    assertTrue(
        healthyThroughSlowTicks > 0, "no healthy fleet was judged through a server falling behind");
    assertTrue(abandonedAnswers > 0, "no node ever answered a request after it was abandoned");
    assertTrue(answersBehindTheirChunk > 0, "no node ever searched before its start chunk arrived");
    assertTrue(canceledInQueue > 0, "no cancel ever reached a request still waiting on its node");
    assertTrue(restarts > 0, "the controller process never restarted");
    assertTrue(lostToRestart > 0, "no request was open when its controller restarted");
    assertTrue(foreignAnswers > 0, "no answer for an earlier controller run reached a new one");
    assertTrue(pausedRuns > 0, "no node process was ever paused");
    assertTrue(blackholedRuns > 0, "no node ever lost its datagrams with its link up");
    assertTrue(suspicions > 0, "no node's heartbeats were ever overdue enough to suspect it");
    assertTrue(quietPaths > 0, "no node's request path ever went quiet");
    assertTrue(probesAnswered > 0, "no probe ever brought a node back into service");
    assertTrue(checksFailed > 0, "no unanswered check ever quieted a node's request path");
    assertTrue(chunksSkipped > 0, "no holder was ever spared a chunk another holder lacked");
    assertTrue(sharedRequests > 0, "no replica ever took a request its region's other would shed");
    assertTrue(heldWithoutShedding > 0, "no run was held to the SLO with sheds counted late");
    assertTrue(
        heldOverloaded > 0, "no run that shed past the fleet's capacity was held to the SLO");
    assertTrue(
        measuredSlower > 0, "no node's searches ever measured it below the assumed capacity");
    assertTrue(refusedUnavailable > 0, "no request was ever refused for a node out of service");
    assertTrue(failOpenJudged > 0, "no node that could not answer was sent enough to judge");
    assertTrue(checkpoints > 0, "no node ever reached a mid-run checkpoint");
    assertTrue(deferredReads > 0, "chunk reads never ran past one tick's budget");
    System.out.println(
        "coverage: "
            + heldToTheSlo
            + " of "
            + seeds
            + " seeds held to the SLO, "
            + heldAtTheEdge
            + " at the edge, "
            + checkpoints
            + " checkpoints compared, "
            + keptHalves
            + " halves kept, "
            + reclaimed
            + " replicas reclaimed, "
            + multiCellReplicas
            + " replicas of several cells, "
            + heldWithoutShedding
            + " held with sheds counted late, "
            + heldOverloaded
            + " held while overloaded, "
            + measuredSlower
            + " with a node measured slower than assumed");
  }
}
