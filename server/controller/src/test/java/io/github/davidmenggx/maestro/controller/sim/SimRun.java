package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.core.Controller;
import io.github.davidmenggx.maestro.controller.core.NodeHealth;
import io.github.davidmenggx.maestro.controller.core.NodeLinks;
import io.github.davidmenggx.maestro.controller.core.Placement;
import io.github.davidmenggx.maestro.controller.core.Priority;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * One assembled run: world, fleet, controller and workload, all seeded from one number. {@code
 * -Dmaestro.dst.seed} and {@code -Dmaestro.dst.runs} set the sweep.
 */
public final class SimRun {

  /** Quiet ticks at the end, so in-flight requests resolve before invariants are checked. */
  public static final long DRAIN_TICKS = 25;

  /** Extra ticks a node may need to catch up after a reconnect and full send. */
  static final long SETTLE_MARGIN_TICKS = 5;

  /**
   * Ticks past its grace a region whose holders all died may wait for a spare that is up: the
   * controller hears of a death at the next hand-over and judges at the next tick's start.
   */
  static final long STRANDED_MARGIN_TICKS = 2;

  /**
   * Ticks a controller may take to find that a holder still linked cannot answer: a second with no
   * answer, then a probe's time. A request path that goes quiet sooner shows it sooner.
   */
  static final long DETECT_TICKS = NodeHealth.CHECK_TICKS + NodeHealth.PROBE_TICKS + 2;

  /**
   * Times a run is extended to settle. A region change during an extension needs another, and the
   * changes stop soon after the workload does.
   */
  static final int MAX_SETTLE_ROUNDS = 4;

  /** The SLO: p99 latency must be below this many ticks. */
  public static final double TICK_BUDGET = Priority.SLO_TICKS;

  /** Sweep size when nothing sets {@code maestro.dst.runs}. */
  private static final int DEFAULT_RUNS = 100;

  /** Chance per tick of a checkpoint. A few per run, since each reads every region. */
  private static final double CHECKPOINT_CHANCE = 1.0 / 32;

  /** Length of a fuzzed run. Before {@link #DRAIN_TICKS}, it must leave room for a fault. */
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

  /** Every node of the run, in fleet order. */
  public final List<NodeId> fleet;

  public final RecordingOutput out;
  public final Workload workload;

  /** The current controller process. A restart fault replaces it. */
  public Controller controller;

  /** Requests starting in each node's region, per tick. Each holder is charged all of them. */
  private final Map<NodeId, Map<Long, Integer>> offered = new LinkedHashMap<>();

  /** What the self-check of each controller process a restart replaced found. */
  private final List<String> retiredViolations = new ArrayList<>();

  /** The tick the workload stops changing the world, or {@link #ticks} if it never does. */
  private final long quietAfter;

  private long ticksRun;

  /** The controller's grace if it differs from the scenario's, else 0. */
  private final int controllerGrace;

  /** The capacity the controller assumes if it differs from the scenario's, else 0. */
  private final int controllerCapacity;

  /** The last tick a controller process changed its regions, which resends their areas. */
  private long lastRegionChange = -1;

  /** The controller process and region version the last tick ended with. */
  private Controller seenController;

  private int seenRegions;

  /**
   * Per region, the first tick since its holders last changed that they all could not serve while a
   * spare was up.
   */
  private final Map<Integer, Long> strandedSince = new LinkedHashMap<>();

  /** Each region's holders as the last tick ended. */
  private final Map<Integer, List<NodeId>> seenHolders = new LinkedHashMap<>();

  /** The last tick each node could serve. */
  private final Map<NodeId, Long> lastServed = new LinkedHashMap<>();

  /**
   * Regions left to holders that could not serve past their grace while a spare was up, and spares
   * handed a region long after they stopped serving, as they happened.
   */
  private final List<String> strandedRegions = new ArrayList<>();

  private final Rng checkpointRng;

  /** {@code quietAfter} is the tick the workload stops changing the world. */
  private SimRun(
      long seed,
      FaultPlan faults,
      long ticks,
      long quietAfter,
      Topology topology,
      Scenario scenario,
      int controllerGrace,
      int controllerCapacity,
      BiFunction<SimWorld, Rng, Workload> workloadFactory) {
    this.seed = seed;
    this.controllerGrace = controllerGrace;
    this.controllerCapacity = controllerCapacity;
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
    this.fleet = List.copyOf(nodes);
    this.world =
        new SimWorld(
            topology, scenario.counts().get(Count.MOB_CLASSES), sim.rng().derive("terrain"));
    this.out = new RecordingOutput(sim, world, this::regions);
    this.controller = newController();
    this.workload = workloadFactory.apply(world, sim.rng().derive("workload"));
    this.checkpointRng = sim.rng().derive("checkpoints");
    sim.onRestart(this::restartController);
  }

  private Controller newController() {
    Placement.Policy policy = scenario.policy();
    if (controllerGrace > 0) {
      policy = new Placement.Policy(controllerGrace, policy.hotTicks(), policy.coldTicks());
    }
    return new Controller(
        sim.env(),
        world,
        out,
        fleet,
        new Controller.Settings(
            topology.layout(),
            topology.spares(),
            controllerCapacity > 0 ? controllerCapacity : scenario.assumedCapacity(),
            scenario.dispatch(),
            policy));
  }

  /** The regions and their holders as the current controller has them now. */
  public Regions regions() {
    return controller.regions();
  }

  /** The controller restarted. Its requests are gone, and the new one learns the world afresh. */
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
    return withControllerGrace(seed, faults, ticks, topology, scenario, 0);
  }

  /**
   * As {@link #of}, with a controller that waits {@code graceTicks} for a down holder instead of
   * the scenario's grace, which the invariants still expect: a controller that waits too long.
   */
  static SimRun withControllerGrace(
      long seed,
      FaultPlan faults,
      long ticks,
      Topology topology,
      Scenario scenario,
      int graceTicks) {
    return synthetic(seed, faults, ticks, topology, scenario, graceTicks, 0);
  }

  /**
   * As {@link #of}, with a controller that assumes {@code capacity} instead of the scenario's
   * capacity, which the invariants still expect: a controller that admits too much.
   */
  static SimRun withControllerCapacity(
      long seed, FaultPlan faults, long ticks, Topology topology, Scenario scenario, int capacity) {
    return synthetic(seed, faults, ticks, topology, scenario, 0, capacity);
  }

  private static SimRun synthetic(
      long seed,
      FaultPlan faults,
      long ticks,
      Topology topology,
      Scenario scenario,
      int graceTicks,
      int capacity) {
    return new SimRun(
        seed,
        faults,
        ticks,
        ticks - DRAIN_TICKS,
        topology,
        scenario,
        graceTicks,
        capacity,
        (world, rng) -> new SyntheticWorkload(world, rng, ticks - DRAIN_TICKS, scenario));
  }

  /** A run whose geometry, load, length, faults and event order are all drawn from the seed. */
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
    return scripted(seed, faults, ticks, Topology.DEFAULT, scenario, events);
  }

  /** As above, over {@code topology}. */
  public static SimRun scripted(
      long seed,
      FaultPlan faults,
      long ticks,
      Topology topology,
      Scenario scenario,
      List<WorldEvent> events) {
    return new SimRun(
        seed,
        faults,
        ticks,
        ticks,
        topology,
        scenario,
        0,
        0,
        (world, rng) -> new ScriptedWorkload(world, events));
  }

  public SimRun go() {
    // Ledgered first, since a drop can come back within the same call.
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
    for (int round = 0; round < MAX_SETTLE_ROUNDS; round++) {
      long more = settledBy() - ticksRun;
      if (more <= 0) {
        break;
      }
      sim.run(input, workload, more, this::afterTick);
      ticksRun += more;
    }
    return this;
  }

  /**
   * The tick by which every live node of a run with a quiet tail has caught up. After the last
   * fault, a dead holder's region waits out its grace and a node redials; after the last region
   * change its new holders start over. Then every loaded chunk is read again at the read budget,
   * plus a margin. Scripted runs end where their script says.
   */
  private long settledBy() {
    if (quietAfter >= ticks) {
      return ticks;
    }
    long from = faults.offloadFailureRate() > 0 ? quietAfter : 0;
    for (FaultPlan.Fault fault : faults.faults()) {
      from = Math.max(from, fault.tick());
    }
    long recovered = from + scenario.policy().graceTicks() + NodeLinks.RECONNECT_TICKS;
    return Math.max(recovered, lastRegionChange) + out.readTicks() + SETTLE_MARGIN_TICKS;
  }

  private void offer(WorldEvent.PathRequested requested) {
    Region region = regions().regionAt(requested.start().x(), requested.start().z());
    if (region == null) {
      return;
    }
    for (NodeId holder : regions().holders(region.id())) {
      offered
          .computeIfAbsent(holder, node -> new LinkedHashMap<>())
          .merge(requested.tick(), 1, Integer::sum);
    }
  }

  /** Now and then, records what each region's holder must hold at the latest sequence. */
  private void afterTick(long tick) {
    out.tickEnded();
    if (controller != seenController) {
      // A new process counts its holders' grace afresh.
      strandedSince.clear();
      seenHolders.clear();
    } else if (controller.regionsVersion() != seenRegions) {
      lastRegionChange = tick;
    }
    seenController = controller;
    seenRegions = controller.regionsVersion();
    checkStranded(tick);
    if (checkpointRng.nextDouble() >= CHECKPOINT_CHANCE) {
      return;
    }
    for (Region region : regions().regions()) {
      List<NodeId> holders = regions().holders(region.id());
      if (!holders.isEmpty() && controller.regionSettled(region.id())) {
        sim.checkpoint(
            sim.env().run(),
            region.id(),
            controller.heldSequence(holders.get(0)),
            Invariants.freshDigest(this, region.id()));
      }
    }
  }

  public List<String> violations() {
    return Invariants.check(this);
  }

  /**
   * A region whose holders all cannot serve, dead or unable to answer, goes to a spare that is up
   * within its grace, once the controller could have found out. A spare it goes to may have stopped
   * serving too recently for the controller to know, which then takes a grace of its own, but no
   * earlier. Judged as the run goes, so a region stranded for a while that heals later is caught
   * too.
   */
  private void checkStranded(long tick) {
    boolean spareUp = false;
    for (NodeId node : fleet) {
      if (serves(node)) {
        lastServed.put(node, tick);
      }
      spareUp |=
          regions().regionOf(node) == Regions.NONE && serves(node) && controller.serves(node);
    }
    Set<NodeId> wereSpares = new LinkedHashSet<>(fleet);
    for (List<NodeId> holders : seenHolders.values()) {
      wereSpares.removeAll(holders);
    }
    Map<Integer, List<NodeId>> holdersNow = new LinkedHashMap<>();
    for (Region region : regions().regions()) {
      List<NodeId> holders = List.copyOf(regions().holders(region.id()));
      holdersNow.put(region.id(), holders);
      if (!holders.equals(seenHolders.get(region.id()))) {
        strandedSince.remove(region.id());
        for (NodeId holder : holders) {
          long silent = tick - lastServed.getOrDefault(holder, Long.MIN_VALUE / 2);
          if (wereSpares.contains(holder) && !seenHolders.isEmpty() && silent > DETECT_TICKS) {
            strandedRegions.add(
                "region "
                    + region.id()
                    + " was given at tick "
                    + tick
                    + " to "
                    + holder
                    + ", which had not served for "
                    + silent
                    + " ticks");
          }
        }
      }
      boolean anyServes = false;
      for (NodeId holder : holders) {
        anyServes |= serves(holder);
      }
      if (anyServes || !spareUp) {
        strandedSince.remove(region.id());
        continue;
      }
      long since = strandedSince.computeIfAbsent(region.id(), unseen -> tick);
      if (tick - since
          == DETECT_TICKS + scenario.policy().graceTicks() + STRANDED_MARGIN_TICKS + 1) {
        strandedRegions.add(
            "region "
                + region.id()
                + " was left to holders "
                + holders
                + " that could not serve from tick "
                + since
                + " to "
                + tick
                + " while a spare was up");
      }
    }
    strandedSince.keySet().retainAll(holdersNow.keySet());
    seenHolders.clear();
    seenHolders.putAll(holdersNow);
  }

  /** Whether the node is alive and able to answer requests, whatever the controller knows. */
  boolean serves(NodeId node) {
    return sim.transport().alive(node) && !sim.transport().unresponsive(node);
  }

  /** Regions left to holders that could not serve past their grace while a spare was up. */
  public List<String> strandedRegions() {
    return List.copyOf(strandedRegions);
  }

  /** Whether the run has a quiet tail long enough for every live node to settle. */
  public boolean settles() {
    return quietAfter < ticks;
  }

  /**
   * Whether the load fits the fleet, judged from the offered load, not the controller's actions: no
   * fault could delay or lose a request, and each tick's requests to a node, at worst cost and
   * latency, fit one tick.
   */
  public boolean fitsCapacity() {
    if (!faults.clean()) {
      return false;
    }
    for (ModeledNode node : sim.nodes()) {
      for (int requests : offered.getOrDefault(node.id(), Map.of()).values()) {
        if (!fitsOneTick(node.service(), requests)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Whether admission alone keeps what it admits within the SLO, whatever the load: no fault could
   * delay or lose a request, and each node serves the queue admission lets it hold, the SLO's share
   * of the assumed capacity, which the controller never takes a node to exceed. At worst cost the
   * queue is served before the next batch can arrive, so no backlog builds, and its answers come
   * back by the hand-over that ends the tick after the batch left, at the tick's start. A server
   * that works so much of each tick that a batch can still be encoding then sends it a hand-over
   * later, and is not judged.
   */
  public boolean admissionFits() {
    if (!faults.clean()
        || scenario.serverBusySteps() + Simulation.MAX_OFFLOAD_STEPS > Simulation.STEPS_PER_TICK) {
      return false;
    }
    int queue = (int) Math.ceil(Priority.QUEUE_TICKS * scenario.assumedCapacity());
    long spread = faults.maxLatencySteps() - faults.minLatencySteps();
    for (ModeledNode node : sim.nodes()) {
      ModeledNode.Service service = node.service();
      if (service.workers() == 0) {
        return false;
      }
      long serve =
          (long) ((queue + service.workers() - 1) / service.workers()) * service.maxCostSteps();
      if (serve + spread > Simulation.STEPS_PER_TICK
          || 2L * faults.maxLatencySteps() + serve
              > Simulation.STEPS_PER_TICK + scenario.serverBusySteps() - 1) {
        return false;
      }
    }
    return true;
  }

  /** Whether {@code requests} arriving at once are answered within a tick of arriving. */
  private boolean fitsOneTick(ModeledNode.Service service, int requests) {
    if (service.workers() == 0) {
      return false;
    }
    long rounds = (requests + service.workers() - 1) / service.workers();
    return 2L * faults.maxLatencySteps() + rounds * service.maxCostSteps()
        <= Simulation.STEPS_PER_TICK - 1;
  }

  /** Whether every tick had its own time. A server behind on ticks makes the latency SLO moot. */
  public boolean serverKeptUp() {
    return scenario.chances().get(Scenario.Chance.SLOW_TICK) == 0
        && faults.faults().stream().noneMatch(fault -> fault instanceof FaultPlan.ServerStalled);
  }

  /**
   * Whether a node can be judged by its answers: it answers in time whatever is sent to it, as the
   * load fits the fleet or admission keeps what it sends within the SLO.
   */
  public boolean judgedHealthy() {
    return fitsCapacity() || admissionFits();
  }

  /** Whether the latency SLO applies to the requests the run admits: also, the server kept up. */
  public boolean heldToTheSlo() {
    return judgedHealthy() && serverKeptUp();
  }

  /**
   * Whether the SLO applies to every request, a shed one counting as late: the load fits the fleet,
   * the server kept up, and each tick's requests to a node fit what it takes before the least
   * important requests are shed. That is judged at the least capacity the controller can take the
   * node to have: the assumed one, or the node's rate at worst cost, which its measured rate never
   * falls below in a run with no faults.
   */
  public boolean heldWithoutShedding() {
    if (!fitsCapacity() || !serverKeptUp()) {
      return false;
    }
    for (ModeledNode node : sim.nodes()) {
      ModeledNode.Service service = node.service();
      double worst =
          (double) service.workers() * Simulation.STEPS_PER_TICK / service.maxCostSteps();
      double ahead =
          Priority.allowedAhead(Priority.MIN_SHARE, Math.min(scenario.assumedCapacity(), worst));
      for (int requests : offered.getOrDefault(node.id(), Map.of()).values()) {
        if (requests - 1 > ahead) {
          return false;
        }
      }
    }
    return true;
  }

  /** The most chunks loaded at once, a lower bound on the frames of one full send. */
  public int chunkCount() {
    return world.mostLoaded();
  }

  /** Transport counters are per node and per channel, so a fleet total must be summed. */
  public long fleetCounter(String name, Channel channel) {
    long total = 0;
    for (NodeId node : fleet) {
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
