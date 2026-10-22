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
import java.util.function.LongConsumer;
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

  private record Scheduled(long time, long order, String name, Runnable action)
      implements Comparable<Scheduled> {

    @Override
    public int compareTo(Scheduled other) {
      int byTime = Long.compare(time, other.time);
      return byTime != 0 ? byTime : Long.compare(order, other.order);
    }
  }

  /** One region's stream in one controller run. */
  private record Stream(long run, long region) {}

  /** One holder's copy of a region's stream. */
  private record HeldStream(NodeId node, long run, long region) {}

  private record Checkpoint(long run, long region, long sequence) {}

  private final SeededRng rng;
  private final Rng offloadRng;
  private final Rng runRng;
  private final Rng controllerRng;
  private final PriorityQueue<Scheduled> queue = new PriorityQueue<>();
  private final Map<NodeId, ModeledNode> nodes = new LinkedHashMap<>();
  private final Map<Long, List<FaultPlan.Fault>> faultsByTick = new LinkedHashMap<>();

  /** Highest world sequence any fleet node applied, per region stream. */
  private final Map<Stream, Long> appliedByRegion = new LinkedHashMap<>();

  /** Highest world sequence each holder applied, per region stream. */
  private final Map<HeldStream, Long> appliedByHolder = new LinkedHashMap<>();

  /**
   * What each holder of a region must hold once it applies a sequence, from {@link #checkpoint}.
   * Kept for the whole run, since every holder of the region reaches it.
   */
  private final Map<Checkpoint, Long> checkpoints = new LinkedHashMap<>();

  private final List<String> streamViolations = new ArrayList<>();
  private final SimMetrics metrics = new SimMetrics();
  private final Digest digest = new Digest();
  private final SimTransport transport;
  private final SimClock clock = new SimClock();
  private final double offloadFailureRate;

  private ControllerEnv env;
  private int incarnation;
  private Runnable onRestart = () -> {};
  private long checkpointsCompared;
  private long now;
  private long order;
  private long tick = -1;
  private long settleFrom = Long.MAX_VALUE;

  public Simulation(long seed, FaultPlan faults) {
    this.rng = SeededRng.of(seed);
    this.offloadRng = rng.derive("offload");
    this.runRng = rng.derive("run");
    this.controllerRng = rng.derive("controller");
    this.transport = new SimTransport(this, faults, rng.derive("transport"), nodes);
    this.offloadFailureRate = faults.offloadFailureRate();
    for (FaultPlan.Fault fault : faults.faults()) {
      faultsByTick.computeIfAbsent(fault.tick(), tick -> new ArrayList<>()).add(fault);
    }
    startController();
  }

  /** The current controller process's environment. */
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

  /** Controller processes started so far. */
  public int incarnations() {
    return incarnation;
  }

  /** Mid-run checkpoints a node reached and was compared at. */
  public long checkpointsCompared() {
    return checkpointsCompared;
  }

  /**
   * From {@code tick} on, no encode fails. Fleet faults end before a run's quiet tail, and this
   * ends the last fault that could hold a world frame back, so every live node can catch up before
   * the run ends.
   */
  public void settleFrom(long tick) {
    settleFrom = tick;
  }

  /** Runs when a fault restarts the controller, after the new process's environment exists. */
  public void onRestart(Runnable action) {
    onRestart = Objects.requireNonNull(action, "action");
  }

  /**
   * A new controller process: a new run id, its own transport session and offload, and nothing left
   * of the old process. What the old one had in flight is lost.
   */
  private void startController() {
    incarnation++;
    long run = 0;
    while (run == 0) {
      run = runRng.nextLong();
    }
    env =
        new ControllerEnv(
            clock,
            controllerRng.derive("process-" + incarnation),
            new SimOffload(incarnation),
            transport.restart(),
            metrics,
            run);
  }

  /**
   * Records that a fleet node applied {@code sequence} of a region's world stream. The sequence a
   * holder applies only rises, however often the region is sent to it again, and a holder at a
   * checkpoint holds what the world held when the checkpoint was taken.
   */
  void applied(ModeledNode node, long run, long region, long sequence) {
    HeldStream held = new HeldStream(node.id(), run, region);
    long highest = appliedByHolder.getOrDefault(held, 0L);
    if (sequence <= highest) {
      streamViolations.add(
          node.id()
              + " applied sequence "
              + sequence
              + " of region "
              + region
              + " after "
              + highest);
    }
    appliedByHolder.put(held, Math.max(highest, sequence));
    appliedByRegion.merge(new Stream(run, region), sequence, Math::max);
    Long expected = checkpoints.get(new Checkpoint(run, region, sequence));
    if (expected != null) {
      checkpointsCompared++;
      if (node.stateDigest() != expected) {
        streamViolations.add(
            node.id()
                + " holds "
                + Long.toHexString(node.stateDigest())
                + " at sequence "
                + sequence
                + " of region "
                + region
                + ", but the world held "
                + Long.toHexString(expected));
      }
    }
  }

  /**
   * Records that a node took a fresh assignment. A stream starts over only where it stands, never
   * behind a frame a node already applied, so it has no gap a node could have needed.
   */
  void assigned(ModeledNode node, long run, long region, long sequence) {
    long highest = appliedByRegion.getOrDefault(new Stream(run, region), 0L);
    // The holder's own copy starts over here too.
    appliedByHolder.put(new HeldStream(node.id(), run, region), sequence);
    if (sequence < highest) {
      streamViolations.add(
          node.id()
              + " was assigned region "
              + region
              + " at sequence "
              + sequence
              + ", behind the applied "
              + highest);
    }
  }

  /**
   * The state a holder of the region must have once it applies {@code sequence}, which the
   * controller produced last for it.
   */
  void checkpoint(long run, long region, long sequence, long stateDigest) {
    checkpoints.put(new Checkpoint(run, region, sequence), stateDigest);
  }

  /** Every time a region's world stream went backwards, or a node's state left the world's. */
  public List<String> streamViolations() {
    return List.copyOf(streamViolations);
  }

  public ModeledNode addNode(NodeId id, ModeledNode.Service service) {
    if (nodes.containsKey(id)) {
      throw new IllegalArgumentException(id + " is already in the fleet");
    }
    ModeledNode node =
        new ModeledNode(id, transport.hostFor(id), service, rng.derive("node-" + id.id()));
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
    scheduleAt(now + delaySteps, name, action);
  }

  public void scheduleAt(long time, String name, Runnable action) {
    if (time <= now) {
      throw new IllegalArgumentException("cannot schedule into the past: " + time + " <= " + now);
    }
    queue.add(new Scheduled(time, order++, name, action));
  }

  /** As below, with nothing to do after each tick. */
  public void run(WorldInput controller, Workload workload, long ticks) {
    run(controller, workload, ticks, tick -> {});
  }

  /**
   * Runs {@code ticks} ticks. Each tick applies its faults, opens the tick, lets the workload
   * change the world, lets the fleet work until the tick's last step, then closes the tick and runs
   * {@code afterTick}. Queued work is drained between stages.
   */
  public void run(WorldInput controller, Workload workload, long ticks, LongConsumer afterTick) {
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

      drainUntil(tickEnd - 1);

      deliver(controller, new WorldEvent.TickEnded(tick));
      drainUntil(tickEnd);
      afterTick.accept(tick);
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
        case FaultPlan.Paused paused -> transport.pause(paused.node(), paused.active());
        case FaultPlan.ControllerRestarted restarted -> {
          startController();
          onRestart.run();
        }
      }
    }
  }

  /** Hands an event to the controller, folding it into the run digest. */
  void deliver(WorldInput controller, WorldEvent event) {
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
   * Work runs when it finishes, and its completion waits for the end of the tick it finishes in,
   * because production drains finished jobs there. A result therefore never reaches the controller
   * inside the call that submitted it, and work that reads controller state changed after it was
   * submitted is caught. Work that throws, or that the fault plan fails, reports its failure at the
   * same point. A job of a controller process that has since restarted reports nothing. The lane
   * orders only a production worker's queue, which this model does not have.
   */
  private final class SimOffload implements Offload {

    /**
     * A frame encode takes tens of microseconds, so between two steps and a couple of milliseconds.
     * Two steps at least, so work submitted while a tick closes finishes in the next one.
     */
    private static final int MIN_WORK_STEPS = 2;

    private static final int MAX_WORK_STEPS = 40;

    private final int process;

    private SimOffload(int process) {
      this.process = process;
    }

    @Override
    public <T> void submit(
        String name, Lane lane, Supplier<T> work, Consumer<T> completion, Runnable failure) {
      long ready = now + MIN_WORK_STEPS + offloadRng.nextInt(MAX_WORK_STEPS - MIN_WORK_STEPS + 1);
      long drainAt = (ready / STEPS_PER_TICK) * STEPS_PER_TICK + STEPS_PER_TICK - 1;
      metrics.count("offload.submitted", 1, "job", name);
      // Drawn only when failures are possible, so a plan without them replays unchanged.
      if (tick < settleFrom
          && offloadFailureRate > 0
          && offloadRng.nextDouble() < offloadFailureRate) {
        metrics.count("offload.failed", 1, "job", name);
        scheduleAt(drainAt, "offload-failed:" + name, () -> live(failure));
        return;
      }
      scheduleAt(
          drainAt,
          "offload:" + name,
          () ->
              live(
                  () -> {
                    T result;
                    try {
                      result = work.get();
                    } catch (RuntimeException e) {
                      metrics.count("offload.threw", 1, "job", name);
                      failure.run();
                      return;
                    }
                    completion.accept(result);
                  }));
    }

    private void live(Runnable action) {
      if (process == incarnation) {
        action.run();
      }
    }
  }
}
