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
 * replays with `make dst SEED=...`.
 */
public final class SimRun {

  /** Region geometry. `RegionMap` requires a chunk-aligned size, and the world is sized from it. */
  public static final int REGION_SIZE = 16;

  public static final int REGIONS_PER_SIDE = 2;

  /** One node per region, since resharding is not modeled. */
  public static final int NODE_COUNT = REGIONS_PER_SIDE * REGIONS_PER_SIDE;

  /**
   * Trailing quiet ticks, so every in-flight request resolves before the invariants are checked.
   */
  public static final long DRAIN_TICKS = 25;

  /** p99 target from the SLO, in ticks. Looser than the admission bound in `ProbeController`. */
  public static final double TICK_BUDGET = 3.0;

  public final long seed;
  public final long ticks;
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
      Scenario scenario,
      BiFunction<SimWorld, Rng, Workload> workloadFactory) {
    this.seed = seed;
    this.ticks = ticks;
    this.scenario = scenario;
    this.sim = new Simulation(seed, faults);

    List<NodeId> nodes = new ArrayList<>();
    for (int i = 0; i < NODE_COUNT; i++) {
      NodeId id = NodeId.of(i);
      nodes.add(id);
      sim.addNode(id, scenario.nodeCapacity());
    }

    this.regions = new RegionMap(REGION_SIZE, REGIONS_PER_SIDE, nodes);
    this.world = new SimWorld(regions.extent(), sim.rng().derive("terrain"));
    this.out = new RecordingOutput(sim);
    this.controller = new ProbeController(sim.env(), world, out, regions, scenario.nodeCapacity());
    this.workload = workloadFactory.apply(world, sim.rng().derive("workload"));
  }

  public static SimRun of(long seed, FaultPlan faults, long ticks) {
    return of(seed, faults, ticks, Scenario.DEFAULT);
  }

  public static SimRun of(long seed, FaultPlan faults, long ticks, Scenario scenario) {
    return new SimRun(
        seed,
        faults,
        ticks,
        scenario,
        (world, rng) -> new SyntheticWorkload(world, rng, ticks - DRAIN_TICKS, scenario));
  }

  /**
   * A run whose load and faults are drawn from the seed as well as its event ordering, so a sweep
   * covers the ranges in {@link Scenario} and {@link FaultPlan} rather than one fixed setting.
   */
  public static SimRun fuzzed(long seed, long ticks) {
    Rng rng = SeededRng.of(seed).derive("fuzz");
    Scenario scenario = Scenario.sample(rng);
    FaultPlan faults = FaultPlan.sample(rng, NODE_COUNT, ticks - DRAIN_TICKS);
    return of(seed, faults, ticks, scenario);
  }

  /** A run driven by a fixed event list rather than a generator, for focused tests. */
  public static SimRun scripted(long seed, FaultPlan faults, long ticks, List<WorldEvent> events) {
    return new SimRun(
        seed, faults, ticks, Scenario.DEFAULT, (world, rng) -> new ScriptedWorkload(world, events));
  }

  public SimRun go() {
    sim.run(controller, workload, ticks);
    return this;
  }

  public List<String> violations() {
    return violations(TICK_BUDGET);
  }

  public List<String> violations(double tickBudget) {
    return Invariants.check(sim, controller, out, tickBudget);
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
        + " scenario="
        + scenario
        + " digest="
        + Long.toHexString(sim.digest().value());
  }

  public static long baseSeed() {
    return Long.getLong("maestro.dst.seed", 1L);
  }

  public static int runCount() {
    return Integer.getInteger("maestro.dst.runs", 8);
  }
}
