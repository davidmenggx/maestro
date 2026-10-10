package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The faults the network, the fleet and the controller's encode workers may inject into a run. The
 * drop and duplicate rates apply to unordered channels only, because an ordered channel cannot
 * lose a frame without breaking the connection. {@code offloadFailureRate} is the probability that
 * an offloaded encode throws.
 */
public record FaultPlan(
    int minLatencySteps,
    int maxLatencySteps,
    double dropRate,
    double duplicateRate,
    double offloadFailureRate,
    List<Fault> faults) {

  /** Scheduled fleet fault, applied at the start of its tick. */
  public sealed interface Fault {
    long tick();

    NodeId node();
  }

  /** Process death: state is lost and the peer sees a reset. */
  public record NodeDied(long tick, NodeId node) implements Fault {}

  /**
   * The connection breaks but the process survives, so the node keeps its region state. This is the
   * case resume-from-N is for: the node reconnects still holding most of the stream.
   */
  public record LinkReset(long tick, NodeId node) implements Fault {}

  public record NodeRecovered(long tick, NodeId node) implements Fault {}

  /** Only the failure detector can find a partition, unlike a disconnect. */
  public record Partitioned(long tick, NodeId node, boolean active) implements Fault {}

  /**
   * The ordered channels stop delivering while the link stays up, like TCP during a retransmit
   * backoff, and unordered frames still get through. Held frames arrive when the stall ends. This
   * is how a request can arrive before the profile it names.
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
   * Faults drawn per run. Several may hit one node, so the sweep covers a failure during a
   * recovery. Raising this makes the average run harsher without widening the space, trading runs
   * that get far enough to answer requests for runs that spend their whole length recovering.
   */
  static final int MAX_FAULTS = 5;

  static final int MAX_FAULT_SPAN_TICKS = 15;

  /** A node that dies this often is never brought back, so a run ends with one node fewer. */
  private static final double PERMANENT_LOSS_CHANCE = 0.15;

  /** Floor of the sampled latency range, in steps. Twenty steps is one millisecond. */
  static final int MAX_MIN_LATENCY_STEPS = 20;

  /**
   * Widest sampled range. The worst case a draw can reach, {@code MAX_MIN_LATENCY_STEPS +
   * MAX_LATENCY_SPAN_STEPS}, must stay well inside {@link Simulation#STEPS_PER_TICK}. Otherwise a
   * request is always late and the latency invariant says nothing about the controller. At 1000
   * steps per tick, 400 is 20ms.
   */
  static final int MAX_LATENCY_SPAN_STEPS = 400;

  /** No faults; latency is a few hundred microseconds. */
  public static FaultPlan reliable() {
    return new FaultPlan(2, 12, 0.0, 0.0, 0.0, List.of());
  }

  /**
   * One draw from the full range of latency, loss, duplication and encode failure, plus up to
   * {@link #MAX_FAULTS} fleet faults that may overlap on one node or hit several nodes at once.
   * Each fault starts inside {@code activeTicks} and, unless the node is permanently lost, ends
   * there too, so the quiet tail of the run can settle.
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
