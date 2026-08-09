package com.smpler.maestro.controller.sim;

import com.smpler.maestro.controller.env.NodeId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the network and the fleet are allowed to do to a run. Rates apply to unordered channels
 * only: an ordered channel is TCP-shaped, so its frames are never silently lost, and a break shows
 * up as a disconnect instead.
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

  public record NodeRecovered(long tick, NodeId node) implements Fault {}

  /** Silent black hole in both directions. No disconnect, so only the detector notices. */
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

  /** Nothing goes wrong; latency is a few hundred microseconds. */
  public static FaultPlan reliable() {
    return new FaultPlan(2, 12, 0.0, 0.0, List.of());
  }

  public FaultPlan withLatency(int minSteps, int maxSteps) {
    return new FaultPlan(minSteps, maxSteps, dropRate, duplicateRate, faults);
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

  @Override
  public String toString() {
    return "FaultPlan[latency="
        + minLatencySteps
        + ".."
        + maxLatencySteps
        + " drop="
        + dropRate
        + " dup="
        + duplicateRate
        + " faults="
        + Arrays.toString(faults.toArray())
        + "]";
  }
}
