package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Offload;
import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.nio.ByteBuffer;
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
import maestro.fbs.ChunkSnapshot;
import maestro.fbs.PathProbe;
import maestro.fbs.PathRequestBatch;
import maestro.fbs.RegionAssignment;
import maestro.fbs.RequestFrame;
import maestro.fbs.RequestMsg;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;

/**
 * Tick-driven deterministic simulator. Time is a step counter: {@link #STEPS_PER_TICK} steps make
 * one tick, and every delay is in steps. Events at the same step run in insertion order, so two
 * runs of one seed are identical.
 *
 * <p>The server works for the first part of each tick, then idles. The controller runs on the
 * server thread, so network and offload results reach it only when a tick's work starts and just
 * before it ends. A tick running long delays the next; a server far behind restarts its schedule,
 * as Minecraft does. The fleet keeps real time throughout.
 */
public final class Simulation {

  /** Intra-tick resolution. One step is 50 microseconds. */
  public static final int STEPS_PER_TICK = 1000;

  /** A Minecraft tick is 50ms. */
  static final long NANOS_PER_TICK = 50_000_000L;

  static final long NANOS_PER_STEP = NANOS_PER_TICK / STEPS_PER_TICK;

  /** A server that works the whole tick still leaves its last step for the fleet. */
  static final int MAX_BUSY_STEPS = STEPS_PER_TICK - 2;

  /** Most steps an offload job takes, such as encoding a batch handed over at a tick's end. */
  static final int MAX_OFFLOAD_STEPS = 40;

  /** A slow tick works between one and this many ticks' worth of steps. */
  static final int MAX_SLOW_TICKS = 3;

  /** Behind by more than 2 s, the server stops catching up, as Minecraft's does. */
  static final long MAX_CATCH_UP_STEPS = 40L * STEPS_PER_TICK;

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
  private final Rng serverRng;
  private final Rng offloadRng;
  private final Rng lateRng;
  private final Rng runRng;
  private final Rng controllerRng;
  private final PriorityQueue<Scheduled> queue = new PriorityQueue<>();

  /** Transport callbacks waiting for the controller thread, in arrival order. */
  private final List<Scheduled> inbox = new ArrayList<>();

  /** Offload work by the step it finishes, run at the first hand-over at or after it. */
  private final PriorityQueue<Scheduled> finishedWork = new PriorityQueue<>();

  /** Finished offload work that misses the first hand-over at or after it, by finishing step. */
  private final PriorityQueue<Scheduled> lateWork = new PriorityQueue<>();

  /** Late work that missed the last hand-over, in the order it finished. */
  private List<Scheduled> missedWork = new ArrayList<>();

  private final Map<NodeId, ModeledNode> nodes = new LinkedHashMap<>();
  private final Map<Long, List<FaultPlan.Fault>> faultsByTick = new LinkedHashMap<>();

  /** Highest world sequence the controller sent to any holder, per region stream. */
  private final Map<Stream, Long> sentByRegion = new LinkedHashMap<>();

  /** The region stream each node was last assigned, as the controller sent it. */
  private final Map<NodeId, Stream> sentStream = new LinkedHashMap<>();

  /** Highest world sequence each holder applied, per region stream. */
  private final Map<HeldStream, Long> appliedByHolder = new LinkedHashMap<>();

  /** What each holder of a region must hold at a sequence, from {@link #checkpoint}. */
  private final Map<Checkpoint, Long> checkpoints = new LinkedHashMap<>();

  private final List<String> streamViolations = new ArrayList<>();
  private final FailOpenCheck failOpen = new FailOpenCheck();
  private final SimMetrics metrics = new SimMetrics();
  private final SimTracing tracing = new SimTracing();
  private final SimEvents events = new SimEvents();
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

  /** Steps from the start of a tick to the end of the server's work in it. */
  private int busySteps = MAX_BUSY_STEPS;

  /** The chance that a tick's work runs past its time. */
  private double slowTickChance;

  /** The chance that offload work that sent hands its completion over a hand-over late. */
  private double lateCompletionChance;

  /** Steps the current tick's work takes at least, from a stall in the fault plan. */
  private long stallSteps;

  /** The step the next tick is due at. */
  private long due;

  /** The last step of the previous tick. */
  private long lastTickEnd = -1;

  public Simulation(long seed, FaultPlan faults) {
    this.rng = SeededRng.of(seed);
    this.serverRng = rng.derive("server");
    this.offloadRng = rng.derive("offload");
    this.lateRng = rng.derive("late-completions");
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

  /** The traces of every controller process so far. */
  public SimTracing tracing() {
    return tracing;
  }

  /** The events of every controller process so far. */
  public SimEvents events() {
    return events;
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

  /** From {@code tick} on, no encode fails, so every live node can catch up before the run ends. */
  public void settleFrom(long tick) {
    settleFrom = tick;
  }

  /** Steps of each tick the server works, between 1 and {@link #MAX_BUSY_STEPS}. It idles after. */
  public void serverBusySteps(int steps) {
    if (steps < 1 || steps > MAX_BUSY_STEPS) {
      throw new IllegalArgumentException("a tick's work takes 1 to " + MAX_BUSY_STEPS + " steps");
    }
    busySteps = steps;
  }

  /** The chance that a tick's work runs past its time, for up to {@link #MAX_SLOW_TICKS} ticks. */
  public void slowTicks(double chance) {
    if (!(chance >= 0 && chance <= 1)) {
      throw new IllegalArgumentException("a chance is in [0, 1]: " + chance);
    }
    slowTickChance = chance;
  }

  /**
   * The chance that offload work that sent through the transport hands its completion over one
   * hand-over late, as a worker queues it only after its sends, so the answers to them can be
   * handed over first.
   */
  public void lateCompletions(double chance) {
    if (!(chance >= 0 && chance <= 1)) {
      throw new IllegalArgumentException("a chance is in [0, 1]: " + chance);
    }
    lateCompletionChance = chance;
  }

  /** Queues a transport callback for the controller thread, run at the next hand-over. */
  void toController(String name, Runnable action) {
    inbox.add(new Scheduled(now, order++, name, action));
  }

  /** Runs when a fault restarts the controller, after the new process's environment exists. */
  public void onRestart(Runnable action) {
    onRestart = Objects.requireNonNull(action, "action");
  }

  /** A new controller process: new run id, transport and offload. In-flight work is lost. */
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
            tracing,
            events,
            run);
    failOpen.restarted(run, tick);
  }

  /** Records that a fleet node applied {@code sequence} of a region's world stream. */
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

  /** The node started or stopped being able to answer requests, whatever its links show. */
  void unresponsive(NodeId node, boolean active) {
    digest.mix(now).mix("unresponsive:" + node + ":" + active);
    failOpen.unresponsive(node, env.run(), tick, active);
  }

  /** The controller heard that the node's link is gone, which forgets the requests sent on it. */
  void linkLost(NodeId node) {
    failOpen.linkLost(node, env.run(), tick);
  }

  /**
   * Records each request or probe a request frame to {@code node} carries, for {@link
   * FailOpenCheck}.
   */
  void requestFrameSent(NodeId node, byte[] bytes) {
    RequestFrame frame = RequestFrame.getRootAsRequestFrame(ByteBuffer.wrap(bytes));
    if (frame.bodyType() == RequestMsg.PathProbe) {
      PathProbe probe = (PathProbe) frame.body(new PathProbe());
      failOpen.probeSent(node, frame.run(), probe.requestId(), tick);
      return;
    }
    if (frame.bodyType() != RequestMsg.PathRequestBatch) {
      return;
    }
    PathRequestBatch batch = (PathRequestBatch) frame.body(new PathRequestBatch());
    for (int i = 0; i < batch.requestsLength(); i++) {
      failOpen.requestSent(node, frame.run(), batch.requests(i).requestId(), tick);
    }
  }

  /** Every node that could not answer and was still sent more than probes, as of now. */
  public List<String> failOpenViolations() {
    return failOpen.violations(tick);
  }

  /** Stretches in which a node could not answer that were long enough to judge. */
  public long failOpenJudged() {
    return failOpen.judged();
  }

  /** Records that a node took a fresh assignment, from which its own copy starts over. */
  void assigned(ModeledNode node, long run, long region, long sequence) {
    appliedByHolder.put(new HeldStream(node.id(), run, region), sequence);
  }

  /**
   * Records a world frame the controller sent. A fresh assignment must never start behind a frame
   * already sent to a holder. Judged at send time, since holders' links deliver in any order.
   */
  void worldFrameSent(NodeId node, byte[] bytes) {
    WorldFrame frame = WorldFrame.getRootAsWorldFrame(ByteBuffer.wrap(bytes));
    long sequence;
    switch (frame.bodyType()) {
      case WorldMsg.RegionAssignment -> {
        RegionAssignment assignment = (RegionAssignment) frame.body(new RegionAssignment());
        Stream stream = new Stream(assignment.run(), assignment.regionId());
        sentStream.put(node, stream);
        long highest = sentByRegion.getOrDefault(stream, 0L);
        if (!assignment.resume() && assignment.sequence() < highest) {
          streamViolations.add(
              node
                  + " was assigned region "
                  + assignment.regionId()
                  + " at sequence "
                  + assignment.sequence()
                  + ", behind the sent "
                  + highest);
        }
        return;
      }
      case WorldMsg.ChunkSnapshot ->
          sequence = ((ChunkSnapshot) frame.body(new ChunkSnapshot())).sequence();
      case WorldMsg.WorldDelta -> sequence = ((WorldDelta) frame.body(new WorldDelta())).sequence();
      default -> {
        return;
      }
    }
    Stream stream = sentStream.get(node);
    if (stream != null) {
      sentByRegion.merge(stream, sequence, Math::max);
    }
  }

  /** The state a holder of the region must have once it applies {@code sequence}. */
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
   * Runs {@code ticks} ticks: faults, hand-over, tick open, workload, the server's work, hand-over,
   * tick close, then the fleet works to the tick's last step. {@code afterTick} runs last.
   */
  public void run(WorldInput controller, Workload workload, long ticks, LongConsumer afterTick) {
    if (ticks < 1) {
      throw new IllegalArgumentException("a run needs at least one tick: " + ticks);
    }
    Objects.requireNonNull(controller, "controller");
    Objects.requireNonNull(workload, "workload");
    for (long i = 0; i < ticks; i++) {
      tick++;
      now = Math.max(due, lastTickEnd + 1);
      applyFaults(tick);
      long tickClose = now + workSteps();
      due += STEPS_PER_TICK;
      if (tickClose - due > MAX_CATCH_UP_STEPS) {
        due = tickClose;
      }
      long tickEnd = Math.max(due, tickClose + 1) - 1;
      lastTickEnd = tickEnd;

      handOver();
      deliver(controller, new WorldEvent.TickBegan(tick));
      workload.tick(tick, event -> deliver(controller, event));

      drainUntil(tickClose);

      handOver();
      deliver(controller, new WorldEvent.TickEnded(tick));
      drainUntil(tickEnd);
      afterTick.accept(tick);
    }
  }

  /** Steps the current tick's work takes: the usual share, more if slow or stalled. */
  private long workSteps() {
    long work = busySteps;
    // Drawn only if slow ticks are possible, so other runs replay unchanged.
    if (slowTickChance > 0 && serverRng.nextDouble() < slowTickChance) {
      work = STEPS_PER_TICK + serverRng.nextInt((MAX_SLOW_TICKS - 1) * STEPS_PER_TICK + 1);
    }
    work = Math.max(work, stallSteps);
    stallSteps = 0;
    return work;
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
        case FaultPlan.Blackholed blackholed ->
            transport.blackhole(blackholed.node(), blackholed.active());
        case FaultPlan.ServerStalled stalled -> stallSteps = Math.max(stallSteps, stalled.steps());
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

  /**
   * Runs queued transport callbacks in arrival order, then offload work that missed the last
   * hand-over, then offload work finished by now. Late work finished by now misses this one.
   */
  private void handOver() {
    for (int i = 0; i < inbox.size(); i++) {
      Scheduled next = inbox.get(i);
      digest.mix(now).mix(next.name());
      next.action().run();
    }
    inbox.clear();
    List<Scheduled> missed = missedWork;
    missedWork = new ArrayList<>();
    for (Scheduled next : missed) {
      digest.mix(now).mix(next.name());
      next.action().run();
    }
    while (!lateWork.isEmpty() && lateWork.peek().time() <= now) {
      missedWork.add(lateWork.poll());
    }
    while (!finishedWork.isEmpty() && finishedWork.peek().time() <= now) {
      Scheduled next = finishedWork.poll();
      digest.mix(now).mix(next.name());
      next.action().run();
    }
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
   * A job's work runs on its worker when it finishes, between hand-overs, so a job that sends does
   * so then. Its completion runs at the next hand-over, so a result never reaches the controller
   * inside the submitting call, or, by {@link #lateCompletions}, one later if the job sent.
   * Failures report at the same point. Jobs of a restarted process neither run nor report. The lane
   * is ignored.
   */
  private final class SimOffload implements Offload {

    /** Encodes take tens of microseconds. At least one step, so work never finishes at submit. */
    private static final int MIN_WORK_STEPS = 2;

    private final int process;

    private SimOffload(int process) {
      this.process = process;
    }

    @Override
    public <T> void submit(
        String name, Lane lane, Supplier<T> work, Consumer<T> completion, Runnable failure) {
      long ready =
          now + MIN_WORK_STEPS + offloadRng.nextInt(MAX_OFFLOAD_STEPS - MIN_WORK_STEPS + 1);
      metrics.count("offload.submitted", 1, "job", name);
      // Drawn only if failures are possible, so other runs replay unchanged.
      if (tick < settleFrom
          && offloadFailureRate > 0
          && offloadRng.nextDouble() < offloadFailureRate) {
        metrics.count("offload.failed", 1, "job", name);
        finishedWork.add(
            new Scheduled(ready, order++, "offload-failed:" + name, () -> live(failure)));
        return;
      }
      queue.add(
          new Scheduled(
              ready,
              order++,
              "offload-run:" + name,
              () -> {
                if (process != incarnation) {
                  return;
                }
                long sentBefore = transport.sentToNodes();
                T result;
                try {
                  result = work.get();
                } catch (RuntimeException e) {
                  metrics.count("offload.threw", 1, "job", name);
                  finishedWork.add(
                      new Scheduled(now, order++, "offload-threw:" + name, () -> live(failure)));
                  return;
                }
                Scheduled done =
                    new Scheduled(
                        now,
                        order++,
                        "offload:" + name,
                        () -> live(() -> completion.accept(result)));
                // Drawn only if late completions are possible, so other runs replay unchanged.
                if (transport.sentToNodes() != sentBefore
                    && lateCompletionChance > 0
                    && lateRng.nextDouble() < lateCompletionChance) {
                  metrics.count("offload.late", 1, "job", name);
                  lateWork.add(done);
                } else {
                  finishedWork.add(done);
                }
              }));
    }

    private void live(Runnable action) {
      if (process == incarnation) {
        action.run();
      }
    }
  }
}
