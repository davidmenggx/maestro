package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the network and the fleet are allowed to do to a run. Rates apply to unordered channels
 * only: an ordered channel loses nothing without also breaking the connection.
 */
public record FaultPlan(
    int minLatencySteps,
    int maxLatencySteps,
    double dropRate,
    double duplicateRate,
    List<Fault> faults) {

  /** Scheduled fleet event, applied at the start of its tick. */
  public sealed interface Fault {
    long tick();

    NodeId node();
  }

  /** Process death: state is lost and the peer sees a reset. */
  public record NodeDied(long tick, NodeId node) implements Fault {}

  /**
   * The connection breaks but the process lives, so the node keeps its region state. This is the
   * case resume-from-N exists for: the node reconnects still holding most of the stream.
   */
  public record LinkReset(long tick, NodeId node) implements Fault {}

  public record NodeRecovered(long tick, NodeId node) implements Fault {}

  /** Partitions are only found by the failure detector (as opposed to disconnects). */
  public record Partitioned(long tick, NodeId node, boolean on) implements Fault {}

  public FaultPlan {
    if (minLatencySteps < 1 || maxLatencySteps < minLatencySteps) {
      throw new IllegalArgumentException("latency range must be positive and ordered");
    }
    if (dropRate < 0 || dropRate >= 1 || duplicateRate < 0 || duplicateRate >= 1) {
      throw new IllegalArgumentException("rates must be in [0, 1)");
    }
    faults = List.copyOf(faults);
  }

  private static final double MAX_SAMPLED_RATE = 0.1;
  private static final double FLEET_FAULT_CHANCE = 0.5;
  private static final int MAX_FAULT_SPAN_TICKS = 15;

  /** Nothing goes wrong; latency is a few hundred microseconds. */
  public static FaultPlan reliable() {
    return new FaultPlan(2, 12, 0.0, 0.0, List.of());
  }

  /**
   * One draw from the full range of loss and duplication, plus at most one fleet fault. The fault
   * both starts and ends inside {@code activeTicks}, leaving the quiet tail of the run to settle.
   */
  public static FaultPlan sample(Rng rng, int nodeCount, long activeTicks) {
    FaultPlan plan =
        reliable()
            .withLoss(rng.nextDouble() * MAX_SAMPLED_RATE, rng.nextDouble() * MAX_SAMPLED_RATE);
    if (nodeCount < 1
        || activeTicks <= MAX_FAULT_SPAN_TICKS
        || rng.nextDouble() >= FLEET_FAULT_CHANCE) {
      return plan;
    }
    NodeId node = NodeId.of(rng.nextInt(nodeCount));
    long start = 1 + rng.nextLong(activeTicks - MAX_FAULT_SPAN_TICKS);
    long end = start + 1 + rng.nextInt(MAX_FAULT_SPAN_TICKS - 1);
    return switch (rng.nextInt(3)) {
      case 0 -> plan.with(new NodeDied(start, node), new NodeRecovered(end, node));
      case 1 -> plan.with(new Partitioned(start, node, true), new Partitioned(end, node, false));
      default -> plan.with(new LinkReset(start, node));
    };
  }

  public FaultPlan withLoss(double drop, double duplicate) {
    return new FaultPlan(minLatencySteps, maxLatencySteps, drop, duplicate, faults);
  }

  public FaultPlan with(Fault... added) {
    List<Fault> all = new ArrayList<>(faults);
    Collections.addAll(all, added);
    return new FaultPlan(minLatencySteps, maxLatencySteps, dropRate, duplicateRate, all);
  }

  public List<Fault> at(long tick) {
    return faults.stream().filter(f -> f.tick() == tick).toList();
  }
}
