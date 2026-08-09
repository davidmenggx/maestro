package com.smpler.maestro.controller.sim;

import com.smpler.maestro.controller.env.NodeId;
import java.util.ArrayList;
import java.util.List;

/**
 * One assembled run: world, fleet, controller and workload, all seeded from a single number. {@code
 * -Dmaestro.dst.seed} and {@code -Dmaestro.dst.runs} move the sweep, so a failing seed from CI
 * replays with `make dst SEED=...`.
 */
public final class SimRun {

  public static final int REGION_SIZE = 16;
  public static final int REGIONS_PER_SIDE = 2;
  public static final int NODE_CAPACITY = 8;
  public static final int MOB_COUNT = 12;
  public static final long DRAIN_TICKS = 25;

  /** p99 target from the SLO, in ticks. */
  public static final double TICK_BUDGET = 3.0;

  public final long seed;
  public final long ticks;
  public final Simulation sim;
  public final SimWorld world;
  public final RegionMap regions;
  public final RecordingOutput out;
  public final ProbeController controller;
  public final SyntheticWorkload workload;

  private SimRun(
      long seed,
      FaultPlan faults,
      long ticks,
      int nodeCapacity,
      int mobCount,
      double requestChance) {
    this.seed = seed;
    this.ticks = ticks;
    this.sim = new Simulation(seed, faults);

    List<NodeId> nodes = new ArrayList<>();
    for (int i = 0; i < REGIONS_PER_SIDE * REGIONS_PER_SIDE; i++) {
      NodeId id = NodeId.of(i);
      nodes.add(id);
      sim.addNode(id, nodeCapacity);
    }

    int extent = REGION_SIZE * REGIONS_PER_SIDE;
    this.regions = new RegionMap(REGION_SIZE, REGIONS_PER_SIDE, nodes);
    this.world = new SimWorld(extent, extent, sim.rng().derive("terrain"));
    this.out = new RecordingOutput(sim);
    this.controller = new ProbeController(sim.env(), world, out, regions, nodeCapacity);
    this.workload =
        new SyntheticWorkload(
            world, sim.rng().derive("workload"), mobCount, ticks - DRAIN_TICKS, requestChance);
  }

  public static SimRun of(long seed, FaultPlan faults, long ticks) {
    return new SimRun(
        seed, faults, ticks, NODE_CAPACITY, MOB_COUNT, SyntheticWorkload.DEFAULT_REQUEST_CHANCE);
  }

  /** Far more demand than the fleet can serve, so pre-send rejection has to hold the line. */
  public static SimRun saturated(long seed, FaultPlan faults, long ticks) {
    return new SimRun(seed, faults, ticks, 1, 40, 0.25);
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
