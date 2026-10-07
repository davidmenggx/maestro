package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the network, the fleet and the controller's encode workers are allowed to do to a run. The
 * drop and duplicate rates apply to unordered channels only: an ordered channel loses nothing
 * without also breaking the connection. {@code offloadFailureRate} is the chance an offloaded
 * encode throws.
 */
public record FaultPlan(
    int minLatencySteps,
    int maxLatencySteps,
    double dropRate,
    double duplicateRate,
    double offloadFailureRate,
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
  public record Partitioned(long tick, NodeId node, boolean active) implements Fault {}

  /**
   * The ordered channels stop delivering while the link stays up, as TCP does while it backs off
   * retransmitting, and unordered frames still get through. What was held arrives when the stall
   * ends. This is how a request can overtake the profile it names.
   */
  public record Stalled(long tick, NodeId node, boolean active) implements Fault {}

  public FaultPlan {
    if (minLatencySteps < 1 || maxLatencySteps < minLatencySteps) {
      throw new IllegalArgumentException("latency range must be positive and ordered");
    }
    if (dropRate < 0
        || dropRate >= 1
        || duplicateRate < 0
        || duplicateRate >= 1
        || offloadFailureRate < 0
        || offloadFailureRate >= 1) {
      throw new IllegalArgumentException("rates must be in [0, 1)");
    }
    faults = List.copyOf(faults);
  }

  static final double MAX_SAMPLED_RATE = 0.1;

  /**
   * Faults drawn per run. Several may land on one node, so a failure during a recovery is a case
   * the sweep reaches. Raising it makes the average run rougher rather than the space wider, so it
   * trades runs that get far enough to answer anything for runs that spend their length recovering.
   */
  static final int MAX_FAULTS = 5;

  static final int MAX_FAULT_SPAN_TICKS = 15;

  /** A node that dies this often is never brought back, so a run ends a node short. */
  private static final double PERMANENT_LOSS_CHANCE = 0.15;

  /** Floor of the sampled latency range, in steps. Twenty steps is one millisecond. */
  static final int MAX_MIN_LATENCY_STEPS = 20;

  /**
   * Widest sampled range. The worst case a draw can reach, {@code MAX_MIN_LATENCY_STEPS +
   * MAX_LATENCY_SPAN_STEPS}, has to stay well inside {@link Simulation#STEPS_PER_TICK}, or a
   * request is late by construction and the latency invariant stops saying anything about the
   * controller. At 1000 steps a tick, 400 is 20ms.
   */
  static final int MAX_LATENCY_SPAN_STEPS = 400;

  /** Nothing goes wrong; latency is a few hundred microseconds. */
  public static FaultPlan reliable() {
    return new FaultPlan(2, 12, 0.0, 0.0, 0.0, List.of());
  }

  /**
   * One draw from the full range of latency, loss, duplication and encode failure, plus up to
   * {@link #MAX_FAULTS} fleet faults that may overlap on one node or hit several at once. Each
   * fault starts inside {@code activeTicks} and, unless the node is permanently lost, ends there
   * too, leaving the quiet tail of the run to settle.
   */
  public static FaultPlan sample(Rng rng, int nodeCount, long activeTicks) {
    int minLatency = 1 + rng.nextInt(MAX_MIN_LATENCY_STEPS);
    FaultPlan plan =
        new FaultPlan(
            minLatency,
            minLatency + rng.nextInt(MAX_LATENCY_SPAN_STEPS),
            rng.nextDouble() * MAX_SAMPLED_RATE,
            rng.nextDouble() * MAX_SAMPLED_RATE,
            rng.nextDouble() * MAX_SAMPLED_RATE,
            List.of());
    if (nodeCount < 1 || activeTicks <= MAX_FAULT_SPAN_TICKS) {
      return plan;
    }
    int count = rng.nextInt(MAX_FAULTS + 1);
    for (int i = 0; i < count; i++) {
      NodeId node = NodeId.of(rng.nextInt(nodeCount));
      long start = 1 + rng.nextLong(activeTicks - MAX_FAULT_SPAN_TICKS);
      long end = start + 1 + rng.nextInt(MAX_FAULT_SPAN_TICKS - 1);
      plan =
          switch (rng.nextInt(4)) {
            case 0 ->
                rng.nextDouble() < PERMANENT_LOSS_CHANCE
                    ? plan.with(new NodeDied(start, node))
                    : plan.with(new NodeDied(start, node), new NodeRecovered(end, node));
            case 1 ->
                plan.with(new Partitioned(start, node, true), new Partitioned(end, node, false));
            case 2 -> plan.with(new Stalled(start, node, true), new Stalled(end, node, false));
            default -> plan.with(new LinkReset(start, node));
          };
    }
    return plan;
  }

  public FaultPlan withDropRate(double rate) {
    return new FaultPlan(
        minLatencySteps, maxLatencySteps, rate, duplicateRate, offloadFailureRate, faults);
  }

  public FaultPlan withDuplicateRate(double rate) {
    return new FaultPlan(
        minLatencySteps, maxLatencySteps, dropRate, rate, offloadFailureRate, faults);
  }

  public FaultPlan withOffloadFailureRate(double rate) {
    return new FaultPlan(minLatencySteps, maxLatencySteps, dropRate, duplicateRate, rate, faults);
  }

  public FaultPlan with(Fault... added) {
    List<Fault> all = new ArrayList<>(faults);
    Collections.addAll(all, added);
    return new FaultPlan(
        minLatencySteps, maxLatencySteps, dropRate, duplicateRate, offloadFailureRate, all);
  }
}
