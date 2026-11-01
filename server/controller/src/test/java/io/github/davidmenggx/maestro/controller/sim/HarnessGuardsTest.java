package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Offload;
import io.github.davidmenggx.maestro.controller.env.RequestTrace;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Chance;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import maestro.fbs.CellDelta;
import maestro.fbs.CellRun;
import maestro.fbs.ChunkSnapshot;
import maestro.fbs.PathType;
import maestro.fbs.Vec2i;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;
import org.junit.jupiter.api.Test;

/** Tests for the harness machinery: assertions, samplers and misuse guards. */
class HarnessGuardsTest {

  private static final int DRAWS = 300;

  /** Draws of the cheap samplers, enough to reach the ends of settings few runs have. */
  private static final int SAMPLER_DRAWS = 2000;

  /** Any value works for a node created outside a controller. */
  private static final long RUN = 7;

  private static final Topology ONE_REGION =
      Topology.DEFAULT.withCells(1).withNodes(1).withSpares(0);

  /** A square area a modeled node is assigned outside a controller. */
  private static final int AREA = RegionLayout.CELL;

  /** Highest cost of one request in the sampled plans' fleet. */
  private static final int COST = 375;

  private static final Pos ORIGIN = new Pos(1, 40, 1);
  private static final Pos FAR = new Pos(9, 40, 9);

  /**
   * A controller that waits far past the grace the run expects leaves a region to a holder that
   * cannot answer while a spare is up, and the run says so. With its own grace it moves the region.
   */
  @Test
  void aRegionLeftToAHolderThatCannotServeIsReported() {
    NodeId holder = NodeId.of(0);
    FaultPlan plan = FaultPlan.reliable().with(new FaultPlan.Blackholed(40, holder, true));
    Scenario shortGrace = Scenario.DEFAULT.with(Count.GRACE_TICKS, 30);

    SimRun late =
        SimRun.withControllerGrace(11L, plan, 300, Topology.DEFAULT, shortGrace, 1000).go();
    assertEquals(1, late.strandedRegions().size(), late.describe());
    assertTrue(late.strandedRegions().get(0).contains("could not serve"), late.describe());
    assertEquals(1, late.regions().regionOf(holder), late.describe());

    SimRun onTime = SimRun.of(11L, plan, 300, Topology.DEFAULT, shortGrace).go();
    assertEquals(List.of(), onTime.strandedRegions(), onTime.describe());
    assertEquals(Regions.NONE, onTime.regions().regionOf(holder), onTime.describe());
  }

  @Test
  void aControllerAdmittingPastWhatItsNodeServesIsCaughtUnderOverload() {
    Topology alone = Topology.DEFAULT.withCells(1).withNodes(1).withSpares(0);
    Scenario overloaded =
        Scenario.SATURATED
            .with(Count.WORKER_CAPACITY, 4)
            .with(Count.COST_SPREAD_PERCENT, 0)
            .with(Count.CAPACITY_ESTIMATE_PERCENT, 75);

    // It takes the node to serve four times what it does until its searches are measured.
    SimRun greedy =
        SimRun.withControllerCapacity(3L, FaultPlan.reliable(), 400, alone, overloaded, 16).go();
    assertTrue(greedy.admissionFits(), greedy.describe());
    assertTrue(
        greedy.violations().stream().anyMatch(violation -> violation.startsWith("p99 latency")),
        greedy.describe());

    SimRun honest = SimRun.of(3L, FaultPlan.reliable(), 400, alone, overloaded).go();
    assertEquals(List.of(), honest.violations(), honest.describe());
  }

  @Test
  void sampledScenariosSpanTheirRangeAndStayValid() {
    Map<Count, Span> counts = new EnumMap<>(Count.class);
    Map<Chance, Span> chances = new EnumMap<>(Chance.class);
    int keptUp = 0;

    for (int seed = 0; seed < SAMPLER_DRAWS; seed++) {
      Scenario scenario = Scenario.sample(SeededRng.of(seed).derive("fuzz"));
      keptUp += scenario.chances().get(Chance.SLOW_TICK) == 0 ? 1 : 0;
      for (Count setting : Count.values()) {
        counts.computeIfAbsent(setting, unseen -> new Span()).see(scenario.counts().get(setting));
      }
      for (Chance setting : Chance.values()) {
        chances.computeIfAbsent(setting, unseen -> new Span()).see(scenario.chances().get(setting));
      }
    }

    // A sample that always returns one setting would not fuzz anything.
    for (Count setting : Count.values()) {
      counts.get(setting).assertCovers(setting, setting.floor(), setting.ceiling());
    }
    for (Chance setting : Chance.values()) {
      chances.get(setting).assertCovers(setting, 0, setting.ceiling());
    }
    // The latency SLO applies only to a server that keeps up, so most draws must, and some not.
    assertTrue(keptUp > SAMPLER_DRAWS / 2, keptUp + " of " + SAMPLER_DRAWS + " servers kept up");
    assertTrue(keptUp < SAMPLER_DRAWS, "no sampled server fell behind");
  }

  @Test
  void sampledTopologiesSpanTheirRangeAndStayChunkAligned() {
    Set<Integer> spares = new LinkedHashSet<>();
    Set<Integer> gridWidths = new LinkedHashSet<>();
    Set<Integer> regionCounts = new LinkedHashSet<>();
    Set<Integer> floors = new LinkedHashSet<>();
    Span height = new Span();
    Span relief = new Span();

    for (int seed = 0; seed < DRAWS; seed++) {
      Topology topology = Topology.sample(SeededRng.of(seed).derive("fuzz"));
      spares.add(topology.spares());
      gridWidths.add(topology.cells());
      regionCounts.add(topology.regionCount());
      assertEquals(topology.regionCount() + topology.spares(), topology.nodeCount());
      floors.add(topology.minY());
      height.see(topology.height());
      relief.see(topology.relief());
      assertEquals(0, topology.extent() % Chunks.SIZE, topology.toString());
      assertEquals(0, Math.floorMod(topology.min(), Chunks.SIZE), topology.toString());
      // Centered, so the grid covers positions on both sides of the origin.
      assertTrue(topology.min() < 0 && topology.min() + topology.extent() > 0, topology.toString());
      assertTrue(topology.nodeCount() <= Topology.maxNodeCount(), topology.toString());
    }

    assertEquals(
        IntStream.rangeClosed(0, Topology.MAX_SPARES).boxed().collect(Collectors.toSet()), spares);
    assertEquals(Set.copyOf(Topology.floors()), floors);
    assertEquals(
        IntStream.rangeClosed(1, Topology.MAX_CELLS).boxed().collect(Collectors.toSet()),
        gridWidths);
    // From one region for the whole square to one per cell.
    assertEquals(
        IntStream.rangeClosed(1, Topology.MAX_CELLS * Topology.MAX_CELLS)
            .boxed()
            .collect(Collectors.toSet()),
        regionCounts);
    height.assertCovers("height", Topology.MIN_HEIGHT, Topology.MAX_HEIGHT);
    relief.assertCovers("relief", 0, Topology.MAX_RELIEF);
  }

  @Test
  void sampledFleetFaultsCoverEveryKindAndStayInsideTheActiveWindow() {
    long active = 100;
    Set<String> kinds = new LinkedHashSet<>();
    Span faultCount = new Span();
    Span minLatency = new Span();
    Span maxLatency = new Span();
    Span failureRate = new Span();
    boolean sawPermanentLoss = false;
    boolean sawEdge = false;
    long longestSpan = 0;

    for (int seed = 0; seed < SAMPLER_DRAWS; seed++) {
      FaultPlan plan = FaultPlan.sample(SeededRng.of(seed).derive("fuzz"), 4, active, COST);
      faultCount.see(plan.faults().size());
      minLatency.see(plan.minLatencySteps());
      maxLatency.see(plan.maxLatencySteps());
      failureRate.see(plan.offloadFailureRate());
      assertTrue(plan.maxLatencySteps() >= plan.minLatencySteps(), plan.toString());
      sawEdge |= plan.clean() && plan.maxLatencySteps() == FaultPlan.edgeLatency(COST);

      int died = 0;
      int recovered = 0;
      for (FaultPlan.Fault fault : plan.faults()) {
        kinds.add(fault.getClass().getSimpleName());
        assertTrue(fault.tick() >= 1 && fault.tick() < active, "fault at tick " + fault.tick());
        assertTrue(
            !(fault instanceof FaultPlan.NodeFault nodeFault) || nodeFault.node().id() < 4,
            fault.toString());
        if (fault instanceof FaultPlan.NodeDied) {
          died++;
        } else if (fault instanceof FaultPlan.NodeRecovered) {
          recovered++;
        }
      }
      sawPermanentLoss |= died > recovered;
      // A fault with a start and an end is drawn as a pair.
      for (int i = 0; i + 1 < plan.faults().size(); i++) {
        FaultPlan.Fault start = plan.faults().get(i);
        FaultPlan.Fault end = plan.faults().get(i + 1);
        if (start.getClass() == end.getClass() && !(start instanceof FaultPlan.NodeDied)) {
          longestSpan = Math.max(longestSpan, end.tick() - start.tick());
        }
      }
    }

    // Each fault kind needs a different recovery, so the sweep must cover all of them.
    assertEquals(
        Set.of(
            "NodeDied",
            "NodeRecovered",
            "Partitioned",
            "Stalled",
            "Paused",
            "Blackholed",
            "LinkReset",
            "ControllerRestarted",
            "ServerStalled"),
        kinds);
    // Some faults outlast the detectors, or a node is never noticed and probed.
    assertTrue(
        longestSpan > FaultPlan.MAX_FAULT_SPAN_TICKS,
        "no fault outlasted " + FaultPlan.MAX_FAULT_SPAN_TICKS + " ticks: " + longestSpan);
    // Faults must be able to accumulate on one node, to reach a second failure during a
    // recovery. A paired fault adds two entries.
    faultCount.assertCovers("fault count", 0, FaultPlan.MAX_FAULTS);
    minLatency.assertCovers("min latency", 1, FaultPlan.MAX_MIN_LATENCY_STEPS);
    maxLatency.assertCovers(
        "max latency", 1, FaultPlan.MAX_MIN_LATENCY_STEPS + FaultPlan.MAX_LATENCY_SPAN_STEPS);
    // Clean plans at the edge make a round trip and a search take nearly a tick.
    assertTrue(sawEdge, "no clean plan put its latency at the edge");
    // Some plans fail encodes often enough that one fails every attempt.
    failureRate.assertCovers("offload failure rate", 0, FaultPlan.MAX_FLAKY_ENCODE_RATE);
    assertTrue(sawPermanentLoss, "no node was ever lost for the rest of the run");
    assertEquals(
        List.of(), FaultPlan.sample(SeededRng.of(1).derive("fuzz"), 0, active, COST).faults());
  }

  @Test
  void aNodeToldToResumeFromAStreamItLostSaysSo() {
    Simulation sim = new Simulation(5L, FaultPlan.reliable());
    ModeledNode node = linked(sim);

    // The node holds nothing, so it cannot honor a resume.
    SimWorld world = new SimWorld(ONE_REGION, 2, sim.rng().derive("terrain"));
    node.receive(
        Channel.WORLD, ByteBuffer.wrap(Frames.assignment(RUN, 1, AREA, AREA, 0, true, world)));

    assertEquals(1, node.violations().size(), node.violations().toString());
    assertTrue(node.violations().get(0).contains("resume"), node.violations().toString());
  }

  @Test
  void aGapInTheWorldStreamIsReported() {
    Simulation sim = new Simulation(3L, FaultPlan.reliable());
    ModeledNode node = linked(sim);
    SimWorld world = new SimWorld(ONE_REGION, 2, sim.rng().derive("terrain"));
    world.loadChunk(0, 0, 0);

    node.receive(
        Channel.WORLD, ByteBuffer.wrap(Frames.assignment(RUN, 1, AREA, AREA, 0, false, world)));
    node.receive(
        Channel.WORLD,
        ByteBuffer.wrap(Frames.chunkSnapshot(1, 0, 0, Frames.readChunk(world, 0, 0))));
    assertEquals(List.of(), node.violations());
    assertEquals(1, node.lastSequence());

    List<WorldEvent.BlockChanged> cells =
        List.of(new WorldEvent.BlockChanged(0, new Pos(1, 40, 1), (byte) PathType.WATER, 0));
    node.receive(Channel.WORLD, ByteBuffer.wrap(Frames.delta(2, cells, 0, 0)));
    assertEquals(List.of(), node.violations());

    node.receive(Channel.WORLD, ByteBuffer.wrap(Frames.delta(4, cells, 0, 0)));
    assertFalse(node.violations().isEmpty(), "a skipped sequence went unreported");
  }

  @Test
  void theModeledNodeRefusesWhatTheRealNodeRefusesAndStartsOver() {
    Topology grid = Topology.DEFAULT;
    SimWorld world = new SimWorld(grid, 2, SeededRng.of(1).derive("terrain"));
    SimWorld taller =
        new SimWorld(grid.withHeight(grid.height() + 16), 2, SeededRng.of(1).derive("terrain"));
    world.loadChunk(0, 0, 0);
    WorldEvent.BlockChanged outside =
        new WorldEvent.BlockChanged(0, new Pos(AREA, 40, 1), (byte) PathType.WATER, 0);
    WorldEvent.BlockChanged inside =
        new WorldEvent.BlockChanged(0, new Pos(1, 40, 1), (byte) PathType.WATER, 0);

    Map<String, byte[]> refused = new LinkedHashMap<>();
    refused.put("delta outside the area", Frames.delta(2, List.of(inside, outside), 0, 0));
    refused.put("sequence 0", Frames.chunkCleared(0, 0, 0));
    refused.put("chunk outside the area", Frames.chunkCleared(2, AREA / Chunks.SIZE, 0));
    refused.put("repeated sequence", Frames.chunkCleared(1, 0, 0));
    refused.put("resume of another region", Frames.assignment(RUN, 2, AREA, AREA, 1, true, world));
    refused.put("resume of another run", Frames.assignment(RUN + 1, 1, AREA, AREA, 1, true, world));
    refused.put("resume of another shape", Frames.assignment(RUN, 1, AREA, AREA, 1, true, taller));
    refused.put(
        "resume of another rectangle", Frames.assignment(RUN, 1, AREA, 2 * AREA, 1, true, world));
    refused.put(
        "resume from another sequence", Frames.assignment(RUN, 1, AREA, AREA, 2, true, world));
    refused.put("chunk a column short", chunkWithColumns(2, Chunks.SIZE * Chunks.SIZE - 1, 0));
    refused.put(
        "chunk run too tall", chunkWithColumns(2, Chunks.SIZE * Chunks.SIZE, Cells.MAX_FLOOR + 1));
    refused.put("delta cell too tall", deltaWithFloor(2, Cells.MAX_FLOOR + 1));
    refused.put(
        "fresh assignment behind the held sequence",
        Frames.assignment(RUN, 1, AREA, AREA, 0, false, world));
    refused.put(
        "fresh assignment of another run",
        Frames.assignment(RUN + 1, 1, AREA, AREA, 1, false, world));

    for (Map.Entry<String, byte[]> entry : refused.entrySet()) {
      Simulation sim = new Simulation(5L, FaultPlan.reliable());
      ModeledNode node = linked(sim);
      node.receive(
          Channel.WORLD, ByteBuffer.wrap(Frames.assignment(RUN, 1, AREA, AREA, 0, false, world)));
      node.receive(
          Channel.WORLD,
          ByteBuffer.wrap(Frames.chunkSnapshot(1, 0, 0, Frames.readChunk(world, 0, 0))));
      assertTrue(node.hasState(), entry.getKey());
      long before = node.stateDigest();

      node.receive(Channel.WORLD, ByteBuffer.wrap(entry.getValue()));

      assertEquals(1, node.violations().size(), entry.getKey() + ": " + node.violations());
      // Like the real node, it drops all state instead of keeping half a stream.
      assertFalse(node.hasState(), entry.getKey());
      assertEquals(0, node.lastSequence(), entry.getKey());
      assertTrue(before != node.stateDigest(), entry.getKey());
    }
  }

  @Test
  void theFirstFrameMustFollowTheAssignment() {
    Simulation sim = new Simulation(3L, FaultPlan.reliable());
    ModeledNode node = linked(sim);
    SimWorld world = new SimWorld(ONE_REGION, 2, sim.rng().derive("terrain"));
    world.loadChunk(0, 0, 0);
    int size = AREA;

    node.receive(
        Channel.WORLD, ByteBuffer.wrap(Frames.assignment(RUN, 1, size, size, 7, false, world)));
    assertEquals(7, node.lastSequence());
    node.receive(
        Channel.WORLD,
        ByteBuffer.wrap(Frames.chunkSnapshot(9, 0, 0, Frames.readChunk(world, 0, 0))));

    assertEquals(1, node.violations().size(), node.violations().toString());
    assertTrue(node.violations().get(0).contains("gap"), node.violations().toString());
    assertFalse(node.assigned());
  }

  @Test
  void aCanceledRequestIsNeverServed() {
    Simulation sim = new Simulation(9L, FaultPlan.reliable());
    ModeledNode node = linked(sim);
    node.receive(Channel.CONTROL, ByteBuffer.wrap(Frames.mobClass(profile((short) 3))));
    node.receive(Channel.REQUEST, requests(walk(5), walk(6)));

    // An unknown id is remembered, so its request is dropped when it arrives after the cancel.
    node.receive(Channel.REQUEST, ByteBuffer.wrap(Frames.cancels(RUN, 0, List.of(5L, 9L))));
    node.receive(Channel.REQUEST, requests(walk(9)));
    node.serve(0);

    assertEquals(List.of(), node.violations());
    assertEquals(1, node.canceledRequests());
    assertEquals(1, requestFramesSent(sim));
    // An answered request is left alone, and a copy still gets its answer.
    node.receive(Channel.REQUEST, ByteBuffer.wrap(Frames.cancels(RUN, 0, List.of(6L))));
    node.receive(Channel.REQUEST, requests(walk(6)));
    assertEquals(2, requestFramesSent(sim));
  }

  @Test
  void aLinkResetForgetsProfilesAndRequestsButKeepsTheWorld() {
    Simulation sim = new Simulation(9L, FaultPlan.reliable());
    ModeledNode node = linked(sim);
    SimWorld world = new SimWorld(ONE_REGION, 2, sim.rng().derive("terrain"));
    world.loadChunk(0, 0, 0);
    node.receive(
        Channel.WORLD, ByteBuffer.wrap(Frames.assignment(RUN, 1, AREA, AREA, 0, false, world)));
    node.receive(
        Channel.WORLD,
        ByteBuffer.wrap(Frames.chunkSnapshot(1, 0, 0, Frames.readChunk(world, 0, 0))));
    node.receive(Channel.CONTROL, ByteBuffer.wrap(Frames.mobClass(profile((short) 3))));
    node.receive(Channel.REQUEST, requests(walk(5)));
    long digest = node.stateDigest();

    node.connectionLost();
    node.serve(0);

    assertEquals(0, node.knownClassCount());
    assertEquals(0, requestFramesSent(sim), "a request from the old link was served");
    assertTrue(node.hasState());
    assertEquals(digest, node.stateDigest());
    // A request with no link is dropped, not refused.
    node.receive(Channel.REQUEST, requests(walk(6)));
    assertEquals(0, requestFramesSent(sim));
  }

  @Test
  void aRequestNamingAnUnknownProfileIsAnsweredAtOnce() {
    Simulation sim = new Simulation(9L, FaultPlan.reliable());
    ModeledNode node = linked(sim);

    node.receive(Channel.REQUEST, requests(walk(5)));

    // A request arriving before its profile is network behavior, not a violation. It is
    // answered at once, without a worker.
    assertEquals(List.of(), node.violations());
    assertEquals(1, requestFramesSent(sim));
  }

  @Test
  void aWorkerServesARequestInItsOwnTimeAndAnswersCanFinishOutOfOrder() {
    Simulation sim = new Simulation(9L, FaultPlan.reliable());
    NodeId id = NodeId.of(0);
    ModeledNode node = sim.addNode(id, new ModeledNode.Service(2, 10, 400));
    node.connect(Channel.CONTROL, ByteBuffer.wrap(Frames.joinControl(RUN, 1)));
    node.receive(Channel.CONTROL, ByteBuffer.wrap(Frames.mobClass(profile((short) 3))));
    node.receive(Channel.REQUEST, requests(walk(1), walk(2), walk(3)));

    // Two run at once and the third waits for a worker.
    assertEquals(3, node.depth());
    assertTrue(node.backlogged());
    sim.run(event -> {}, (tick, input) -> {}, 1);
    assertEquals(0, node.depth());
    assertEquals(3, node.servedRequests());
  }

  @Test
  void aJoinIsRefusedUnlessItNamesTheCurrentOrALaterAttempt() {
    Simulation sim = new Simulation(9L, FaultPlan.reliable());
    ModeledNode node = sim.addNode(NodeId.of(0), ModeledNode.Service.MANUAL);
    node.connect(Channel.CONTROL, ByteBuffer.wrap(Frames.joinControl(RUN, 2)));
    node.connect(Channel.WORLD, ByteBuffer.wrap(Frames.joinWorld(RUN, 2)));
    assertEquals(0, sim.metrics().total("transport.refused"));

    // An earlier attempt, a second connection for a joined port, and a first frame that
    // is not a `Join` are refused.
    node.connect(Channel.WORLD, ByteBuffer.wrap(Frames.joinWorld(RUN, 1)));
    node.connect(Channel.CONTROL, ByteBuffer.wrap(Frames.joinControl(RUN, 2)));
    node.connect(Channel.CONTROL, ByteBuffer.wrap(Frames.mobClass(profile((short) 3))));
    assertEquals(3, sim.metrics().total("transport.refused"));

    // Another run replaces the link, which forgets its profiles.
    node.receive(Channel.CONTROL, ByteBuffer.wrap(Frames.mobClass(profile((short) 3))));
    assertEquals(1, node.knownClassCount());
    node.connect(Channel.CONTROL, ByteBuffer.wrap(Frames.joinControl(RUN + 1, 1)));
    assertEquals(0, node.knownClassCount());
    assertEquals(3, sim.metrics().total("transport.refused"));
  }

  @Test
  void aProfileThatBreaksTheContractDropsTheLink() {
    Simulation sim = new Simulation(9L, FaultPlan.reliable());
    ModeledNode node = linked(sim);
    FlatBufferBuilder builder = new FlatBufferBuilder(128);
    int malus = maestro.fbs.MobClass.createMalusVector(builder, new float[PathTypes.count()]);
    int body =
        maestro.fbs.MobClass.createMobClass(
            builder, 3, 0f, 1.8f, malus, 0.6f, true, false, false, false);
    builder.finish(
        maestro.fbs.ControlFrame.createControlFrame(
            builder, maestro.fbs.ControlMsg.MobClass, body));

    node.receive(Channel.CONTROL, ByteBuffer.wrap(builder.sizedByteArray()));

    assertEquals(1, node.violations().size(), node.violations().toString());
    assertEquals(0, node.knownClassCount());
    node.receive(Channel.REQUEST, requests(walk(5)));
    assertEquals(0, requestFramesSent(sim), "the link survived a broken profile");
  }

  @Test
  void theSloIsStrictAndCountsAMissedRequestAsLate() {
    List<Double> fast = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      fast.add(2.0);
    }
    assertEquals(List.of(), Invariants.checkSlo(fast, 0));

    // Two misses in 202 requests leave the p99 on an answer, and a third puts it on a miss.
    assertEquals(List.of(), Invariants.checkSlo(fast, 2));
    assertEquals(1, Invariants.checkSlo(fast, 3).size());
    // A run that refuses everything has only misses.
    assertEquals(1, Invariants.checkSlo(List.of(), 1).size());

    // The SLO is below 3 ticks, so a p99 of exactly 3 misses it.
    List<Double> late = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      late.add(3.0);
    }
    assertEquals(1, Invariants.checkSlo(late, 0).size());
  }

  @Test
  void aMissIsARejectionForQueueDepthATimeoutANodeLostOrAServableRequestLeftUnserved() {
    RecordingOutput out = recorder(new SimWorld(Topology.DEFAULT, 1, SeededRng.of(1)));
    DropReason[] reasons = DropReason.values();
    for (int mob = 0; mob < reasons.length; mob++) {
      out.issued(request(mob, 0, ORIGIN));
      if (reasons[mob] == DropReason.SUPERSEDED) {
        out.issued(request(mob, 0, ORIGIN));
        out.pathDropped(mob, DropReason.SUPERSEDED);
        out.pathDropped(mob, DropReason.CANCELED);
      } else {
        out.pathDropped(mob, reasons[mob]);
      }
    }
    assertEquals(List.of(), out.ledgerViolations());
    // Queue depth, a timeout and a node lost.
    assertEquals(3, out.sloMisses());

    // Requests from a chunk loaded long enough to be on its node, dropped for want of one.
    SimWorld world = new SimWorld(Topology.DEFAULT, 1, SeededRng.of(1));
    world.loadChunk(Chunks.of(ORIGIN.x()), Chunks.of(ORIGIN.z()), 0);
    RecordingOutput servable = recorder(world);
    servable.issued(request(1, 1, ORIGIN));
    servable.pathDropped(1, DropReason.UNAVAILABLE);
    servable.issued(request(2, 100, ORIGIN));
    servable.pathDropped(2, DropReason.UNAVAILABLE);
    servable.issued(request(3, 100, ORIGIN));
    servable.pathDropped(3, DropReason.NO_NODE);
    assertEquals(List.of(), servable.ledgerViolations());
    assertEquals(2, servable.sloMisses());
  }

  @Test
  void aChunkThatStaysAfterItWasAboutToLeaveCountsFromWhenItCouldBeReadAgain() {
    SimWorld world = new SimWorld(Topology.DEFAULT, 1, SeededRng.of(1));
    int chunkX = Chunks.of(ORIGIN.x());
    int chunkZ = Chunks.of(ORIGIN.z());
    world.loadChunk(chunkX, chunkZ, 0);
    world.beginLeaving(chunkX, chunkZ);
    RecordingOutput out = recorder(world);

    // Not servable while it cannot be read, nor just after, though it loaded long before.
    out.issued(request(1, 100, ORIGIN));
    out.pathDropped(1, DropReason.UNAVAILABLE);
    world.stayLoaded(chunkX, chunkZ, 100);
    assertEquals(100, world.readableSince(chunkX, chunkZ));
    out.issued(request(2, 101, ORIGIN));
    out.pathDropped(2, DropReason.UNAVAILABLE);
    // Readable long enough to be on its node.
    out.issued(request(3, 200, ORIGIN));
    out.pathDropped(3, DropReason.UNAVAILABLE);

    assertEquals(List.of(), out.ledgerViolations());
    assertEquals(1, out.sloMisses());
    // A chunk that left is not made readable by a late stay.
    world.unloadChunk(chunkX, chunkZ);
    world.stayLoaded(chunkX, chunkZ, 300);
    assertEquals(-1, world.readableSince(chunkX, chunkZ));
  }

  @Test
  void theLedgerMatchesEachOutcomeToItsRequest() {
    RecordingOutput out = recorder(new SimWorld(Topology.DEFAULT, 1, SeededRng.of(1)));
    WorldEvent.PathRequested first = request(1, 0, ORIGIN);
    WorldEvent.PathRequested repeat = request(1, 1, FAR);
    out.issued(first);
    out.issued(repeat);
    out.pathDropped(1, DropReason.SUPERSEDED);
    out.handled(repeat);
    out.pathComputed(answer(repeat));
    assertEquals(List.of(), out.ledgerViolations());
    assertEquals(1, out.latencies().size());

    // A repeat leaves the older request open, then the newer one is dropped as if it were alone.
    out.issued(request(2, 0, ORIGIN));
    WorldEvent.PathRequested kept = request(2, 1, FAR);
    out.issued(kept);
    out.handled(kept);
    out.pathDropped(2, DropReason.SHED);
    assertEquals(3, out.ledgerViolations().size(), out.ledgerViolations().toString());

    // An answer for a request the mob no longer has open.
    RecordingOutput wrong = recorder(new SimWorld(Topology.DEFAULT, 1, SeededRng.of(1)));
    wrong.issued(request(3, 4, FAR));
    wrong.pathComputed(answer(request(3, 2, ORIGIN)));
    assertEquals(1, wrong.ledgerViolations().size(), wrong.ledgerViolations().toString());

    // A path said to reach its target must end there.
    RecordingOutput stopsShort = recorder(new SimWorld(Topology.DEFAULT, 1, SeededRng.of(1)));
    WorldEvent.PathRequested toFar = request(6, 0, FAR);
    stopsShort.issued(toFar);
    stopsShort.pathComputed(
        new WorldOutput.PathResult(
            6,
            FAR,
            true,
            List.of(new WorldOutput.Node(ORIGIN, (byte) PathType.WALKABLE)),
            1.0f,
            0));
    assertEquals(1, stopsShort.ledgerViolations().size(), stopsShort.ledgerViolations().toString());

    // An outcome with nothing open, and a request with no outcome.
    RecordingOutput unmatched = recorder(new SimWorld(Topology.DEFAULT, 1, SeededRng.of(1)));
    unmatched.issued(request(4, 0, ORIGIN));
    unmatched.pathDropped(5, DropReason.MOB_GONE);
    assertEquals(2, unmatched.ledgerViolations().size(), unmatched.ledgerViolations().toString());
  }

  @Test
  void aRegionStreamThatGoesBackIsReported() {
    Simulation sim = new Simulation(3L, FaultPlan.reliable());
    ModeledNode first = linked(sim, NodeId.of(0));
    ModeledNode second = linked(sim, NodeId.of(1));
    SimWorld world = new SimWorld(ONE_REGION, 2, sim.rng().derive("terrain"));
    world.loadChunk(0, 0, 0);
    int size = AREA;

    sendWorld(sim, first, Frames.assignment(RUN, 1, size, size, 4, false, world));
    sendWorld(sim, first, Frames.chunkSnapshot(5, 0, 0, Frames.readChunk(world, 0, 0)));
    assertEquals(List.of(), sim.streamViolations());

    // A fresh assignment may start anywhere, but never behind a frame sent to another holder.
    sendWorld(sim, second, Frames.assignment(RUN, 1, size, size, 2, false, world));
    sendWorld(sim, second, Frames.chunkSnapshot(3, 0, 0, Frames.readChunk(world, 0, 0)));
    assertEquals(List.of(), first.violations());
    assertEquals(List.of(), second.violations());
    assertEquals(1, sim.streamViolations().size(), sim.streamViolations().toString());

    // Another controller run has a stream of its own.
    ModeledNode third = linked(sim, NodeId.of(2), RUN + 1);
    sendWorld(sim, third, Frames.assignment(RUN + 1, 1, size, size, 0, false, world));
    sendWorld(sim, third, Frames.chunkSnapshot(1, 0, 0, Frames.readChunk(world, 0, 0)));
    assertEquals(1, sim.streamViolations().size(), sim.streamViolations().toString());
  }

  /** A world frame the controller sends, delivered at once. */
  private static void sendWorld(Simulation sim, ModeledNode node, byte[] frame) {
    sim.worldFrameSent(node.id(), frame);
    node.receive(Channel.WORLD, ByteBuffer.wrap(frame));
  }

  @Test
  void aNodeAtACheckpointMustHoldWhatTheWorldHeld() {
    Simulation sim = new Simulation(3L, FaultPlan.reliable());
    ModeledNode node = linked(sim);
    SimWorld world = new SimWorld(ONE_REGION, 2, sim.rng().derive("terrain"));
    world.loadChunk(0, 0, 0);
    int size = AREA;
    node.receive(
        Channel.WORLD, ByteBuffer.wrap(Frames.assignment(RUN, 1, size, size, 0, false, world)));
    node.receive(
        Channel.WORLD,
        ByteBuffer.wrap(Frames.chunkSnapshot(1, 0, 0, Frames.readChunk(world, 0, 0))));
    long right = node.stateDigest();

    sim.checkpoint(RUN, 1, 2, right);
    sim.checkpoint(RUN, 1, 3, right);
    List<WorldEvent.BlockChanged> cells =
        List.of(new WorldEvent.BlockChanged(0, new Pos(1, 40, 1), (byte) PathType.WATER, 0));
    node.receive(Channel.WORLD, ByteBuffer.wrap(Frames.delta(2, cells, 0, 0)));
    assertEquals(1, sim.streamViolations().size(), sim.streamViolations().toString());
    assertEquals(1, sim.checkpointsCompared());

    // A fresh assignment past a checkpoint means the node never reaches it, so it is not
    // compared.
    node.receive(
        Channel.WORLD, ByteBuffer.wrap(Frames.assignment(RUN, 1, size, size, 3, false, world)));
    node.receive(Channel.WORLD, ByteBuffer.wrap(Frames.chunkCleared(4, 0, 0)));
    assertEquals(1, sim.checkpointsCompared());
  }

  @Test
  void aNodeLeftBehindWhenTheRunEndsIsReported() {
    // A one-tick run ends with the chunks encoded but not yet delivered.
    List<WorldEvent> loads = new ArrayList<>();
    int min = Chunks.of(Topology.DEFAULT.min());
    for (int chunkX = min; chunkX < min + Topology.DEFAULT.chunksPerSide(); chunkX++) {
      for (int chunkZ = min; chunkZ < min + Topology.DEFAULT.chunksPerSide(); chunkZ++) {
        loads.add(new WorldEvent.ChunkLoaded(0, chunkX, chunkZ));
      }
    }

    List<String> violations = SimRun.scripted(1L, FaultPlan.reliable(), 1, loads).go().violations();

    assertTrue(
        violations.stream().anyMatch(v -> v.contains("has not caught up")), violations.toString());
  }

  @Test
  void theWorkloadVariesWhichKindOfEventComesFirstInATick() {
    SimWorld world = new SimWorld(Topology.DEFAULT, 2, SeededRng.of(1).derive("terrain"));
    Workload workload =
        new SyntheticWorkload(
            world,
            SeededRng.of(1).derive("workload"),
            Long.MAX_VALUE,
            Scenario.DEFAULT.with(Chance.CHUNK_CHURN, 1.0));
    boolean changeFirst = false;
    boolean chunkFirst = false;
    for (long tick = 0; tick < DRAWS; tick++) {
      List<WorldEvent> events = new ArrayList<>();
      workload.tick(tick, events::add);
      int change = firstIndex(events, WorldEvent.BlockChanged.class);
      int chunk =
          Math.max(
              firstIndex(events, WorldEvent.ChunkLoaded.class),
              firstIndex(events, WorldEvent.ChunkUnloaded.class));
      if (tick > 0 && change >= 0 && chunk >= 0) {
        changeFirst |= change < chunk;
        chunkFirst |= chunk < change;
      }
    }
    assertTrue(changeFirst, "a block change never came before a chunk event");
    assertTrue(chunkFirst, "a chunk event never came before a block change");
  }

  @Test
  void theMetricsTheAssertionsLeanOnAreRight() {
    SimMetrics metrics = new SimMetrics();
    for (int i = 1; i <= 100; i++) {
      metrics.record("latency", i);
    }

    assertEquals(50.0, metrics.percentile("latency", 0.5));
    assertEquals(99.0, metrics.percentile("latency", 0.99));
    assertEquals(100.0, metrics.percentile("latency", 1.0));
    assertEquals(1.0, metrics.percentile("latency", 0.0));
    assertTrue(Double.isNaN(metrics.percentile("nothing", 0.99)));
    // Misses rank above every recorded value.
    List<Double> latencies = new ArrayList<>();
    for (int i = 1; i <= 100; i++) {
      latencies.add((double) i);
    }
    assertEquals(100.0, SimMetrics.percentileWithMisses(latencies, 0.99, 1));
    assertEquals(Double.POSITIVE_INFINITY, SimMetrics.percentileWithMisses(latencies, 0.99, 2));
    assertEquals(Double.POSITIVE_INFINITY, SimMetrics.percentileWithMisses(List.of(), 0.5, 1));
    assertTrue(Double.isNaN(SimMetrics.percentileWithMisses(List.of(), 0.99, 0)));

    metrics.count("frames", 2, "node", "node-0");
    metrics.count("frames", 5, "node", "node-1");
    metrics.count("frames-held", 9, "node", "node-0");

    assertEquals(2, metrics.counter("frames", "node", "node-0"));
    assertEquals(5, metrics.counter("frames", "node", "node-1"));
    assertEquals(0, metrics.counter("frames"));
    // A total covers every label of one counter and stops at the name boundary.
    assertEquals(7, metrics.total("frames"));
    assertEquals(9, metrics.total("frames-held"));
    assertThrows(IllegalArgumentException.class, () -> metrics.count("frames", 1, "unpaired"));

    // A gauge keeps its last value.
    metrics.gauge("depth", 3, "node", "node-0");
    metrics.gauge("depth", 1, "node", "node-0");
    assertEquals(1, metrics.gauge("depth", "node", "node-0"));
    assertEquals(0, metrics.gauge("depth"));
  }

  @Test
  void aFaultPlanRejectsImpossibleSettings() {
    assertThrows(IllegalArgumentException.class, () -> FaultPlan.reliable().withDropRate(1.0));
    assertThrows(
        IllegalArgumentException.class, () -> FaultPlan.reliable().withDuplicateRate(-0.1));
    assertThrows(
        IllegalArgumentException.class, () -> FaultPlan.reliable().withOffloadFailureRate(1.0));
    assertThrows(
        IllegalArgumentException.class, () -> new FaultPlan(0, 5, 0.0, 0.0, 0.0, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new FaultPlan(9, 5, 0.0, 0.0, 0.0, List.of()));
  }

  @Test
  void aScenarioRejectsImpossibleSettings() {
    assertThrows(IllegalArgumentException.class, () -> Scenario.DEFAULT.with(Count.MOBS, -1));
    assertThrows(
        IllegalArgumentException.class, () -> Scenario.DEFAULT.with(Count.WORKER_CAPACITY, 0));
    assertThrows(IllegalArgumentException.class, () -> Scenario.DEFAULT.with(Chance.REQUEST, 1.5));
    assertThrows(
        IllegalArgumentException.class, () -> Scenario.DEFAULT.with(Chance.CHUNK_CHURN, -0.1));
    // A table with a missing setting is rejected, not filled in with a guess.
    assertThrows(
        IllegalArgumentException.class,
        () -> new Scenario(Map.of(Count.MOBS, 1), Scenario.DEFAULT.chances()));
  }

  @Test
  void aSlowNodeIsTheOnlyOneServedAtAReducedRate() {
    // A worker at 125 steps a request serves 8 a tick, and a slow node takes four times as
    // long per request.
    assertEquals(new ModeledNode.Service(1, 63, 187), scenario(8, 0).serviceOf(0));
    assertEquals(new ModeledNode.Service(1, 250, 750), scenario(8, 1).serviceOf(0));
    assertEquals(new ModeledNode.Service(1, 63, 187), scenario(8, 1).serviceOf(1));
    assertEquals(750, scenario(8, 1).maxCostSteps(2));
    // With no spread, every request costs the mean.
    assertEquals(
        new ModeledNode.Service(1, 125, 125),
        scenario(8, 0).with(Count.COST_SPREAD_PERCENT, 0).serviceOf(0));
  }

  @Test
  void theControllerAssumesACapacityThatCanDifferFromTheTruth() {
    assertEquals(8, scenario(8, 0).assumedCapacity());
    assertEquals(16, scenario(8, 0).with(Count.WORKERS, 2).assumedCapacity());
    assertEquals(12, scenario(8, 0).with(Count.CAPACITY_ESTIMATE_PERCENT, 150).assumedCapacity());
    // An estimate never falls below one request a tick.
    assertEquals(1, scenario(1, 0).with(Count.CAPACITY_ESTIMATE_PERCENT, 75).assumedCapacity());
  }

  @Test
  void geometryMustBeWhole() {
    assertThrows(IllegalArgumentException.class, () -> Topology.DEFAULT.withCells(0));
    assertThrows(IllegalArgumentException.class, () -> Topology.DEFAULT.withNodes(0));
    assertThrows(IllegalArgumentException.class, () -> Topology.DEFAULT.withSpares(-1));
    // A column that is too short, or a hill that leaves a mob nowhere to stand.
    assertThrows(IllegalArgumentException.class, () -> Topology.DEFAULT.withHeight(16));
    assertThrows(IllegalArgumentException.class, () -> Topology.DEFAULT.withRelief(32));

    Topology topology = Topology.DEFAULT;
    List<NodeId> fleet = IntStream.range(0, topology.nodeCount()).mapToObj(NodeId::of).toList();
    RegionMap regions = topology.layout().initial(fleet, topology.spares());
    // Four single-cell regions, the origin's held by the first node, and the last node spare.
    assertEquals(topology.regionCount(), regions.regions().size());
    assertEquals(fleet.subList(0, 4), regions.nodes());
    assertEquals(Regions.NONE, regions.regionOf(fleet.get(4)));
    assertEquals(List.of(fleet.get(0)), regions.holders(regions.regionAt(0, 0).id()));
    assertTrue(regions.regionAt(-129, 0) == null);
    // The world reaches one halo past the square, so outer areas hold real chunks.
    assertEquals(topology.layout().min() - 64, topology.min());
    assertEquals(topology.layout().size() + 128, topology.extent());
  }

  @Test
  void theWorldRefusesBlocksItDoesNotHold() {
    Topology topology = ONE_REGION.withMinY(-64);
    SimWorld world = new SimWorld(topology, 2, SeededRng.of(1L));

    assertEquals(-64, world.minY());
    assertThrows(
        IllegalArgumentException.class, () -> world.set(world.min() - 1, -60, 0, (byte) 0, 16));
    assertThrows(IllegalArgumentException.class, () -> world.set(0, 0, 0, (byte) 0, 16));
    assertThrows(IllegalArgumentException.class, () -> world.set(0, -65, 0, (byte) 0, 16));
    assertThrows(
        IllegalArgumentException.class,
        () -> world.chunkLoaded(world.minChunk() + world.chunksPerSide(), 0));
    assertThrows(IllegalArgumentException.class, () -> new SimWorld(topology, 0, SeededRng.of(1L)));
    // A derived type cannot be a block's own type.
    assertThrows(
        IllegalArgumentException.class, () -> world.set(0, -60, 0, (byte) PathType.WALKABLE, 16));
  }

  @Test
  void everySampledMobProfileIsCarriedOnTheWire() {
    // Profiles are drawn per run, so the encoder sees values that vary between
    // runs, not constants.
    Set<String> shapes = new LinkedHashSet<>();
    for (int seed = 0; seed < DRAWS; seed++) {
      SimWorld world = new SimWorld(Topology.DEFAULT, 3, SeededRng.of(seed).derive("terrain"));
      assertEquals(3, world.mobClassCount());
      for (short classId = 1; classId <= world.mobClassCount(); classId++) {
        shapes.add(world.mobClass(classId).shape().toString());
      }
    }
    assertTrue(shapes.size() > DRAWS, "mob profiles did not vary across seeds");
  }

  @Test
  void theSimulatorRefusesToBeMisused() {
    Simulation sim = new Simulation(1L, FaultPlan.reliable());
    sim.addNode(NodeId.of(0), ModeledNode.Service.MANUAL);

    assertThrows(
        IllegalArgumentException.class,
        () -> sim.addNode(NodeId.of(0), ModeledNode.Service.MANUAL));
    assertThrows(IllegalArgumentException.class, () -> new ModeledNode.Service(1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new ModeledNode.Service(1, 5, 4));
    assertThrows(IllegalArgumentException.class, () -> sim.scheduleAt(0, "past", () -> {}));
    assertThrows(IllegalArgumentException.class, () -> sim.schedule(0, "now", () -> {}));
    assertThrows(
        IllegalArgumentException.class, () -> sim.run(event -> {}, (tick, input) -> {}, 0));
    assertThrows(IllegalArgumentException.class, () -> sim.lateCompletions(-0.1));
    assertThrows(IllegalArgumentException.class, () -> sim.lateCompletions(1.5));
    assertThrows(IllegalArgumentException.class, () -> sim.lateCompletions(Double.NaN));
  }

  @Test
  void aDatagramTooLargeToSendIsRefusedAndAFragmentedOneIsLostMoreOften() {
    Simulation sim = new Simulation(1L, FaultPlan.reliable().withDropRate(0.2));
    NodeId node = NodeId.of(0);
    sim.addNode(node, ModeledNode.Service.MANUAL);
    ModeledNode.Host host = sim.transport().hostFor(node);

    host.send(Channel.REQUEST, new byte[Frames.MAX_DATAGRAM_BYTES + 1]);
    assertEquals(1, sim.metrics().total("transport.oversize"));

    int sends = 2000;
    for (int i = 0; i < sends; i++) {
      host.send(Channel.REQUEST, new byte[SimTransport.FRAGMENT_BYTES]);
    }
    long whole = sim.metrics().total("transport.dropped");
    for (int i = 0; i < sends; i++) {
      host.send(Channel.REQUEST, new byte[8 * SimTransport.FRAGMENT_BYTES]);
    }
    long fragmented = sim.metrics().total("transport.dropped") - whole;
    // One fragment is lost at the plan's 20%, and eight lose 1 - 0.8^8, about 83%.
    assertTrue(whole > sends / 10 && whole < sends * 3 / 10, "lost " + whole);
    assertTrue(fragmented > sends * 7 / 10, "lost " + fragmented);
  }

  @Test
  void aSectionTheWorldCallsUniformHoldsOneCell() {
    int uniform = 0;
    for (int seed = 0; seed < 20; seed++) {
      Topology topology = Topology.sample(SeededRng.of(seed).derive("fuzz"));
      SimWorld world = new SimWorld(topology, 1, SeededRng.of(seed).derive("terrain"));
      SeededRng rng = SeededRng.of(seed);
      int min = world.minChunk();
      int end = min + world.chunksPerSide();
      for (int chunkX = min; chunkX < end; chunkX++) {
        for (int chunkZ = min; chunkZ < end; chunkZ++) {
          world.loadChunk(chunkX, chunkZ, 0);
        }
      }
      // Changed blocks differ from the terrain around them, so a section holding one must not be
      // summarized.
      for (int i = 0; i < 20; i++) {
        world.set(
            world.min() + rng.nextInt(world.size()),
            world.minY() + rng.nextInt(world.height()),
            world.min() + rng.nextInt(world.size()),
            (byte) PathType.WATER,
            0);
      }

      int sections = world.height() / WorldSource.SECTION_HEIGHT;
      for (int chunkX = min; chunkX < end; chunkX++) {
        for (int chunkZ = min; chunkZ < end; chunkZ++) {
          for (int section = 0; section < sections; section++) {
            int cell = world.uniformCell(chunkX, chunkZ, section);
            if (cell == WorldSource.NOT_UNIFORM) {
              continue;
            }
            uniform++;
            int low = world.minY() + section * WorldSource.SECTION_HEIGHT;
            for (int x = chunkX * Chunks.SIZE; x < (chunkX + 1) * Chunks.SIZE; x++) {
              for (int z = chunkZ * Chunks.SIZE; z < (chunkZ + 1) * Chunks.SIZE; z++) {
                for (int y = low; y < low + WorldSource.SECTION_HEIGHT; y++) {
                  assertEquals(
                      cell, world.cellAt(x, y, z), "seed " + seed + " at " + x + "," + y + "," + z);
                }
              }
            }
          }
        }
      }
    }
    assertTrue(uniform > 0, "no section was ever summarized");
  }

  @Test
  void theTransportTakesOneListenerAndOnlyKnownNodes() {
    Simulation sim = new Simulation(1L, FaultPlan.reliable());
    sim.addNode(NodeId.of(0), ModeledNode.Service.MANUAL);
    Transport transport = sim.env().transport();

    assertThrows(IllegalStateException.class, () -> transport.connect(NodeId.of(0)));
    transport.listen(new NoopListener());
    assertThrows(IllegalStateException.class, () -> transport.listen(new NoopListener()));
    assertThrows(IllegalArgumentException.class, () -> transport.connect(NodeId.of(7)));
  }

  @Test
  void everyConnectAttemptEndsInExactlyOneCallback() {
    Simulation sim = new Simulation(1L, FaultPlan.reliable());
    NodeId id = NodeId.of(0);
    ModeledNode node = sim.addNode(id, ModeledNode.Service.MANUAL);
    Transport transport = sim.env().transport();
    CountingListener listener = new CountingListener(transport, sim.env().run());
    transport.listen(listener);

    // The node dies while the attempt is pending, so it fails.
    transport.connect(id);
    sim.transport().kill(id);
    sim.run(event -> {}, (tick, input) -> {}, 1);
    assertEquals(0, listener.connected);
    assertEquals(1, listener.disconnected);

    sim.transport().revive(id);
    transport.connect(id);
    sim.run(event -> {}, (tick, input) -> {}, 1);
    assertEquals(1, listener.connected);
    // The listener sent a `Join` on each connection and the node said hello.
    sim.run(event -> {}, (tick, input) -> {}, 1);
    assertEquals(0, sim.metrics().total("transport.refused"));
    assertTrue(listener.frames >= 1, "no hello");

    // A new attempt replaces the link, and the node forgets what belonged to the old one.
    node.receive(Channel.CONTROL, ByteBuffer.wrap(Frames.mobClass(profile((short) 3))));
    assertEquals(1, node.knownClassCount());
    transport.connect(id);
    sim.run(event -> {}, (tick, input) -> {}, 1);
    assertEquals(2, listener.connected);
    assertEquals(0, node.knownClassCount());
    assertEquals(0, sim.metrics().total("transport.refused"));
  }

  /** The controller hears the network and finished offload work only as a tick starts and ends. */
  @Test
  void theControllerHearsAnythingOnlyWhenTheServerHandsOver() {
    int busySteps = 300;
    Simulation sim = new Simulation(1L, FaultPlan.reliable());
    sim.serverBusySteps(busySteps);
    NodeId id = NodeId.of(0);
    sim.addNode(id, ModeledNode.Service.MANUAL);
    Transport transport = sim.env().transport();
    CountingListener counting = new CountingListener(transport, sim.env().run());
    List<Long> heardAt = new ArrayList<>();
    transport.listen(
        new Transport.Listener() {
          @Override
          public void onFrame(NodeId node, Channel channel, ByteBuffer frame, long arrived) {
            heardAt.add(sim.now());
            counting.onFrame(node, channel, frame, arrived);
          }

          @Override
          public void onConnected(NodeId node) {
            heardAt.add(sim.now());
            counting.onConnected(node);
          }

          @Override
          public void onDisconnected(NodeId node) {
            heardAt.add(sim.now());
            counting.onDisconnected(node);
          }
        });
    transport.connect(id);
    sim.run(
        event -> {
          if (event instanceof WorldEvent.TickBegan) {
            sim.env()
                .offload()
                .submit(
                    "probe",
                    Offload.Lane.WORLD,
                    () -> 0,
                    done -> heardAt.add(sim.now()),
                    () -> heardAt.add(sim.now()));
          }
        },
        (tick, input) -> {},
        4);

    assertEquals(1, counting.connected);
    assertTrue(counting.frames >= 1, "no hello");
    assertTrue(heardAt.size() > 4, heardAt.toString());
    for (long step : heardAt) {
      long within = step % Simulation.STEPS_PER_TICK;
      assertTrue(within == 0 || within == busySteps, "heard at step " + within + " of its tick");
    }
  }

  /** Work that sent hands its completion over a hand-over late; other work is not held back. */
  @Test
  void aWorkerThatSentHandsItsCompletionOverAHandOverLate() {
    int busySteps = 300;
    Simulation sim = new Simulation(1L, FaultPlan.reliable());
    sim.serverBusySteps(busySteps);
    sim.lateCompletions(1);
    NodeId id = NodeId.of(0);
    sim.addNode(id, ModeledNode.Service.MANUAL);
    Transport transport = sim.env().transport();
    transport.listen(new NoopListener());
    Map<String, Long> doneAt = new LinkedHashMap<>();
    sim.run(
        event -> {
          if (event instanceof WorldEvent.TickBegan began && began.tick() == 0) {
            for (String name : List.of("quiet", "sends")) {
              sim.env()
                  .offload()
                  .submit(
                      name,
                      Offload.Lane.REQUESTS,
                      () -> {
                        if (name.equals("sends")) {
                          transport.send(
                              id, Channel.REQUEST, ByteBuffer.wrap(Frames.probe(RUN, 1)));
                        }
                        return 0;
                      },
                      done -> doneAt.put(name, sim.now()),
                      () -> {});
            }
          }
        },
        (tick, input) -> {},
        3);

    // Both finish within the first tick's work: one is heard as it ends, the other as the next
    // starts.
    assertEquals(
        Map.of("quiet", (long) busySteps, "sends", (long) Simulation.STEPS_PER_TICK), doneAt);
    assertEquals(1, sim.metrics().total("offload.late"));
  }

  /** A trace must be reported once, keep its stages in order, and match what its mob was told. */
  @Test
  void aTraceThatDisagreesWithTheRunIsReported() {
    Simulation sim = new Simulation(1L, FaultPlan.reliable());
    RecordingOutput out = recorder(sim, new SimWorld(Topology.DEFAULT, 1, SeededRng.of(1)));
    // Outcomes are told at the tick the simulation stands at, before it runs.
    long tick = sim.env().clock().tick();
    WorldEvent.PathRequested asked = request(1, 0, FAR);
    out.issued(asked);
    out.pathComputed(answer(asked));
    out.issued(request(2, 0, FAR));
    out.pathDropped(2, DropReason.TIMEOUT);
    RequestTrace.Answer heard = new RequestTrace.Answer(400, 10, 20, 1);
    RequestTrace answered = trace(1, 1, tick, 200, 300, heard, 500, RequestTrace.ANSWERED);
    RequestTrace timedOut = trace(2, 2, tick, 200, 300, null, 500, "TIMEOUT");
    assertEquals(List.of(), traced(answered, timedOut).check(out, true, 0));
    // One traced at a lower rate need not cover every answer, nor one a restart lost.
    assertEquals(List.of(), traced(timedOut).check(out, false, 0));
    assertEquals(List.of(), traced(timedOut).check(out, true, 1));

    assertFlags(traced(timedOut).check(out, true, 0), "answered requests were traced");
    assertFlags(traced(answered, timedOut).check(out, true, 1), "answered requests were traced");
    assertFlags(traced(answered, answered).check(out, false, 0), "was traced twice");
    assertFlags(
        traced(trace(1, 1, tick, 300, 200, heard, 500, RequestTrace.ANSWERED)).check(out, false, 0),
        "has stages out of order");
    assertFlags(
        traced(trace(1, 1, tick, RequestTrace.NEVER, 300, heard, 500, RequestTrace.ANSWERED))
            .check(out, false, 0),
        "was sent without being encoded");
    assertFlags(
        traced(trace(1, 1, tick, 200, 300, new RequestTrace.Answer(600, 10, 20, 1), 500, "x"))
            .check(out, false, 0),
        "has an answer that arrived outside its send and end");
    assertFlags(
        traced(trace(1, 1, tick, 200, 300, null, 500, RequestTrace.ANSWERED)).check(out, false, 0),
        "ended ANSWERED with answer null");
    assertFlags(
        traced(trace(2, 2, tick, 200, 300, heard, 500, "TIMEOUT")).check(out, false, 0),
        "ended TIMEOUT with answer");
    assertFlags(
        traced(trace(2, 2, tick, 200, 300, null, 500, "SHED")).check(out, false, 0),
        "was not told then");
    assertFlags(
        traced(trace(2, 2, tick + 1, 200, 300, null, 500, "TIMEOUT")).check(out, false, 0),
        "was not told then");
  }

  @Test
  void overlappingFaultsOfOneKindEndOnlyWhenEveryIntervalHas() {
    Simulation sim = new Simulation(1L, FaultPlan.reliable());
    NodeId id = NodeId.of(0);
    sim.addNode(id, ModeledNode.Service.MANUAL);
    SimTransport transport = sim.transport();

    transport.kill(id);
    transport.kill(id);
    transport.revive(id);
    assertFalse(transport.alive(id), "the first recovery ended both deaths");
    transport.revive(id);
    assertTrue(transport.alive(id));
    // A recovery with no death is nothing to end.
    transport.revive(id);
    transport.kill(id);
    assertFalse(transport.alive(id));
  }

  @Test
  void aPausedNodeHandlesWhatReachedItOnlyOnceItResumes() {
    Simulation sim = new Simulation(1L, FaultPlan.reliable());
    NodeId id = NodeId.of(0);
    ModeledNode node = sim.addNode(id, new ModeledNode.Service(1, 1, 1));
    Transport transport = sim.env().transport();
    CountingListener listener = new CountingListener(transport, sim.env().run());
    transport.listen(listener);
    transport.connect(id);
    sim.run(event -> {}, (tick, input) -> {}, 1);
    transport.send(id, Channel.CONTROL, ByteBuffer.wrap(Frames.mobClass(profile((short) 3))));
    sim.run(event -> {}, (tick, input) -> {}, 1);

    sim.transport().pause(id, true);
    transport.send(id, Channel.REQUEST, requests(sim.env().run(), walk(5)));
    sim.run(event -> {}, (tick, input) -> {}, 30);
    assertEquals(0, node.servedRequests());
    int heard = listener.frames;

    sim.transport().pause(id, false);
    sim.run(event -> {}, (tick, input) -> {}, 1);
    assertEquals(1, node.servedRequests());
    assertTrue(listener.frames > heard, "the answer never came");
  }

  /** A chunk frame of {@code columns} one-run air columns at {@code floor}. */
  private static byte[] chunkWithColumns(long sequence, int columns, int floor) {
    FlatBufferBuilder builder = new FlatBufferBuilder(1024);
    int[] counts = new int[columns];
    Arrays.fill(counts, 1);
    int countsVector = ChunkSnapshot.createRunCountsVector(builder, counts);
    ChunkSnapshot.startRunsVector(builder, columns);
    for (int i = 0; i < columns; i++) {
      CellRun.createCellRun(builder, Topology.DEFAULT.height(), PathType.OPEN, floor);
    }
    int runs = builder.endVector();
    ChunkSnapshot.startChunkSnapshot(builder);
    ChunkSnapshot.addSequence(builder, sequence);
    ChunkSnapshot.addChunkXz(builder, Vec2i.createVec2i(builder, 0, 0));
    ChunkSnapshot.addRunCounts(builder, countsVector);
    ChunkSnapshot.addRuns(builder, runs);
    int snapshot = ChunkSnapshot.endChunkSnapshot(builder);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.ChunkSnapshot, snapshot));
    return builder.sizedByteArray();
  }

  private static WorldEvent.PathRequested request(int mob, long tick, Pos target) {
    return new WorldEvent.PathRequested(
        tick, mob, (short) 1, ORIGIN, target, new WorldEvent.Search(0, 16f, 256, 3), true);
  }

  private static WorldOutput.PathResult answer(WorldEvent.PathRequested request) {
    return new WorldOutput.PathResult(
        request.mobId(),
        request.target(),
        true,
        List.of(new WorldOutput.Node(request.target(), (byte) PathType.WALKABLE)),
        1.0f,
        request.tick());
  }

  /** A delta with one cell of {@code floor}, which the encoder refuses to build past the limit. */
  private static byte[] deltaWithFloor(long sequence, int floor) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    WorldDelta.startCellsVector(builder, 1);
    CellDelta.createCellDelta(builder, (short) 1, (short) 40, (short) 1, PathType.BLOCKED, floor);
    int cells = builder.endVector();
    int delta = WorldDelta.createWorldDelta(builder, sequence, cells);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.WorldDelta, delta));
    return builder.sizedByteArray();
  }

  private static MobClass profile(short classId) {
    return new MobClass(
        classId,
        new MobClass.Shape(0.6f, 1.8f, 0.6f),
        new MobClass.Abilities(true, false, false, false),
        new float[PathTypes.count()]);
  }

  /** A node joined to a link of {@link #RUN}. */
  private static ModeledNode linked(Simulation sim) {
    return linked(sim, NodeId.of(0));
  }

  private static ModeledNode linked(Simulation sim, NodeId id) {
    return linked(sim, id, RUN);
  }

  private static ModeledNode linked(Simulation sim, NodeId id, long run) {
    ModeledNode node = sim.addNode(id, ModeledNode.Service.MANUAL);
    node.connect(Channel.WORLD, ByteBuffer.wrap(Frames.joinWorld(run, 1)));
    node.connect(Channel.CONTROL, ByteBuffer.wrap(Frames.joinControl(run, 1)));
    return node;
  }

  /** A recorder for a run of the default topology over {@code world}. */
  private static RecordingOutput recorder(SimWorld world) {
    return recorder(new Simulation(1L, FaultPlan.reliable()), world);
  }

  private static RecordingOutput recorder(Simulation sim, SimWorld world) {
    Topology topology = Topology.DEFAULT;
    List<NodeId> fleet = IntStream.range(0, topology.nodeCount()).mapToObj(NodeId::of).toList();
    RegionMap regions = topology.layout().initial(fleet, topology.spares());
    return new RecordingOutput(sim, world, () -> regions);
  }

  /** A trace of {@code mob}'s request asked at 100 that ended at {@code tick}. */
  private static RequestTrace trace(
      long requestId,
      int mob,
      long tick,
      long encoding,
      long sent,
      RequestTrace.Answer answer,
      long ended,
      String outcome) {
    return new RequestTrace(
        RUN,
        requestId,
        mob,
        (short) 1,
        NodeId.of(0),
        1,
        true,
        tick,
        tick,
        100,
        encoding,
        sent,
        List.of(),
        answer,
        ended,
        outcome);
  }

  private static SimTracing traced(RequestTrace... traces) {
    SimTracing tracing = new SimTracing();
    for (RequestTrace trace : traces) {
      tracing.finished(trace);
    }
    return tracing;
  }

  /** {@code found} holds a violation naming {@code phrase}. */
  private static void assertFlags(List<String> found, String phrase) {
    assertTrue(found.stream().anyMatch(violation -> violation.contains(phrase)), found.toString());
  }

  private static ByteBuffer requests(Frames.Request... batch) {
    return requests(RUN, batch);
  }

  private static ByteBuffer requests(long run, Frames.Request... batch) {
    return ByteBuffer.wrap(Frames.requests(run, 1, List.of(batch), 0, 0));
  }

  /** A request of class 3 from {@link #ORIGIN} to {@link #FAR}. */
  private static Frames.Request walk(long requestId) {
    return new Frames.Request(
        requestId, (short) 3, ORIGIN, FAR, new WorldEvent.Search(0, 16f, 256, 3));
  }

  /** Frames the node has sent on the request channel: answers and refusals. */
  private static long requestFramesSent(Simulation sim) {
    return sim.metrics()
        .counter("transport.sent", "node", NodeId.of(0).toString(), "channel", "REQUEST");
  }

  private static Scenario scenario(int capacity, int slowNodes) {
    return Scenario.DEFAULT
        .with(Count.WORKERS, 1)
        .with(Count.WORKER_CAPACITY, capacity)
        .with(Count.SLOW_NODES, slowNodes);
  }

  private static int firstIndex(List<WorldEvent> events, Class<? extends WorldEvent> kind) {
    for (int i = 0; i < events.size(); i++) {
      if (kind.isInstance(events.get(i))) {
        return i;
      }
    }
    return -1;
  }

  /** Lowest and highest value a sampler produced, so a range can be checked in one place. */
  private static final class Span {

    /**
     * Fraction of the range at each end that may be missed, so the check does not depend on luck.
     */
    private static final double EDGE_MARGIN = 0.1;

    private double low = Double.POSITIVE_INFINITY;
    private double high = Double.NEGATIVE_INFINITY;

    void see(double value) {
      low = Math.min(low, value);
      high = Math.max(high, value);
    }

    /** Asserts that the draws reached both ends of {@code [low, high]}, within a margin. */
    void assertCovers(Object label, double low, double high) {
      double margin = (high - low) * EDGE_MARGIN;
      assertTrue(
          this.low <= low + margin, label + ": lowest draw was " + this.low + ", floor is " + low);
      assertTrue(
          this.high >= high - margin,
          label + ": highest draw was " + this.high + ", ceiling is " + high);
    }
  }

  /** Counts callbacks, and opens each link with its `Join`s as the controller does. */
  private static final class CountingListener implements Transport.Listener {

    private final Transport transport;
    private final long run;
    private int frames;
    private int connected;
    private int disconnected;

    CountingListener(Transport transport, long run) {
      this.transport = transport;
      this.run = run;
    }

    @Override
    public void onFrame(NodeId node, Channel channel, ByteBuffer frame, long arrived) {
      frames++;
    }

    @Override
    public void onConnected(NodeId node) {
      connected++;
      transport.send(node, Channel.WORLD, ByteBuffer.wrap(Frames.joinWorld(run, connected)));
      transport.send(node, Channel.CONTROL, ByteBuffer.wrap(Frames.joinControl(run, connected)));
    }

    @Override
    public void onDisconnected(NodeId node) {
      disconnected++;
    }
  }

  private static final class NoopListener implements Transport.Listener {

    @Override
    public void onFrame(NodeId node, Channel channel, ByteBuffer frame, long arrived) {}

    @Override
    public void onConnected(NodeId node) {}

    @Override
    public void onDisconnected(NodeId node) {}
  }
}
