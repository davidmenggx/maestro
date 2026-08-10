package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Offload;
import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tick-driven deterministic simulator. Time is a step counter: {@link #STEPS_PER_TICK} steps make
 * one tick, and every delay in the harness is expressed in steps, so a run has no wall-clock
 * dependency at all. Events at the same step run in insertion order, which is what keeps two runs
 * of one seed identical.
 */
public final class Simulation {

  public static final int STEPS_PER_TICK = 1000;

  /** One tick is 50ms, so one step is 50 microseconds. */
  public static final long NANOS_PER_STEP = 50_000L;

  private record Scheduled(long time, long order, String name, Runnable action)
      implements Comparable<Scheduled> {

    @Override
    public int compareTo(Scheduled other) {
      int byTime = Long.compare(time, other.time);
      return byTime != 0 ? byTime : Long.compare(order, other.order);
    }
  }

  private final long seed;
  private final FaultPlan faults;
  private final SeededRng rng;
  private final Rng offloadRng;
  private final PriorityQueue<Scheduled> queue = new PriorityQueue<>();
  private final Map<NodeId, ModeledNode> nodes = new LinkedHashMap<>();
  private final SimMetrics metrics = new SimMetrics();
  private final Digest digest = new Digest();
  private final SimTransport transport;
  private final ControllerEnv env;

  private long now;
  private long order;
  private long tick = -1;

  public Simulation(long seed, FaultPlan faults) {
    this.seed = seed;
    this.faults = faults;
    this.rng = SeededRng.of(seed);
    this.offloadRng = rng.derive("offload");
    this.transport = new SimTransport(this, faults, rng.derive("transport"), nodes);
    this.env =
        new ControllerEnv(
            new SimClock(), rng.derive("controller"), new SimOffload(), transport, metrics);
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

  public long tick() {
    return tick;
  }

  /** Absolute step time. */
  public long now() {
    return now;
  }

  public ModeledNode addNode(NodeId id, int capacityPerTick) {
    ModeledNode node = new ModeledNode(this, transport, id, capacityPerTick);
    nodes.put(id, node);
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
    queue.add(new Scheduled(now + delaySteps, order++, name, action));
  }

  public void scheduleAt(long time, String name, Runnable action) {
    if (time <= now) {
      throw new IllegalArgumentException("cannot schedule into the past: " + time + " <= " + now);
    }
    queue.add(new Scheduled(time, order++, name, action));
  }

  /**
   * Runs {@code ticks} ticks. Each one applies its faults, opens the tick, lets the workload mutate
   * the world, lets the fleet serve what it has, then closes the tick, draining queued work between
   * every stage.
   */
  public void run(WorldInput controller, Workload workload, long ticks) {
    for (long i = 0; i < ticks; i++) {
      tick++;
      now = tick * STEPS_PER_TICK;
      long tickEnd = now + STEPS_PER_TICK - 1;

      applyFaults(tick);
      deliver(controller, new WorldEvent.TickBegan(tick));
      workload.tick(tick, event -> deliver(controller, event));

      // The fleet works through the tick rather than at its edge, so a response
      // is not pushed into the next tick purely by where the loop puts it.
      drainUntil(now + STEPS_PER_TICK / 2);
      for (ModeledNode node : nodes.values()) {
        node.service(tick);
      }
      drainUntil(tickEnd - 1);

      deliver(controller, new WorldEvent.TickEnded(tick));
      drainUntil(tickEnd);
    }
  }

  private void applyFaults(long tick) {
    for (FaultPlan.Fault fault : faults.at(tick)) {
      digest.mix(fault.toString());
      switch (fault) {
        case FaultPlan.NodeDied died -> transport.kill(died.node());
        case FaultPlan.NodeRecovered recovered -> transport.revive(recovered.node());
        case FaultPlan.Partitioned partitioned ->
            transport.partition(partitioned.node(), partitioned.on());
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
   * Work runs immediately, but its completion waits for the end of whichever tick it finishes in,
   * because that is where production drains finished jobs. The controller therefore never observes
   * a result inside the tick that submitted it.
   */
  private final class SimOffload implements Offload {

    /** A frame encode is tens of microseconds, so at most a couple of milliseconds. */
    private static final int MAX_WORK_STEPS = 40;

    @Override
    public <T> void submit(String name, Supplier<T> work, Consumer<T> completion) {
      T result = work.get();
      long ready = now + 1 + offloadRng.nextInt(MAX_WORK_STEPS);
      long drainAt = (ready / STEPS_PER_TICK) * STEPS_PER_TICK + STEPS_PER_TICK - 1;
      metrics.count("offload.submitted", 1, "job", name);
      scheduleAt(drainAt, "offload:" + name, () -> completion.accept(result));
    }
  }
}
