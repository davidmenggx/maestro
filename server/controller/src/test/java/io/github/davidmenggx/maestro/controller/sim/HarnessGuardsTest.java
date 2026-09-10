package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Chance;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import maestro.fbs.PathType;
import org.junit.jupiter.api.Test;

/**
 * The harness has to be trustworthy before the runs it produces are. These cover the machinery the
 * assertions themselves lean on, the samplers that decide what a sweep explores, and the guards
 * that turn a harness misuse into a message.
 */
class HarnessGuardsTest {

  private static final int DRAWS = 300;

  private static final RegionMap ONE_REGION =
      new RegionMap(Topology.DEFAULT.withRegionsPerSide(1), List.of(NodeId.of(0)));

  @Test
  void sampledScenariosSpanTheirRangeAndStayValid() {
    Map<Count, Span> counts = new EnumMap<>(Count.class);
    Map<Chance, Span> chances = new EnumMap<>(Chance.class);

    for (int seed = 0; seed < DRAWS; seed++) {
      Scenario scenario = Scenario.sample(SeededRng.of(seed).derive("fuzz"));
      for (Count setting : Count.values()) {
        counts.computeIfAbsent(setting, unseen -> new Span()).see(scenario.counts().get(setting));
      }
      for (Chance setting : Chance.values()) {
        chances.computeIfAbsent(setting, unseen -> new Span()).see(scenario.chances().get(setting));
      }
    }

    // A sample stuck at one setting would not fuzz anything.
    for (Count setting : Count.values()) {
      counts.get(setting).assertCovers(setting, setting.floor(), setting.ceiling());
    }
    for (Chance setting : Chance.values()) {
      chances.get(setting).assertCovers(setting, 0, setting.ceiling());
    }
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
      assertEquals(0, topology.extent() % Chunks.SIZE, topology.toString());
      assertTrue(topology.nodeCount() <= Topology.maxNodeCount(), topology.toString());
    }

    assertEquals(Set.copyOf(Topology.regionSizes()), regionSizes);
    assertEquals(Set.copyOf(Topology.floors()), floors);
    assertEquals(
        IntStream.rangeClosed(1, Topology.MAX_REGIONS_PER_SIDE).boxed().collect(Collectors.toSet()),
        gridWidths);
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
    faultCount.assertCovers("fault count", 0, FaultPlan.MAX_FAULTS);
    minLatency.assertCovers("min latency", 1, FaultPlan.MAX_MIN_LATENCY_STEPS);
    maxLatency.assertCovers(
        "max latency", 1, FaultPlan.MAX_MIN_LATENCY_STEPS + FaultPlan.MAX_LATENCY_SPAN_STEPS - 1);
    assertTrue(sawPermanentLoss, "no node was ever lost for the rest of the run");
    assertEquals(List.of(), FaultPlan.sample(SeededRng.of(1).derive("fuzz"), 0, active).faults());
  }

  @Test
  void aNodeToldToResumeFromAStreamItLostSaysSo() {
    Simulation sim = new Simulation(5L, FaultPlan.reliable());
    ModeledNode node = sim.addNode(NodeId.of(0), 4);

    // The node holds nothing, so a resume point is a claim it cannot honor.
    node.receive(Channel.WORLD, ByteBuffer.wrap(Frames.assignment(ONE_REGION, 0, 7)));

    assertEquals(1, node.violations().size(), node.violations().toString());
    assertTrue(node.violations().get(0).contains("resume from 7"), node.violations().toString());
  }

  @Test
  void aGapInTheWorldStreamIsReported() {
    Simulation sim = new Simulation(3L, FaultPlan.reliable());
    ModeledNode node = sim.addNode(NodeId.of(0), 4);
    SimWorld world = new SimWorld(ONE_REGION.topology(), 2, sim.rng().derive("terrain"));
    world.loadChunk(0, 0);

    node.receive(Channel.WORLD, ByteBuffer.wrap(Frames.assignment(ONE_REGION, 0, 0)));
    node.receive(Channel.WORLD, ByteBuffer.wrap(Frames.snapshot(world, ONE_REGION, 0, 1)));
    assertEquals(List.of(), node.violations());
    assertEquals(1, node.lastSequence());

    List<WorldEvent.BlockChanged> cells =
        List.of(new WorldEvent.BlockChanged(0, new Pos(1, 40, 1), (byte) PathType.WATER));
    node.receive(Channel.WORLD, ByteBuffer.wrap(Frames.delta(2, cells)));
    assertEquals(List.of(), node.violations());

    node.receive(Channel.WORLD, ByteBuffer.wrap(Frames.delta(4, cells)));
    assertFalse(node.violations().isEmpty(), "a skipped sequence went unreported");
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

    metrics.count("frames", 2, "node", "node-0");
    metrics.count("frames", 5, "node", "node-1");
    metrics.count("frames-held", 9, "node", "node-0");
    metrics.gauge("depth", 3, "node", "node-0");

    assertEquals(2, metrics.counter("frames", "node", "node-0"));
    assertEquals(5, metrics.counter("frames", "node", "node-1"));
    assertEquals(0, metrics.counter("frames"));
    assertEquals(3, metrics.gauge("depth", "node", "node-0"));
    assertEquals(0, metrics.gauge("depth", "node", "node-1"));
    // A total covers every label of one counter and stops at the name boundary.
    assertEquals(7, metrics.total("frames"));
    assertEquals(9, metrics.total("frames-held"));
    assertThrows(IllegalArgumentException.class, () -> metrics.count("frames", 1, "unpaired"));
  }

  @Test
  void aFaultPlanRejectsImpossibleSettings() {
    assertThrows(IllegalArgumentException.class, () -> FaultPlan.reliable().withDropRate(1.0));
    assertThrows(
        IllegalArgumentException.class, () -> FaultPlan.reliable().withDuplicateRate(-0.1));
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(0, 5, 0.0, 0.0, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(9, 5, 0.0, 0.0, List.of()));
  }

  @Test
  void aScenarioRejectsImpossibleSettings() {
    assertThrows(IllegalArgumentException.class, () -> Scenario.DEFAULT.with(Count.MOBS, -1));
    assertThrows(
        IllegalArgumentException.class, () -> Scenario.DEFAULT.with(Count.NODE_CAPACITY, 0));
    assertThrows(IllegalArgumentException.class, () -> Scenario.DEFAULT.with(Chance.REQUEST, 1.5));
    assertThrows(
        IllegalArgumentException.class, () -> Scenario.DEFAULT.with(Chance.CHUNK_CHURN, -0.1));
    // A table with a setting left out cannot be completed by guessing.
    assertThrows(
        IllegalArgumentException.class,
        () -> new Scenario(Map.of(Count.MOBS, 1), Scenario.DEFAULT.chances()));
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
    assertThrows(
        IllegalArgumentException.class, () -> Topology.DEFAULT.withRegionSize(Chunks.SIZE + 4));
    assertThrows(IllegalArgumentException.class, () -> Topology.DEFAULT.withRegionsPerSide(0));
    // A column too short, or a hill that would leave a mob nowhere to stand.
    assertThrows(IllegalArgumentException.class, () -> Topology.DEFAULT.withHeight(16));
    assertThrows(IllegalArgumentException.class, () -> Topology.DEFAULT.withRelief(32));

    // One node per region, no more and no fewer.
    assertThrows(IllegalArgumentException.class, () -> new RegionMap(Topology.DEFAULT, nodes(3)));

    RegionMap regions = new RegionMap(Topology.DEFAULT, nodes(4));
    assertEquals(4, regions.count());
    assertThrows(IllegalArgumentException.class, () -> regions.minX(4));
    assertThrows(IllegalArgumentException.class, () -> regions.owner(-1));
  }

  @Test
  void theWorldRefusesBlocksItDoesNotHold() {
    Topology topology = ONE_REGION.topology().withMinY(-64);
    SimWorld world = new SimWorld(topology, 2, SeededRng.of(1L));

    assertEquals(-64, world.minY());
    assertThrows(IllegalArgumentException.class, () -> world.set(-1, -60, 0, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> world.set(0, 0, 0, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> world.set(0, -65, 0, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> world.chunkLoaded(9, 9));
    assertThrows(IllegalArgumentException.class, () -> new SimWorld(topology, 0, SeededRng.of(1L)));
    // Derived types cannot be a block's own type.
    assertThrows(
        IllegalArgumentException.class, () -> world.set(0, -60, 0, (byte) PathType.WALKABLE));
  }

  @Test
  void everySampledMobProfileIsCarriedOnTheWire() {
    // Profiles are drawn per run, so the encoder meets values that differ between
    // runs rather than one pair of constants.
    Set<String> shapes = new LinkedHashSet<>();
    for (int seed = 0; seed < DRAWS; seed++) {
      SimWorld world = new SimWorld(Topology.DEFAULT, 3, SeededRng.of(seed).derive("terrain"));
      assertEquals(3, world.mobClasses().size());
      world.mobClasses().forEach(profile -> shapes.add(profile.shape().toString()));
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
    SimTransport transport = sim.transport();

    assertThrows(IllegalStateException.class, () -> transport.connect(NodeId.of(0), "sim://0"));
    transport.listen(new NoopListener());
    assertThrows(IllegalStateException.class, () -> transport.listen(new NoopListener()));
    assertThrows(IllegalArgumentException.class, () -> transport.connect(NodeId.of(7), "sim://7"));
  }

  private static Scenario scenario(int capacity, int slowNodes) {
    return Scenario.DEFAULT.with(Count.NODE_CAPACITY, capacity).with(Count.SLOW_NODES, slowNodes);
  }

  private static List<NodeId> nodes(int count) {
    return IntStream.range(0, count).mapToObj(NodeId::of).toList();
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
    void assertCovers(Object label, double low, double high) {
      double margin = (high - low) * EDGE_MARGIN;
      assertTrue(
          this.low <= low + margin, label + ": lowest draw was " + this.low + ", floor is " + low);
      assertTrue(
          this.high >= high - margin,
          label + ": highest draw was " + this.high + ", ceiling is " + high);
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
