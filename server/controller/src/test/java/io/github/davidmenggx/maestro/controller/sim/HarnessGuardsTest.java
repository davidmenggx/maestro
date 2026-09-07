package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The harness has to be trustworthy before the runs it produces are. These cover the machinery the
 * assertions themselves lean on, the samplers that decide what a sweep explores, and the guards
 * that turn a harness misuse into a message.
 */
class HarnessGuardsTest {

  private static final int DRAWS = 300;

  @Test
  void sampledScenariosSpanTheirRangeAndStayValid() {
    Span capacity = new Span();
    Span slowNodes = new Span();
    Span mobs = new Span();
    Span classes = new Span();
    Span blockChanges = new Span();
    Span players = new Span();
    Span requests = new Span();
    Span cancels = new Span();
    Span mobChurn = new Span();
    Span chunkChurn = new Span();
    Span playerChurn = new Span();
    Span stray = new Span();

    for (int seed = 0; seed < DRAWS; seed++) {
      Scenario scenario = Scenario.sample(SeededRng.of(seed).derive("fuzz"));
      capacity.see(scenario.nodeCapacity());
      slowNodes.see(scenario.slowNodes());
      mobs.see(scenario.mobCount());
      classes.see(scenario.mobClassCount());
      blockChanges.see(scenario.maxBlockChangesPerTick());
      players.see(scenario.playerCount());
      requests.see(scenario.requestChance());
      cancels.see(scenario.cancelChance());
      mobChurn.see(scenario.mobChurnChance());
      chunkChurn.see(scenario.chunkChurnChance());
      playerChurn.see(scenario.playerChurnChance());
      stray.see(scenario.strayRequestChance());
    }

    // A sample stuck at one setting would not fuzz anything.
    capacity.assertCovers(1, Scenario.MAX_NODE_CAPACITY);
    slowNodes.assertCovers(0, Scenario.MAX_SLOW_NODES);
    mobs.assertCovers(0, Scenario.MAX_MOB_COUNT);
    classes.assertCovers(1, Scenario.MAX_MOB_CLASSES);
    blockChanges.assertCovers(0, Scenario.MAX_BLOCK_CHANGES_PER_TICK);
    players.assertCovers(0, Scenario.MAX_PLAYERS);
    requests.assertCovers(0, Scenario.MAX_REQUEST_CHANCE);
    cancels.assertCovers(0, Scenario.MAX_CANCEL_CHANCE);
    mobChurn.assertCovers(0, Scenario.MAX_MOB_CHURN_CHANCE);
    chunkChurn.assertCovers(0, Scenario.MAX_CHUNK_CHURN_CHANCE);
    playerChurn.assertCovers(0, Scenario.MAX_PLAYER_CHURN_CHANCE);
    stray.assertCovers(0, Scenario.MAX_STRAY_REQUEST_CHANCE);
  }

  @Test
  void sampledTopologiesSpanTheirRangeAndStayChunkAligned() {
    Set<Integer> regionSizes = new LinkedHashSet<>();
    Set<Integer> gridWidths = new LinkedHashSet<>();
    Set<Integer> floors = new LinkedHashSet<>();
    Span height = new Span();
    Span relief = new Span();

    for (int seed = 0; seed < DRAWS; seed++) {
      Topology topology = Topology.sample(SeededRng.of(seed).derive("fuzz"));
      regionSizes.add(topology.regionSize());
      gridWidths.add(topology.regionsPerSide());
      floors.add(topology.minY());
      height.see(topology.height());
      relief.see(topology.relief());
      assertEquals(0, topology.extent() % Topology.CHUNK_SIZE, topology.toString());
      assertEquals(topology.regionsPerSide() * topology.regionsPerSide(), topology.nodeCount());
      assertTrue(topology.nodeCount() <= Topology.maxNodeCount(), topology.toString());
    }

    assertEquals(Set.copyOf(Topology.regionSizes()), regionSizes);
    assertEquals(Set.copyOf(Topology.floors()), floors);
    assertEquals(widths(Topology.MAX_REGIONS_PER_SIDE), gridWidths);
    height.assertCovers(Topology.MIN_HEIGHT, Topology.MAX_HEIGHT);
    relief.assertCovers(0, Topology.MAX_RELIEF);
  }

  @Test
  void sampledFleetFaultsCoverEveryKindAndStayInsideTheActiveWindow() {
    long active = 100;
    Set<String> kinds = new LinkedHashSet<>();
    Span faultCount = new Span();
    Span minLatency = new Span();
    Span maxLatency = new Span();
    boolean sawPermanentLoss = false;

    for (int seed = 0; seed < DRAWS; seed++) {
      FaultPlan plan = FaultPlan.sample(SeededRng.of(seed).derive("fuzz"), 4, active);
      faultCount.see(plan.faults().size());
      minLatency.see(plan.minLatencySteps());
      maxLatency.see(plan.maxLatencySteps());
      assertTrue(plan.maxLatencySteps() >= plan.minLatencySteps(), plan.toString());

      int died = 0;
      int recovered = 0;
      for (FaultPlan.Fault fault : plan.faults()) {
        kinds.add(fault.getClass().getSimpleName());
        assertTrue(fault.tick() >= 1 && fault.tick() < active, "fault at tick " + fault.tick());
        died += fault instanceof FaultPlan.NodeDied ? 1 : 0;
        recovered += fault instanceof FaultPlan.NodeRecovered ? 1 : 0;
      }
      sawPermanentLoss |= died > recovered;
    }

    // Process death, a silent partition and a link reset are three different
    // recoveries, so the sweep has to reach all of them.
    assertEquals(Set.of("NodeDied", "NodeRecovered", "Partitioned", "LinkReset"), kinds);
    // Faults have to be able to pile up on one node, or a second failure during a
    // recovery is a case the sweep can never reach. A paired fault contributes two
    // entries, so the count only has to clear the number of faults drawn.
    faultCount.assertCovers(0, FaultPlan.MAX_FAULTS);
    minLatency.assertCovers(1, FaultPlan.MAX_MIN_LATENCY_STEPS);
    maxLatency.assertCovers(
        1, FaultPlan.MAX_MIN_LATENCY_STEPS + FaultPlan.MAX_LATENCY_SPAN_STEPS - 1);
    assertTrue(sawPermanentLoss, "no node was ever lost for the rest of the run");
    assertEquals(List.of(), FaultPlan.sample(SeededRng.of(1).derive("fuzz"), 0, active).faults());
  }

  /**
   * Some harness constants are only correct relative to another one. Left to drift apart they fail
   * silently: the sweep still passes, having quietly stopped reaching the case it was sized for.
   */
  @Test
  void theHarnessConstantsAgreeWithWhatTheyAreSizedAgainst() {
    // Worst-case latency a draw can reach, kept well inside a tick. Any wider and a
    // response is late whatever the controller does, so the latency invariant would
    // be measuring the sampler instead of the code under test.
    assertTrue(
        FaultPlan.MAX_MIN_LATENCY_STEPS + FaultPlan.MAX_LATENCY_SPAN_STEPS
            <= Simulation.STEPS_PER_TICK / 2,
        "sampled latency can reach half a tick");

    // A fault starts inside the active window and, unless the node is lost for good,
    // ends there too. The shortest run has to hold both.
    assertTrue(
        SimRun.MIN_FUZZED_TICKS - SimRun.DRAIN_TICKS > FaultPlan.MAX_FAULT_SPAN_TICKS,
        "the shortest fuzzed run has no room for a fault");
    assertTrue(SimRun.MAX_FUZZED_TICKS >= SimRun.MIN_FUZZED_TICKS, "run length range is inverted");

    // Relief is capped against the shortest column, so no draw can fail the check
    // the constructor makes against its own height.
    assertTrue(Topology.MAX_HEIGHT >= Topology.MIN_HEIGHT, "height range is inverted");
    assertTrue(
        Topology.MAX_RELIEF <= Topology.MIN_HEIGHT / 4, "a sampled hill can outgrow its column");

    // Comparison nodes are built outside the fleet at `FRESH_NODE + region`, so the
    // block they sit in has to start above every id a sampled grid hands out.
    assertTrue(
        Invariants.FRESH_NODE >= Topology.maxNodeCount(),
        "a comparison node can collide with a real one");
  }

  @Test
  void aNodeToldToResumeFromAStreamItLostSaysSo() {
    Simulation sim = new Simulation(5L, FaultPlan.reliable());
    ModeledNode node = sim.addNode(NodeId.of(0), 4);
    RegionMap regions = new RegionMap(16, 1, List.of(NodeId.of(0)));

    // The node holds nothing, so a resume point is a claim it cannot honor.
    node.receive(Channel.WORLD, ByteBuffer.wrap(ProbeController.encodeAssignment(regions, 0, 7)));

    assertEquals(1, node.violations().size(), node.violations().toString());
    assertTrue(node.violations().get(0).contains("resume from 7"), node.violations().toString());
  }

  @Test
  void thePercentileTheSloLeansOnIsRight() {
    SimMetrics metrics = new SimMetrics();
    for (int i = 1; i <= 100; i++) {
      metrics.record("latency", i);
    }

    assertEquals(50.0, metrics.percentile("latency", 0.5));
    assertEquals(99.0, metrics.percentile("latency", 0.99));
    assertEquals(100.0, metrics.percentile("latency", 1.0));
    assertEquals(1.0, metrics.percentile("latency", 0.0));
    assertTrue(Double.isNaN(metrics.percentile("nothing", 0.99)));
  }

  @Test
  void metricsKeepTheirLabelsApart() {
    SimMetrics metrics = new SimMetrics();
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
  }

  @Test
  void aFaultPlanRejectsImpossibleSettings() {
    assertThrows(IllegalArgumentException.class, () -> FaultPlan.reliable().withLoss(1.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> FaultPlan.reliable().withLoss(-0.1, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(0, 5, 0.0, 0.0, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(9, 5, 0.0, 0.0, List.of()));
  }

  @Test
  void aScenarioRejectsImpossibleSettings() {
    assertThrows(IllegalArgumentException.class, () -> scenario(0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> Scenario.DEFAULT.toBuilder().mobCount(-1).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> Scenario.DEFAULT.toBuilder().requestChance(1.5).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> Scenario.DEFAULT.toBuilder().chunkChurnChance(-0.1).build());
  }

  @Test
  void aSlowNodeIsTheOnlyOneServedAtAReducedRate() {
    assertEquals(8, scenario(8, 0).capacityOf(0));
    assertEquals(2, scenario(8, 1).capacityOf(0));
    assertEquals(8, scenario(8, 1).capacityOf(1));
    // A capacity that would round to zero still serves one request a tick.
    assertEquals(1, scenario(2, 1).capacityOf(0));
  }

  @Test
  void geometryMustBeChunkAligned() {
    assertThrows(IllegalArgumentException.class, () -> new RegionMap(20, 1, List.of(NodeId.of(0))));
    assertThrows(IllegalArgumentException.class, () -> new RegionMap(16, 2, List.of(NodeId.of(0))));
    assertThrows(IllegalArgumentException.class, () -> new Topology(20, 1, 0, 64, 2));
    assertThrows(IllegalArgumentException.class, () -> new Topology(16, 0, 0, 64, 2));
    // A column too short, or a hill that would leave a mob nowhere to stand.
    assertThrows(IllegalArgumentException.class, () -> new Topology(16, 1, 0, 16, 2));
    assertThrows(IllegalArgumentException.class, () -> new Topology(16, 1, 0, 64, 32));

    RegionMap regions = new RegionMap(16, 2, nodes(4));
    assertEquals(32, regions.extent());
    assertThrows(IllegalArgumentException.class, () -> regions.minX(4));
    assertThrows(IllegalArgumentException.class, () -> regions.owner(-1));
  }

  @Test
  void theWorldRefusesBlocksItDoesNotHold() {
    Topology topology = new Topology(16, 1, -64, 64, 2);
    SimWorld world = new SimWorld(topology, 2, SeededRng.of(1L));

    assertEquals(-64, world.minY());
    assertThrows(IllegalArgumentException.class, () -> world.set(-1, -60, 0, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> world.set(0, 0, 0, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> world.set(0, -65, 0, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> world.chunkLoaded(9, 9));
    assertThrows(IllegalArgumentException.class, () -> new SimWorld(topology, 0, SeededRng.of(1L)));
    // Derived types cannot be a block's own type.
    assertThrows(IllegalArgumentException.class, () -> world.set(0, -60, 0, (byte) 2));
  }

  @Test
  void everySampledMobProfileIsCarriedOnTheWire() {
    // Profiles are drawn per run, so the encoder meets values that differ between
    // runs rather than one pair of constants.
    Set<String> shapes = new LinkedHashSet<>();
    for (int seed = 0; seed < DRAWS; seed++) {
      SimWorld world = new SimWorld(Topology.DEFAULT, 3, SeededRng.of(seed).derive("terrain"));
      assertEquals(3, world.mobClasses().size());
      world.mobClasses().forEach(profile -> shapes.add(profile.width() + "x" + profile.height()));
    }
    assertTrue(shapes.size() > DRAWS, "mob profiles did not vary across seeds");
  }

  @Test
  void theSimulatorRefusesToBeMisused() {
    Simulation sim = new Simulation(1L, FaultPlan.reliable());
    sim.addNode(NodeId.of(0), 1);

    assertThrows(IllegalArgumentException.class, () -> sim.addNode(NodeId.of(0), 1));
    assertThrows(IllegalArgumentException.class, () -> sim.addNode(NodeId.of(1), 0));
    assertThrows(IllegalArgumentException.class, () -> sim.scheduleAt(0, "past", () -> {}));
    assertThrows(IllegalArgumentException.class, () -> sim.schedule(0, "now", () -> {}));
    assertThrows(
        IllegalArgumentException.class, () -> sim.run(event -> {}, (tick, input) -> {}, 0));
  }

  @Test
  void theTransportTakesOneListenerAndOnlyKnownNodes() {
    Simulation sim = new Simulation(1L, FaultPlan.reliable());
    sim.addNode(NodeId.of(0), 1);
    SimTransport transport = (SimTransport) sim.env().transport();

    assertThrows(IllegalStateException.class, () -> transport.connect(NodeId.of(0), "sim://0"));
    transport.listen(new NoopListener());
    assertThrows(IllegalStateException.class, () -> transport.listen(new NoopListener()));
    assertThrows(IllegalArgumentException.class, () -> transport.connect(NodeId.of(7), "sim://7"));
  }

  private static Scenario scenario(int capacity, int slowNodes) {
    return Scenario.DEFAULT.toBuilder().nodeCapacity(capacity).slowNodes(slowNodes).build();
  }

  private static List<NodeId> nodes(int count) {
    return IntStream.range(0, count).mapToObj(NodeId::of).toList();
  }

  private static Set<Integer> widths(int max) {
    return IntStream.rangeClosed(1, max).boxed().collect(Collectors.toSet());
  }

  /** Lowest and highest value a sampler produced, so a range can be asserted in one place. */
  private static final class Span {

    /** Share of a range each end may be missed by, so the check is not a test of luck. */
    private static final double EDGE_MARGIN = 0.1;

    private double low = Double.POSITIVE_INFINITY;
    private double high = Double.NEGATIVE_INFINITY;

    void see(double value) {
      low = Math.min(low, value);
      high = Math.max(high, value);
    }

    /** Asserts the draws reached both ends of {@code [low, high]}, give or take a margin. */
    void assertCovers(double low, double high) {
      double margin = (high - low) * EDGE_MARGIN;
      assertTrue(this.low <= low + margin, "lowest draw was " + this.low + ", floor is " + low);
      assertTrue(
          this.high >= high - margin, "highest draw was " + this.high + ", ceiling is " + high);
    }
  }

  private static final class NoopListener
      implements io.github.davidmenggx.maestro.controller.env.Transport.Listener {

    @Override
    public void onFrame(NodeId node, Channel channel, ByteBuffer frame) {}

    @Override
    public void onConnected(NodeId node) {}

    @Override
    public void onDisconnected(NodeId node) {}
  }
}
