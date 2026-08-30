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
import org.junit.jupiter.api.Test;

/**
 * The harness has to be trustworthy before the runs it produces are. These cover the machinery the
 * assertions themselves lean on, and the guards that turn a harness misuse into a message.
 */
class HarnessGuardsTest {

  @Test
  void sampledScenariosSpanTheirRangeAndStayValid() {
    int lowestCapacity = Integer.MAX_VALUE;
    int highestCapacity = 0;
    boolean sawChurn = false;
    for (int seed = 0; seed < 200; seed++) {
      Scenario scenario = Scenario.sample(SeededRng.of(seed).derive("fuzz"));
      lowestCapacity = Math.min(lowestCapacity, scenario.nodeCapacity());
      highestCapacity = Math.max(highestCapacity, scenario.nodeCapacity());
      sawChurn |= scenario.chunkChurnChance() > 0;
    }

    // A sample stuck at one setting would not fuzz anything.
    assertEquals(1, lowestCapacity);
    assertTrue(highestCapacity >= 10, "capacity never reached the top of its range");
    assertTrue(sawChurn);
  }

  @Test
  void sampledFleetFaultsCoverEveryKindAndStayInsideTheActiveWindow() {
    long active = 100;
    Set<String> kinds = new LinkedHashSet<>();
    for (int seed = 0; seed < 200; seed++) {
      FaultPlan plan = FaultPlan.sample(SeededRng.of(seed).derive("fuzz"), 4, active);
      for (FaultPlan.Fault fault : plan.faults()) {
        kinds.add(fault.getClass().getSimpleName());
        assertTrue(fault.tick() >= 1 && fault.tick() < active, "fault at tick " + fault.tick());
      }
    }

    // Process death, a silent partition and a link reset are three different
    // recoveries, so the sweep has to reach all of them.
    assertEquals(Set.of("NodeDied", "NodeRecovered", "Partitioned", "LinkReset"), kinds);
    assertEquals(List.of(), FaultPlan.sample(SeededRng.of(1).derive("fuzz"), 0, active).faults());
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

    assertEquals(2, metrics.counter("frames", "node", "node-0"));
    assertEquals(5, metrics.counter("frames", "node", "node-1"));
    assertEquals(0, metrics.counter("frames"));
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
    assertThrows(IllegalArgumentException.class, () -> new Scenario(0, 1, 0.1, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new Scenario(1, -1, 0.1, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new Scenario(1, 1, 1.5, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new Scenario(1, 1, 0.1, -0.1));
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

  @Test
  void geometryMustBeChunkAligned() {
    assertThrows(IllegalArgumentException.class, () -> new RegionMap(20, 1, List.of(NodeId.of(0))));
    assertThrows(IllegalArgumentException.class, () -> new RegionMap(16, 2, List.of(NodeId.of(0))));
    assertThrows(IllegalArgumentException.class, () -> new SimWorld(20, SeededRng.of(1L)));

    RegionMap regions = new RegionMap(16, 2, nodes(4));
    assertEquals(32, regions.extent());
    assertThrows(IllegalArgumentException.class, () -> regions.minX(4));
    assertThrows(IllegalArgumentException.class, () -> regions.owner(-1));
  }

  @Test
  void theWorldRefusesBlocksItDoesNotHold() {
    SimWorld world = new SimWorld(16, SeededRng.of(1L));

    assertThrows(IllegalArgumentException.class, () -> world.set(-1, 1, 0, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> world.set(0, SimWorld.HEIGHT, 0, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> world.chunkLoaded(9, 9));
    // Derived types cannot be a block's own type.
    assertThrows(IllegalArgumentException.class, () -> world.set(0, 1, 0, (byte) 2));
  }

  private static List<NodeId> nodes(int count) {
    return java.util.stream.IntStream.range(0, count).mapToObj(NodeId::of).toList();
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
