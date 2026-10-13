package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Offload;
import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tick-driven deterministic simulator. Time is a step counter: {@link #STEPS_PER_TICK} steps make
 * one tick, and every delay in the harness is in steps. Events at the same step run in insertion
 * order, so two runs of one seed are identical.
 */
public final class Simulation {

  /** Intra-tick resolution. One step is 50 microseconds. */
  public static final int STEPS_PER_TICK = 1000;

  /** A Minecraft tick is 50ms. */
  static final long NANOS_PER_TICK = 50_000_000L;

  static final long NANOS_PER_STEP = NANOS_PER_TICK / STEPS_PER_TICK;

  /** When in the tick the fleet works. Mid-tick, so serving does not run into the next tick. */
  static final int SERVICE_STEP = STEPS_PER_TICK / 2;

  private record Scheduled(long time, long order, String name, Runnable action)
      implements Comparable<Scheduled> {

    @Override
    public int compareTo(Scheduled other) {
      int byTime = Long.compare(time, other.time);
      return byTime != 0 ? byTime : Long.compare(order, other.order);
    }
  }

  private final SeededRng rng;
  private final Rng offloadRng;
  private final PriorityQueue<Scheduled> queue = new PriorityQueue<>();
  private final Map<NodeId, ModeledNode> nodes = new LinkedHashMap<>();
  private final Map<Long, List<FaultPlan.Fault>> faultsByTick = new LinkedHashMap<>();

  /** Highest world sequence a fleet node applied, per region. */
  private final Map<Long, Long> appliedByRegion = new LinkedHashMap<>();

  private final List<String> streamViolations = new ArrayList<>();
  private final SimMetrics metrics = new SimMetrics();
  private final Digest digest = new Digest();
  private final SimTransport transport;
  private final ControllerEnv env;
  private final double offloadFailureRate;

  private long now;
  private long order;
  private long tick = -1;
  private long settleFrom = Long.MAX_VALUE;

  public Simulation(long seed, FaultPlan faults) {
    this.rng = SeededRng.of(seed);
    this.offloadRng = rng.derive("offload");
    this.transport = new SimTransport(this, faults, rng.derive("transport"), nodes);
    this.offloadFailureRate = faults.offloadFailureRate();
    this.env =
        new ControllerEnv(
            new SimClock(), rng.derive("controller"), new SimOffload(), transport, metrics);
    for (FaultPlan.Fault fault : faults.faults()) {
      faultsByTick.computeIfAbsent(fault.tick(), tick -> new ArrayList<>()).add(fault);
    }
  }

  public ControllerEnv env() {
    return env;
  }

  public SimTransport transport() {
    return transport;
  }

  public SimMetrics metrics() {
    return metrics;
  }

  public Digest digest() {
    return digest;
  }

  public Rng rng() {
    return rng;
  }

  /** Absolute step time. */
  public long now() {
    return now;
  }

  /**
   * From {@code tick} on, no encode fails. Fleet faults end before a run's quiet tail, and this
   * ends the last fault that could hold a world frame back, so every live node can catch up before
   * the run ends.
   */
  public void settleFrom(long tick) {
    settleFrom = tick;
  }

  /**
   * Records that a fleet node applied {@code sequence} of a region's world stream. The sequence of
   * a region only rises, whichever node holds it and however often it is sent again.
   */
  void applied(NodeId node, long region, long sequence) {
    long highest = appliedByRegion.getOrDefault(region, 0L);
    if (sequence <= highest) {
      streamViolations.add(
          node + " applied sequence " + sequence + " of region " + region + " after " + highest);
    }
    appliedByRegion.put(region, Math.max(highest, sequence));
  }

  /** Every time a region's world stream went backwards. */
  public List<String> streamViolations() {
    return List.copyOf(streamViolations);
  }

  public ModeledNode addNode(NodeId id, int capacityPerTick) {
    ModeledNode node = new ModeledNode(this, transport, id, capacityPerTick);
    if (nodes.put(id, node) != null) {
      throw new IllegalArgumentException(id + " is already in the fleet");
    }
    return node;
  }

  public Collection<ModeledNode> nodes() {
    return nodes.values();
  }

  public ModeledNode node(NodeId id) {
    return nodes.get(id);
  }

  public void schedule(long delaySteps, String name, Runnable action) {
    if (delaySteps < 1) {
      throw new IllegalArgumentException("delay must be at least one step: " + delaySteps);
    }
    scheduleAt(now + delaySteps, name, action);
  }

  public void scheduleAt(long time, String name, Runnable action) {
    if (time <= now) {
      throw new IllegalArgumentException("cannot schedule into the past: " + time + " <= " + now);
    }
    queue.add(new Scheduled(time, order++, name, action));
  }

  /**
   * Runs {@code ticks} ticks. Each tick applies its faults, opens the tick, lets the workload
   * change the world, lets the fleet serve its requests, then closes the tick. Queued work is
   * drained between stages.
   */
  public void run(WorldInput controller, Workload workload, long ticks) {
    if (ticks < 1) {
      throw new IllegalArgumentException("a run needs at least one tick: " + ticks);
    }
    Objects.requireNonNull(controller, "controller");
    Objects.requireNonNull(workload, "workload");
    for (long i = 0; i < ticks; i++) {
      tick++;
      now = tick * STEPS_PER_TICK;
      long tickEnd = now + STEPS_PER_TICK - 1;

      applyFaults(tick);
      deliver(controller, new WorldEvent.TickBegan(tick));
      workload.tick(tick, event -> deliver(controller, event));

      drainUntil(now + SERVICE_STEP);
      for (ModeledNode node : nodes.values()) {
        node.service();
      }
      drainUntil(tickEnd - 1);

      deliver(controller, new WorldEvent.TickEnded(tick));
      drainUntil(tickEnd);
    }
  }

  private void applyFaults(long tick) {
    for (FaultPlan.Fault fault : faultsByTick.getOrDefault(tick, List.of())) {
      digest.mix(fault.toString());
      switch (fault) {
        case FaultPlan.NodeDied died -> transport.kill(died.node());
        case FaultPlan.LinkReset reset -> transport.resetLink(reset.node());
        case FaultPlan.NodeRecovered recovered -> transport.revive(recovered.node());
        case FaultPlan.Partitioned partitioned ->
            transport.partition(partitioned.node(), partitioned.active());
        case FaultPlan.Stalled stalled -> transport.stall(stalled.node(), stalled.active());
      }
    }
  }

  private void deliver(WorldInput controller, WorldEvent event) {
    digest.mix(now).mix(event.toString());
    controller.accept(event);
  }

  private void drainUntil(long limit) {
    while (!queue.isEmpty() && queue.peek().time() <= limit) {
      Scheduled next = queue.poll();
      now = Math.max(now, next.time());
      digest.mix(now).mix(next.name());
      next.action().run();
    }
    now = Math.max(now, limit);
  }

  private final class SimClock implements Clock {

    @Override
    public long tick() {
      return Simulation.this.tick;
    }

    @Override
    public long nowNanos() {
      return Simulation.this.now * NANOS_PER_STEP;
    }
  }

  /**
   * Work runs immediately, but its completion waits for the end of the tick it finishes in, because
   * production drains finished jobs there. A result therefore never reaches the controller inside
   * the call that submitted it. A job that the fault plan fails reports its failure at the same
   * point.
   */
  private final class SimOffload implements Offload {

    /** A frame encode takes tens of microseconds, so at most a couple of milliseconds. */
    private static final int MAX_WORK_STEPS = 40;

    @Override
    public <T> void submit(
        String name, Supplier<T> work, Consumer<T> completion, Runnable failure) {
      long ready = now + 1 + offloadRng.nextInt(MAX_WORK_STEPS);
      long drainAt = (ready / STEPS_PER_TICK) * STEPS_PER_TICK + STEPS_PER_TICK - 1;
      metrics.count("offload.submitted", 1, "job", name);
      // Drawn only when failures are possible, so a plan without them replays unchanged.
      if (tick < settleFrom
          && offloadFailureRate > 0
          && offloadRng.nextDouble() < offloadFailureRate) {
        metrics.count("offload.failed", 1, "job", name);
        scheduleAt(drainAt, "offload-failed:" + name, failure);
        return;
      }
      T result = work.get();
      scheduleAt(drainAt, "offload:" + name, () -> completion.accept(result));
    }
  }
}
