package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * One assembled run: world, fleet, controller and workload, all seeded from a single number. {@code
 * -Dmaestro.dst.seed} and {@code -Dmaestro.dst.runs} move the sweep, so a failing seed from CI
 * replays with `make fuzz SEED=...`.
 */
public final class SimRun {

  /**
   * Trailing quiet ticks, so every in-flight request resolves before the invariants are checked.
   */
  public static final long DRAIN_TICKS = 25;

  /** p99 target from the SLO, in ticks. Looser than the admission bound in `ProbeController`. */
  public static final double TICK_BUDGET = 3.0;

  /** Sweep size when nothing sets {@code maestro.dst.runs}. */
  private static final int DEFAULT_RUNS = 100;

  /**
   * Length of a fuzzed run. The active part, everything before {@link #DRAIN_TICKS}, has to leave
   * room for a fault to start and finish, or the shortest runs draw no faults at all.
   */
  static final long MIN_FUZZED_TICKS = 60;

  static final long MAX_FUZZED_TICKS = 320;

  public final long seed;
  public final long ticks;
  public final Topology topology;
  public final Scenario scenario;
  public final Simulation sim;
  public final SimWorld world;
  public final RegionMap regions;
  public final RecordingOutput out;
  public final ProbeController controller;
  public final Workload workload;

  private SimRun(
      long seed,
      FaultPlan faults,
      long ticks,
      Topology topology,
      Scenario scenario,
      BiFunction<SimWorld, Rng, Workload> workloadFactory) {
    this.seed = seed;
    this.ticks = ticks;
    this.topology = topology;
    this.scenario = scenario;
    this.sim = new Simulation(seed, faults);

    List<NodeId> nodes = new ArrayList<>();
    for (int i = 0; i < topology.nodeCount(); i++) {
      NodeId id = NodeId.of(i);
      nodes.add(id);
      sim.addNode(id, scenario.capacityOf(i));
    }

    this.regions = new RegionMap(topology.regionSize(), topology.regionsPerSide(), nodes);
    this.world = new SimWorld(topology, scenario.mobClassCount(), sim.rng().derive("terrain"));
    this.out = new RecordingOutput(sim);
    this.controller = new ProbeController(sim.env(), world, out, regions, scenario.nodeCapacity());
    this.workload = workloadFactory.apply(world, sim.rng().derive("workload"));
  }

  public static SimRun of(long seed, FaultPlan faults, long ticks) {
    return of(seed, faults, ticks, Scenario.DEFAULT);
  }

  public static SimRun of(long seed, FaultPlan faults, long ticks, Scenario scenario) {
    return of(seed, faults, ticks, Topology.DEFAULT, scenario);
  }

  public static SimRun of(
      long seed, FaultPlan faults, long ticks, Topology topology, Scenario scenario) {
    return new SimRun(
        seed,
        faults,
        ticks,
        topology,
        scenario,
        (world, rng) -> new SyntheticWorkload(world, rng, ticks - DRAIN_TICKS, scenario));
  }

  /**
   * A run whose geometry, load, length and faults are all drawn from the seed alongside its event
   * ordering, so a sweep covers the ranges in {@link Topology}, {@link Scenario} and {@link
   * FaultPlan} rather than one fixed setting.
   */
  public static SimRun fuzzed(long seed) {
    Rng rng = SeededRng.of(seed).derive("fuzz");
    long ticks = MIN_FUZZED_TICKS + rng.nextLong(MAX_FUZZED_TICKS - MIN_FUZZED_TICKS + 1);
    Topology topology = Topology.sample(rng);
    Scenario scenario = Scenario.sample(rng);
    FaultPlan faults = FaultPlan.sample(rng, topology.nodeCount(), ticks - DRAIN_TICKS);
    return of(seed, faults, ticks, topology, scenario);
  }

  /** A run driven by a fixed event list rather than a generator, for focused tests. */
  public static SimRun scripted(long seed, FaultPlan faults, long ticks, List<WorldEvent> events) {
    return new SimRun(
        seed,
        faults,
        ticks,
        Topology.DEFAULT,
        Scenario.DEFAULT,
        (world, rng) -> new ScriptedWorkload(world, events));
  }

  public SimRun go() {
    sim.run(controller, workload, ticks);
    return this;
  }

  public List<String> violations() {
    return violations(TICK_BUDGET);
  }

  public List<String> violations(double tickBudget) {
    return Invariants.check(sim, controller, out, world, regions, tickBudget);
  }

  /** Seed and outcome, for a failure message that is enough to reproduce the run. */
  public String describe() {
    return "seed="
        + seed
        + " ticks="
        + ticks
        + " issued="
        + controller.requestsIssued()
        + " answered="
        + out.answered()
        + " drops="
        + out.drops()
        + " topology="
        + topology
        + " scenario="
        + scenario
        + " digest="
        + Long.toHexString(sim.digest().value());
  }

  public static long baseSeed() {
    return Long.getLong("maestro.dst.seed", 1L);
  }

  public static int runCount() {
    return Integer.getInteger("maestro.dst.runs", DEFAULT_RUNS);
  }
}
