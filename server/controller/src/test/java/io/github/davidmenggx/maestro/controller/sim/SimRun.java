package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
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

  /**
   * Trailing quiet ticks, so every in-flight request resolves before the invariants are checked.
   */
  public static final long DRAIN_TICKS = 25;

  /** p99 target from the SLO, in ticks. Looser than the admission bound in `ProbeController`. */
  public static final double TICK_BUDGET = 3.0;

  /** How much demand a run puts on the fleet. */
  public record Scenario(int nodeCapacity, int mobCount, double requestChance) {

    public static final Scenario DEFAULT =
        new Scenario(8, 12, SyntheticWorkload.DEFAULT_REQUEST_CHANCE);

    /** Far more demand than the fleet can serve, so pre-send rejection has to hold the line. */
    public static final Scenario SATURATED = new Scenario(1, 40, 0.25);
  }

  public final long seed;
  public final long ticks;
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
      int nodeCapacity,
      BiFunction<SimWorld, Rng, Workload> workloadFactory) {
    this.seed = seed;
    this.ticks = ticks;
    this.sim = new Simulation(seed, faults);

    List<NodeId> nodes = new ArrayList<>();
    for (int i = 0; i < REGIONS_PER_SIDE * REGIONS_PER_SIDE; i++) {
      NodeId id = NodeId.of(i);
      nodes.add(id);
      sim.addNode(id, nodeCapacity);
    }

    this.regions = new RegionMap(REGION_SIZE, REGIONS_PER_SIDE, nodes);
    this.world = new SimWorld(regions.extent(), sim.rng().derive("terrain"));
    this.out = new RecordingOutput(sim);
    this.controller = new ProbeController(sim.env(), world, out, regions, nodeCapacity);
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
        scenario.nodeCapacity(),
        (world, rng) ->
            new SyntheticWorkload(
                world, rng, scenario.mobCount(), ticks - DRAIN_TICKS, scenario.requestChance()));
  }

  /** A run driven by a fixed event list rather than a generator, for focused tests. */
  public static SimRun scripted(long seed, FaultPlan faults, long ticks, List<WorldEvent> events) {
    return new SimRun(
        seed,
        faults,
        ticks,
        Scenario.DEFAULT.nodeCapacity(),
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
