package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The faults the network, the fleet, the controller process and its encode workers may inject into
 * a run. The drop and duplicate rates apply to unordered channels only, because an ordered channel
 * cannot lose a frame without breaking the connection. {@code offloadFailureRate} is the
 * probability that an offloaded encode throws, until the run settles ({@link
 * Simulation#settleFrom}).
 */
public record FaultPlan(
    int minLatencySteps,
    int maxLatencySteps,
    double dropRate,
    double duplicateRate,
    double offloadFailureRate,
    List<Fault> faults) {

  /** Scheduled fault, applied at the start of its tick. */
  public sealed interface Fault {
    long tick();
  }

  /** A fault of one node. */
  public sealed interface NodeFault extends Fault {
    NodeId node();
  }

  /** Process death: state is lost and the peer sees a reset. */
  public record NodeDied(long tick, NodeId node) implements NodeFault {}

  /**
   * The connection breaks but the process survives, so the node keeps its region state. This is the
   * case resume-from-N is for: the node reconnects still holding most of the stream.
   */
  public record LinkReset(long tick, NodeId node) implements NodeFault {}

  public record NodeRecovered(long tick, NodeId node) implements NodeFault {}

  /** Only the failure detector can find a partition, unlike a disconnect. */
  public record Partitioned(long tick, NodeId node, boolean active) implements NodeFault {}

  /**
   * The ordered channels stop delivering while the link stays up, like TCP during a retransmit
   * backoff, and unordered frames still get through. Held frames arrive when the stall ends. This
   * is how a request can arrive before the profile it names.
   */
  public record Stalled(long tick, NodeId node, boolean active) implements NodeFault {}

  /**
   * The node's process freezes, as in a long collection pause: its links stay up, but it serves
   * nothing, sends no heartbeat, and handles what reaches it only once it resumes. This is the case
   * only a failure detector can tell from a slow node.
   */
  public record Paused(long tick, NodeId node, boolean active) implements NodeFault {}

  /**
   * Every datagram to and from the node is lost while its links and heartbeats carry on, as when a
   * firewall blocks the request port or the node answers from an address the controller does not
   * know. Only the missing answers show it.
   */
  public record Blackholed(long tick, NodeId node, boolean active) implements NodeFault {}

  /**
   * The controller process restarts with a new run id. Its links and everything it had in flight
   * are gone, and the nodes keep their world state from the old run.
   */
  public record ControllerRestarted(long tick) implements Fault {}

  /**
   * The server's work in this tick takes {@code steps}, as in a long collection pause or a burst of
   * chunk generation. The controller hears nothing meanwhile, while the fleet carries on. It cannot
   * delay or lose a request in ticks, so a plan with only these is still clean.
   */
  public record ServerStalled(long tick, int steps) implements Fault {}

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
   * Share of sampled plans with nothing that can delay or lose a request, so a sweep has runs the
   * latency SLO applies to. They still draw a latency range and a duplicate rate, and only those
   * whose fleet can serve their load in time are held to the SLO, about a quarter of them.
   */
  static final double CLEAN_CHANCE = 0.4;

  /**
   * Faults drawn per run. Several may hit one node, so the sweep covers a failure during a
   * recovery. Raising this makes the average run harsher without widening the space, trading runs
   * that get far enough to answer requests for runs that spend their whole length recovering.
   */
  static final int MAX_FAULTS = 5;

  static final int MAX_FAULT_SPAN_TICKS = 15;

  /**
   * Share of partitions, pauses and blackholes that last long enough for the heartbeat detector to
   * suspect the node, and for a quiet request path to fail a probe and be probed again.
   */
  static final double LONG_FAULT_CHANCE = 0.3;

  static final int MAX_LONG_FAULT_SPAN_TICKS = 160;

  /** Share of plans, clean ones included, whose server stalls once. */
  static final double STALL_CHANCE = 0.3;

  static final int MIN_STALL_STEPS = 20 * Simulation.STEPS_PER_TICK;

  /** The longest stall, 5 s. */
  static final int MAX_STALL_STEPS = 100 * Simulation.STEPS_PER_TICK;

  /** A node that dies this often is never brought back, so a run ends with one node fewer. */
  private static final double PERMANENT_LOSS_CHANCE = 0.15;

  /** Floor of the sampled latency range, in steps. Twenty steps is one millisecond. */
  static final int MAX_MIN_LATENCY_STEPS = 20;

  /** Widest sampled range. At 1000 steps per tick, 400 is 20ms. */
  static final int MAX_LATENCY_SPAN_STEPS = 400;

  /**
   * Share of clean plans whose latency sits just under the edge each way: the longest at which one
   * request served at the slowest node's highest cost is still answered in the tick its batch
   * arrives in ({@link #edgeLatency}). Such a run has no slack: a tick added by the controller
   * misses the SLO.
   */
  static final double EDGE_CHANCE = 0.5;

  /** Width of the latency range in an edge plan. */
  static final int EDGE_SPAN_STEPS = 100;

  /** No faults; latency is a few hundred microseconds. */
  public static FaultPlan reliable() {
    return new FaultPlan(2, 12, 0.0, 0.0, 0.0, List.of());
  }

  /**
   * Whether nothing in the plan can delay or lose a request: no loss, no failed encode, and no
   * fleet or controller fault. Latency, duplicates and server stalls are allowed, because none can.
   */
  public boolean clean() {
    return dropRate == 0
        && offloadFailureRate == 0
        && faults.stream().allMatch(fault -> fault instanceof ServerStalled);
  }

  /**
   * The longest one-way latency at which a request served in {@code maxCostSteps} by an idle node
   * is answered within the tick its batch arrives in: the batch leaves at the end of a tick, and
   * the answer must arrive before that tick's successor ends, where the controller also checks what
   * to resend. 0 if no latency is short enough.
   */
  static int edgeLatency(int maxCostSteps) {
    return Math.max(0, (Simulation.STEPS_PER_TICK - 1 - maxCostSteps) / 2);
  }

  /**
   * One draw from the full range of latency, loss, duplication and encode failure, plus up to
   * {@link #MAX_FAULTS} faults that may overlap on one node or hit several nodes at once. Each
   * fault starts inside {@code activeTicks} and, unless the node is permanently lost, ends there
   * too, so the quiet tail of the run can settle. A share {@link #LONG_FAULT_CHANCE} of the faults
   * that leave a link up but its node unable to answer last up to {@link
   * #MAX_LONG_FAULT_SPAN_TICKS}. One plan in {@link #CLEAN_CHANCE} has only latency and
   * duplication, and a share {@link #EDGE_CHANCE} of those has latency at the edge for a fleet
   * whose slowest request costs {@code maxCostSteps}. A share {@link #STALL_CHANCE} of all plans
   * stall the server once.
   */
  public static FaultPlan sample(Rng rng, int nodeCount, long activeTicks, int maxCostSteps) {
    int minLatency = 1 + rng.nextInt(MAX_MIN_LATENCY_STEPS);
    int maxLatency = minLatency + rng.nextInt(MAX_LATENCY_SPAN_STEPS);
    double duplicateRate = rng.nextDouble() * MAX_SAMPLED_RATE;
    List<Fault> stalls = new ArrayList<>();
    if (rng.nextDouble() < STALL_CHANCE && activeTicks > 1) {
      stalls.add(
          new ServerStalled(
              1 + rng.nextLong(activeTicks - 1),
              MIN_STALL_STEPS + rng.nextInt(MAX_STALL_STEPS - MIN_STALL_STEPS + 1)));
    }
    if (rng.nextDouble() < CLEAN_CHANCE) {
      int edge = edgeLatency(maxCostSteps);
      if (rng.nextDouble() < EDGE_CHANCE && edge > EDGE_SPAN_STEPS) {
        maxLatency = edge;
        minLatency = maxLatency - rng.nextInt(EDGE_SPAN_STEPS);
      }
      return new FaultPlan(minLatency, maxLatency, 0.0, duplicateRate, 0.0, stalls);
    }
    FaultPlan plan =
        new FaultPlan(
            minLatency,
            maxLatency,
            rng.nextDouble() * MAX_SAMPLED_RATE,
            duplicateRate,
            rng.nextDouble() * MAX_SAMPLED_RATE,
            stalls);
    if (nodeCount < 1 || activeTicks <= MAX_FAULT_SPAN_TICKS) {
      return plan;
    }
    int count = rng.nextInt(MAX_FAULTS + 1);
    for (int i = 0; i < count; i++) {
      NodeId node = NodeId.of(rng.nextInt(nodeCount));
      long start = 1 + rng.nextLong(activeTicks - MAX_FAULT_SPAN_TICKS);
      long end = start + 1 + rng.nextInt(MAX_FAULT_SPAN_TICKS - 1);
      long room = Math.min(MAX_LONG_FAULT_SPAN_TICKS, activeTicks - 1 - start);
      long longEnd =
          rng.nextDouble() < LONG_FAULT_CHANCE && room > MAX_FAULT_SPAN_TICKS
              ? start + MAX_FAULT_SPAN_TICKS + rng.nextLong(room - MAX_FAULT_SPAN_TICKS + 1)
              : end;
      plan =
          switch (rng.nextInt(7)) {
            case 0 ->
                rng.nextDouble() < PERMANENT_LOSS_CHANCE
                    ? plan.with(new NodeDied(start, node))
                    : plan.with(new NodeDied(start, node), new NodeRecovered(end, node));
            case 1 ->
                plan.with(
                    new Partitioned(start, node, true), new Partitioned(longEnd, node, false));
            case 2 -> plan.with(new Stalled(start, node, true), new Stalled(end, node, false));
            case 3 -> plan.with(new Paused(start, node, true), new Paused(longEnd, node, false));
            case 4 -> plan.with(new ControllerRestarted(start));
            case 5 ->
                plan.with(new Blackholed(start, node, true), new Blackholed(longEnd, node, false));
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
