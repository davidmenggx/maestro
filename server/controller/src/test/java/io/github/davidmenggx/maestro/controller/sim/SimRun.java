package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.core.Controller;
import io.github.davidmenggx.maestro.controller.core.NodeLinks;
import io.github.davidmenggx.maestro.controller.core.WorldStreams;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * One assembled run: world, fleet, controller and workload, all seeded from a single number. {@code
 * -Dmaestro.dst.seed} and {@code -Dmaestro.dst.runs} set the sweep, so a failing seed from CI can
 * be replayed with `make fuzz SEED=...`.
 */
public final class SimRun {

  /**
   * Quiet ticks at the end, so every in-flight request resolves before the invariants are checked.
   */
  public static final long DRAIN_TICKS = 25;

  /**
   * Ticks past a reconnect and a full send of every loaded chunk that a node may still need to
   * catch up: its greeting, the last encodes, and their delivery.
   */
  static final long SETTLE_MARGIN_TICKS = 5;

  /**
   * The SLO: p99 latency must be below this many ticks. Tighter than the admission bound in
   * `RequestTracker`, which only keeps a saturated fleet from falling further behind.
   */
  public static final double TICK_BUDGET = 3.0;

  /** Sweep size when nothing sets {@code maestro.dst.runs}. */
  private static final int DEFAULT_RUNS = 100;

  /**
   * Chance per tick of a checkpoint: the state each settled region's holder must have once it
   * reaches the stream's current sequence. A few per run, since each reads every region.
   */
  private static final double CHECKPOINT_CHANCE = 1.0 / 32;

  /**
   * Length of a fuzzed run. The active part, before {@link #DRAIN_TICKS}, must leave room for a
   * fault to start and end, or the shortest runs would have no faults.
   */
  static final long MIN_FUZZED_TICKS = 60;

  static final long MAX_FUZZED_TICKS = 320;

  public final long seed;

  /** Ticks the run was asked for. It runs longer if its nodes need longer to settle. */
  public final long ticks;

  public final FaultPlan faults;
  public final Topology topology;
  public final Scenario scenario;
  public final Simulation sim;
  public final SimWorld world;
  public final RegionMap regions;
  public final RecordingOutput out;
  public final Workload workload;

  /** The current controller process. A restart fault replaces it. */
  public Controller controller;

  /**
   * Requests whose start is in each node's region, per tick. What the fleet was asked to do. A
   * region's holders split its requests, but which holder serves one depends on what the controller
   * knows, so each holder is charged them all.
   */
  private final Map<NodeId, Map<Long, Integer>> offered = new LinkedHashMap<>();

  /** What the self-check of each controller process a restart replaced found. */
  private final List<String> retiredViolations = new ArrayList<>();

  /** The tick the workload stops changing the world, or {@link #ticks} if it never does. */
  private final long quietAfter;

  private long ticksRun;

  private final Rng checkpointRng;

  /**
   * {@code quietAfter} is the tick the workload stops changing the world, from which the run
   * settles.
   */
  private SimRun(
      long seed,
      FaultPlan faults,
      long ticks,
      long quietAfter,
      Topology topology,
      Scenario scenario,
      BiFunction<SimWorld, Rng, Workload> workloadFactory) {
    this.seed = seed;
    this.ticks = ticks;
    this.quietAfter = quietAfter;
    this.faults = faults;
    this.topology = topology;
    this.scenario = scenario;
    this.sim = new Simulation(seed, faults);
    sim.settleFrom(quietAfter);
    sim.serverBusySteps(scenario.serverBusySteps());
    sim.slowTicks(scenario.chances().get(Scenario.Chance.SLOW_TICK));

    List<NodeId> nodes = new ArrayList<>();
    for (int i = 0; i < topology.nodeCount(); i++) {
      NodeId id = NodeId.of(i);
      nodes.add(id);
      sim.addNode(id, scenario.serviceOf(i));
    }

    this.regions = topology.regions(nodes);
    this.world =
        new SimWorld(
            topology, scenario.counts().get(Count.MOB_CLASSES), sim.rng().derive("terrain"));
    this.out = new RecordingOutput(sim, world, regions);
    this.controller = newController();
    this.workload = workloadFactory.apply(world, sim.rng().derive("workload"));
    this.checkpointRng = sim.rng().derive("checkpoints");
    sim.onRestart(this::restartController);
  }

  private Controller newController() {
    return new Controller(sim.env(), world, out, regions, scenario.assumedCapacity());
  }

  /**
   * The controller process restarted. Every request it held is gone with it, and the new process
   * learns the world as a starting server would: every loaded chunk, mob and player.
   */
  private void restartController() {
    out.restarted();
    for (String violation : controller.selfCheck()) {
      retiredViolations.add("controller of run " + controller.run() + ": " + violation);
    }
    controller = newController();
    workload.replayState(sim.env().clock().tick(), event -> sim.deliver(controller, event));
  }

  /** Violations the self-checks of the controller processes a restart replaced found. */
  public List<String> retiredViolations() {
    return List.copyOf(retiredViolations);
  }

  public static SimRun of(long seed, FaultPlan faults, long ticks) {
    return of(seed, faults, ticks, Scenario.DEFAULT);
  }

  public static SimRun of(long seed, FaultPlan faults, long ticks, Scenario scenario) {
    return of(seed, faults, ticks, Topology.DEFAULT, scenario);
  }

  public static SimRun of(
      long seed, FaultPlan faults, long ticks, Topology topology, Scenario scenario) {
    return new SimRun(
        seed,
        faults,
        ticks,
        ticks - DRAIN_TICKS,
        topology,
        scenario,
        (world, rng) -> new SyntheticWorkload(world, rng, ticks - DRAIN_TICKS, scenario));
  }

  /**
   * A run whose geometry, load, length and faults are all drawn from the seed, along with its event
   * ordering, so a sweep covers the ranges in {@link Topology}, {@link Scenario} and {@link
   * FaultPlan}, not one fixed setting.
   */
  public static SimRun fuzzed(long seed) {
    Rng rng = SeededRng.of(seed).derive("fuzz");
    long ticks = MIN_FUZZED_TICKS + rng.nextLong(MAX_FUZZED_TICKS - MIN_FUZZED_TICKS + 1);
    Topology topology = Topology.sample(rng);
    Scenario scenario = Scenario.sample(rng);
    FaultPlan faults =
        FaultPlan.sample(
            rng,
            topology.nodeCount(),
            ticks - DRAIN_TICKS,
            scenario.maxCostSteps(topology.nodeCount()));
    return of(seed, faults, ticks, topology, scenario);
  }

  /** A run driven by a fixed event list instead of a generator, for focused tests. */
  public static SimRun scripted(long seed, FaultPlan faults, long ticks, List<WorldEvent> events) {
    return scripted(seed, faults, ticks, Scenario.DEFAULT, events);
  }

  /** As above, on a fleet sized by {@code scenario}. Its event rates are not used. */
  public static SimRun scripted(
      long seed, FaultPlan faults, long ticks, Scenario scenario, List<WorldEvent> events) {
    return new SimRun(
        seed,
        faults,
        ticks,
        ticks,
        Topology.DEFAULT,
        scenario,
        (world, rng) -> new ScriptedWorkload(world, events));
  }

  public SimRun go() {
    // A request enters the ledger before the controller sees it, because a drop can come
    // back within the same call.
    WorldInput input =
        event -> {
          if (event instanceof WorldEvent.PathRequested requested) {
            offer(requested);
            out.issued(requested);
            controller.accept(event);
            out.handled(requested);
          } else {
            controller.accept(event);
          }
        };
    sim.run(input, workload, ticks, this::afterTick);
    ticksRun = ticks;
    long more = settledBy() - ticks;
    if (more > 0) {
      sim.run(input, workload, more, this::afterTick);
      ticksRun += more;
    }
    return this;
  }

  /**
   * The tick by which every live node of a run with a quiet tail has caught up: after the last
   * fault, or the last encode that could fail, the next dial reconnects a node, every loaded chunk
   * is read again at the read budget, and the margin covers the rest. Scripted runs end where their
   * script says.
   */
  private long settledBy() {
    if (quietAfter >= ticks) {
      return ticks;
    }
    long from = faults.offloadFailureRate() > 0 ? quietAfter : 0;
    for (FaultPlan.Fault fault : faults.faults()) {
      from = Math.max(from, fault.tick());
    }
    long reads =
        (world.mostLoaded() + WorldStreams.MAX_CHUNK_READS_PER_TICK - 1)
            / WorldStreams.MAX_CHUNK_READS_PER_TICK;
    return from + NodeLinks.RECONNECT_TICKS + reads + SETTLE_MARGIN_TICKS;
  }

  private void offer(WorldEvent.PathRequested requested) {
    Region region = regions.regionAt(requested.start().x(), requested.start().z());
    if (region == null) {
      return;
    }
    for (NodeId holder : regions.holders(region.id())) {
      offered
          .computeIfAbsent(holder, node -> new LinkedHashMap<>())
          .merge(requested.tick(), 1, Integer::sum);
    }
  }

  /**
   * Now and then, records what each region's holder must hold once it reaches the sequence the
   * controller produced last, for every region whose changes are all in frames.
   */
  private void afterTick(long tick) {
    if (checkpointRng.nextDouble() >= CHECKPOINT_CHANCE) {
      return;
    }
    for (Region region : regions.regions()) {
      if (controller.regionSettled(region.id())) {
        sim.checkpoint(
            sim.env().run(),
            region.id(),
            controller.heldSequence(regions.holders(region.id()).get(0)),
            Invariants.freshDigest(this, region.id()));
      }
    }
  }

  public List<String> violations() {
    return Invariants.check(this);
  }

  /**
   * Whether the run's load fits its fleet, decided from what went in, not from what the controller
   * did: nothing in the fault plan could delay or lose a request, and every node can serve what it
   * was offered in time. A tick's batch leaves when the next tick starts, or at the latest when
   * that tick's work ends, and its answers must reach the controller a tick after that, so each
   * tick's requests to a node, served by its workers at their highest cost with the slowest latency
   * each way, must fit one tick.
   */
  public boolean fitsCapacity() {
    if (!faults.clean()) {
      return false;
    }
    for (ModeledNode node : sim.nodes()) {
      ModeledNode.Service service = node.service();
      for (int requests : offered.getOrDefault(node.id(), Map.of()).values()) {
        long rounds = (requests + service.workers() - 1) / service.workers();
        if (2L * faults.maxLatencySteps() + rounds * service.maxCostSteps()
            > Simulation.STEPS_PER_TICK - 1) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Whether every tick of the run had its own time. A server that falls behind runs the ticks it
   * owes back to back, so an answer that takes one tick's time arrives ticks later by the count,
   * and the latency SLO, which counts ticks, cannot apply.
   */
  public boolean serverKeptUp() {
    return scenario.chances().get(Scenario.Chance.SLOW_TICK) == 0
        && faults.faults().stream().noneMatch(fault -> fault instanceof FaultPlan.ServerStalled);
  }

  /** Whether the latency SLO applies to the run: it fits the fleet, and the server kept up. */
  public boolean heldToTheSlo() {
    return fitsCapacity() && serverKeptUp();
  }

  /** The most chunks loaded at once, which is at least the frames of one full send of them. */
  public int chunkCount() {
    return world.mostLoaded();
  }

  /** Transport counters are per node and per channel, so a fleet total must be summed. */
  public long fleetCounter(String name, Channel channel) {
    long total = 0;
    for (NodeId node : regions.nodes()) {
      total += sim.metrics().counter(name, "node", node.toString(), "channel", channel.name());
    }
    return total;
  }

  /** Seed and outcome, enough for a failure message to reproduce the run. */
  public String describe() {
    return "seed="
        + seed
        + " ticks="
        + ticksRun
        + " issued="
        + out.issuedCount()
        + " controllers="
        + sim.incarnations()
        + " answered="
        + out.answered()
        + " drops="
        + out.drops()
        + " topology="
        + topology
        + " scenario="
        + scenario
        + " digest="
        + Long.toHexString(sim.digest().value());
  }

  public static long baseSeed() {
    return Long.getLong("maestro.dst.seed", 1L);
  }

  public static int runCount() {
    return Integer.getInteger("maestro.dst.runs", DEFAULT_RUNS);
  }
}
