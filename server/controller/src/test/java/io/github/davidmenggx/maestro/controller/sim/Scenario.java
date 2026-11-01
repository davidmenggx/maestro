package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.core.Dispatch;
import io.github.davidmenggx.maestro.controller.core.Placement;
import io.github.davidmenggx.maestro.controller.env.Rng;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The load a run puts on the fleet, and how much the world changes during it. See {@link FaultPlan}
 * for network faults and {@link Topology} for the world's shape.
 *
 * <p>Each setting is declared once, with a minimum and a maximum that {@link #sample} draws up to.
 * The sampler, validation and coverage assertions all read this table. A focused test may exceed
 * the maximum.
 */
public record Scenario(Map<Count, Integer> counts, Map<Chance, Double> chances) {

  /** A whole-number setting. */
  public enum Count {
    /** Requests one worker serves per tick on average. A slow node costs four times as much. */
    WORKER_CAPACITY(1, 12),
    /** Searches a node runs at once. */
    WORKERS(1, 4),
    /** How far one request's cost may stray from the mean, as a percentage of it. */
    COST_SPREAD_PERCENT(0, 50),
    SLOW_NODES(0, 2),
    /** The capacity the controller assumes, as a percentage of a normal node's. */
    CAPACITY_ESTIMATE_PERCENT(50, 200),
    MOBS(0, 48),
    MOB_CLASSES(1, 4),
    BLOCK_CHANGES_PER_TICK(0, 6),
    PLAYERS(0, 3),
    /**
     * Chunks loaded around a player in every direction at the start. At the most, one player loads
     * more chunks than a region's area holds, as on a server with players about.
     */
    VIEW_RADIUS(3, 12),
    /** How much of a tick the server works, as a percentage. It idles for the rest. */
    SERVER_BUSY_PERCENT(20, 100),
    /** Ticks a holder may be down before a spare takes its region. */
    GRACE_TICKS(10, 100),
    /** Windows of shedding before a region takes a spare. */
    HOT_WINDOWS(1, 10),
    /** Windows of low load before a replica returns to the spares. */
    COLD_WINDOWS(1, 30),
    /** 1 to send each request as soon as it is encoded, 0 to batch a tick's requests. */
    STREAM(0, 1);

    private final int floor;
    private final int ceiling;

    Count(int floor, int ceiling) {
      this.floor = floor;
      this.ceiling = ceiling;
    }

    public int floor() {
      return floor;
    }

    public int ceiling() {
      return ceiling;
    }
  }

  /** The probability that the workload emits the event, per mob or per tick. */
  public enum Chance {
    REQUEST(0.3),
    CANCEL(0.1),
    MOB_CHURN(0.15),
    CHUNK_CHURN(0.12),
    PLAYER_CHURN(0.1),
    STRAY_REQUEST(0.1),
    /** A mob that asks again in the same tick, as when a goal changes mid-tick. */
    REPEAT_REQUEST(0.1),
    /** A tick whose work runs past its time, so the server falls behind and catches up. */
    SLOW_TICK(0.2),
    /**
     * A chunk about to leave cannot be read for a few ticks before it is reported gone, or before
     * it stays after all. Per chunk that leaves.
     */
    LEAVE_UNREADABLE(1.0),
    /** A request whose mob has a target, which sheds it last. */
    HUNTING(1.0);

    private final double ceiling;

    Chance(double ceiling) {
      this.ceiling = ceiling;
    }

    public double ceiling() {
      return ceiling;
    }
  }

  public Scenario {
    counts = complete(counts, Count.class);
    chances = complete(chances, Chance.class);
    for (Count setting : Count.values()) {
      int value = counts.get(setting);
      if (value < setting.floor()) {
        throw new IllegalArgumentException(
            setting + " must be at least " + setting.floor() + ": " + value);
      }
    }
    for (Chance setting : Chance.values()) {
      double value = chances.get(setting);
      if (Double.isNaN(value) || value < 0 || value > 1) {
        throw new IllegalArgumentException(setting + " must be in [0, 1]: " + value);
      }
    }
  }

  /** A fleet with spare capacity and a world whose chunks stay loaded. */
  public static final Scenario DEFAULT =
      new Scenario(
          Map.ofEntries(
              Map.entry(Count.WORKER_CAPACITY, 4),
              Map.entry(Count.WORKERS, 2),
              Map.entry(Count.COST_SPREAD_PERCENT, 50),
              Map.entry(Count.SLOW_NODES, 0),
              Map.entry(Count.CAPACITY_ESTIMATE_PERCENT, 100),
              Map.entry(Count.MOBS, 12),
              Map.entry(Count.MOB_CLASSES, 2),
              Map.entry(Count.BLOCK_CHANGES_PER_TICK, 3),
              Map.entry(Count.PLAYERS, 1),
              Map.entry(Count.VIEW_RADIUS, 3),
              Map.entry(Count.SERVER_BUSY_PERCENT, 50),
              Map.entry(Count.GRACE_TICKS, Placement.Policy.DEFAULT.graceTicks()),
              Map.entry(
                  Count.HOT_WINDOWS, Placement.Policy.DEFAULT.hotTicks() / Placement.WINDOW_TICKS),
              Map.entry(
                  Count.COLD_WINDOWS,
                  Placement.Policy.DEFAULT.coldTicks() / Placement.WINDOW_TICKS),
              Map.entry(Count.STREAM, 0)),
          Map.ofEntries(
              // A mob repaths about every 15 ticks, roughly as in vanilla.
              Map.entry(Chance.REQUEST, 0.07),
              Map.entry(Chance.CANCEL, 0.02),
              Map.entry(Chance.MOB_CHURN, 0.05),
              Map.entry(Chance.CHUNK_CHURN, 0.0),
              Map.entry(Chance.PLAYER_CHURN, 0.01),
              Map.entry(Chance.STRAY_REQUEST, 0.0),
              Map.entry(Chance.REPEAT_REQUEST, 0.0),
              Map.entry(Chance.SLOW_TICK, 0.0),
              Map.entry(Chance.LEAVE_UNREADABLE, 0.0),
              Map.entry(Chance.HUNTING, 0.5)));

  /** Far more demand than the fleet can serve, so pre-send rejection must limit the load. */
  public static final Scenario SATURATED =
      DEFAULT
          .with(Count.WORKER_CAPACITY, 1)
          .with(Count.WORKERS, 1)
          .with(Count.MOBS, 40)
          .with(Chance.REQUEST, 0.25);

  /** Chunks are repeatedly unloaded and loaded, so regions keep becoming unreadable. */
  public static final Scenario CHURN = DEFAULT.with(Chance.CHUNK_CHURN, 0.08);

  /** Share of sampled runs whose server falls behind. The rest can be held to the latency SLO. */
  static final double SLOW_SERVER_SHARE = 0.3;

  /**
   * Share of sampled runs whose controller assumes no more than a normal node serves at worst cost,
   * so that admission alone can be held to the SLO under overload.
   */
  static final double MODEST_ESTIMATE_SHARE = 0.3;

  /**
   * Share of sampled runs whose players see past the floor of {@link Count#VIEW_RADIUS}. Such a run
   * loads many more chunks, and costs as much more to run.
   */
  static final double WIDE_VIEW_SHARE = 0.1;

  /** Share of sampled runs whose chunks may be unreadable before they leave. */
  static final double SLOW_LEAVE_SHARE = 0.2;

  /**
   * Settings drawn from streams of their own, so they do not shift the other settings a seed draws.
   * Few runs take them off their floor.
   */
  private static final Set<Enum<?>> SEPARATE = Set.of(Count.VIEW_RADIUS, Chance.LEAVE_UNREADABLE);

  /**
   * One draw from the full range of every setting. Few draws have slow ticks, wide views or chunks
   * unreadable before they leave.
   */
  public static Scenario sample(Rng rng) {
    Map<Count, Integer> counts = new EnumMap<>(Count.class);
    for (Count setting : Count.values()) {
      if (!SEPARATE.contains(setting)) {
        counts.put(setting, setting.floor() + rng.nextInt(setting.ceiling() - setting.floor() + 1));
      }
    }
    if (rng.nextDouble() < MODEST_ESTIMATE_SHARE) {
      int floor = Count.CAPACITY_ESTIMATE_PERCENT.floor();
      int worstCase = 100 * 100 / (100 + counts.get(Count.COST_SPREAD_PERCENT));
      counts.put(
          Count.CAPACITY_ESTIMATE_PERCENT,
          floor + rng.nextInt(Math.max(floor, worstCase) - floor + 1));
    }
    Map<Chance, Double> chances = new EnumMap<>(Chance.class);
    for (Chance setting : Chance.values()) {
      if (!SEPARATE.contains(setting)) {
        chances.put(setting, rng.nextDouble() * setting.ceiling());
      }
    }
    if (rng.nextDouble() >= SLOW_SERVER_SHARE) {
      chances.put(Chance.SLOW_TICK, 0.0);
    }
    Rng views = rng.derive("view-radius");
    Count view = Count.VIEW_RADIUS;
    counts.put(
        view,
        views.nextDouble() < WIDE_VIEW_SHARE
            ? view.floor() + views.nextInt(view.ceiling() - view.floor() + 1)
            : view.floor());
    Rng leaves = rng.derive("leave-unreadable");
    chances.put(
        Chance.LEAVE_UNREADABLE,
        leaves.nextDouble() < SLOW_LEAVE_SHARE
            ? leaves.nextDouble() * Chance.LEAVE_UNREADABLE.ceiling()
            : 0.0);
    return new Scenario(counts, chances);
  }

  public Scenario with(Count setting, int value) {
    Map<Count, Integer> moved = new EnumMap<>(counts);
    moved.put(setting, value);
    return new Scenario(moved, chances);
  }

  public Scenario with(Chance setting, double value) {
    Map<Chance, Double> moved = new EnumMap<>(chances);
    moved.put(setting, value);
    return new Scenario(counts, moved);
  }

  /**
   * How the {@code n}th node serves. Worker costs are drawn within {@link
   * Count#COST_SPREAD_PERCENT} of the mean, which gives {@link Count#WORKER_CAPACITY} a tick. A few
   * nodes are four times slower, so admission is tested against non-uniform capacity.
   */
  public ModeledNode.Service serviceOf(int nodeIndex) {
    int slowdown = nodeIndex < counts.get(Count.SLOW_NODES) ? 4 : 1;
    int mean =
        Math.max(2, Simulation.STEPS_PER_TICK * slowdown / counts.get(Count.WORKER_CAPACITY));
    int spread = mean * counts.get(Count.COST_SPREAD_PERCENT) / 100;
    return new ModeledNode.Service(counts.get(Count.WORKERS), mean - spread, mean + spread);
  }

  /** Requests a normal node serves per tick on average. */
  public int nodeCapacity() {
    return counts.get(Count.WORKERS) * counts.get(Count.WORKER_CAPACITY);
  }

  /** Highest cost of one request on any of {@code nodeCount} nodes. */
  public int maxCostSteps(int nodeCount) {
    int highest = 0;
    for (int i = 0; i < nodeCount; i++) {
      highest = Math.max(highest, serviceOf(i).maxCostSteps());
    }
    return highest;
  }

  /** Steps of each tick the server works, from {@link Count#SERVER_BUSY_PERCENT}. */
  public int serverBusySteps() {
    int steps = Simulation.STEPS_PER_TICK * counts.get(Count.SERVER_BUSY_PERCENT) / 100;
    return Math.max(1, Math.min(Simulation.MAX_BUSY_STEPS, steps));
  }

  /** How long the controller waits before moving regions between nodes. */
  public Placement.Policy policy() {
    return new Placement.Policy(
        counts.get(Count.GRACE_TICKS),
        counts.get(Count.HOT_WINDOWS) * Placement.WINDOW_TICKS,
        counts.get(Count.COLD_WINDOWS) * Placement.WINDOW_TICKS);
  }

  public Dispatch dispatch() {
    return counts.get(Count.STREAM) == 1 ? Dispatch.STREAM : Dispatch.BATCH;
  }

  /** The capacity the controller assumes every node has, which differs from the truth. */
  public int assumedCapacity() {
    return Math.max(
        1, Math.round(nodeCapacity() * counts.get(Count.CAPACITY_ESTIMATE_PERCENT) / 100f));
  }

  /** Uses the enum's iteration order, and rejects a table that is missing a setting. */
  private static <K extends Enum<K>, V> Map<K, V> complete(Map<K, V> values, Class<K> type) {
    EnumMap<K, V> copy = new EnumMap<>(type);
    copy.putAll(values);
    if (copy.size() != type.getEnumConstants().length) {
      throw new IllegalArgumentException("every setting needs a value, got " + copy.keySet());
    }
    return Collections.unmodifiableMap(copy);
  }
}
