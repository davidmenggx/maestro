package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The load a run puts on the fleet, and how much the world changes during it. Separate from {@link
 * FaultPlan}, which describes network behavior instead of server requests, and from {@link
 * Topology}, which describes the world's shape.
 *
 * <p>Each setting is declared once below, with a minimum it may never go below and a maximum that
 * {@link #sample} draws up to. The sampler, the validation and the coverage assertions all read
 * this table. The maximum limits only the draw, and a focused test may set a higher value.
 */
public record Scenario(Map<Count, Integer> counts, Map<Chance, Double> chances) {

  /** A whole-number setting. */
  public enum Count {
    /**
     * Requests one worker serves per tick on average, so a node serves {@link #WORKERS} times this.
     * A request on a normal node costs at most a tick and a half, and on a slow one four times
     * that.
     */
    WORKER_CAPACITY(1, 12),
    /** Searches a node runs at once. */
    WORKERS(1, 4),
    /** How far one request's cost may stray from the mean, as a percentage of it. */
    COST_SPREAD_PERCENT(0, 50),
    SLOW_NODES(0, 2),
    /** The capacity the controller assumes, as a percentage of a normal node's. */
    CAPACITY_ESTIMATE_PERCENT(75, 200),
    MOBS(0, 48),
    MOB_CLASSES(1, 4),
    BLOCK_CHANGES_PER_TICK(0, 6),
    PLAYERS(0, 3),
    /**
     * How much of a tick the server works before it ends the tick, as a percentage. It idles for
     * the rest, so offload work finished then reaches the controller when the next tick starts.
     */
    SERVER_BUSY_PERCENT(20, 100);

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

  /**
   * The probability that the workload emits the event, per mob or per tick depending on the event.
   */
  public enum Chance {
    REQUEST(0.3),
    CANCEL(0.1),
    MOB_CHURN(0.15),
    CHUNK_CHURN(0.12),
    PLAYER_CHURN(0.1),
    STRAY_REQUEST(0.1),
    /** A mob that asks again in the same tick, as when a goal changes mid-tick. */
    REPEAT_REQUEST(0.1);

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
          Map.of(
              Count.WORKER_CAPACITY, 4,
              Count.WORKERS, 2,
              Count.COST_SPREAD_PERCENT, 50,
              Count.SLOW_NODES, 0,
              Count.CAPACITY_ESTIMATE_PERCENT, 100,
              Count.MOBS, 12,
              Count.MOB_CLASSES, 2,
              Count.BLOCK_CHANGES_PER_TICK, 3,
              Count.PLAYERS, 1,
              Count.SERVER_BUSY_PERCENT, 50),
          Map.of(
              // A mob repaths about every 15 ticks, roughly as in vanilla.
              Chance.REQUEST, 0.07,
              Chance.CANCEL, 0.02,
              Chance.MOB_CHURN, 0.05,
              Chance.CHUNK_CHURN, 0.0,
              Chance.PLAYER_CHURN, 0.01,
              Chance.STRAY_REQUEST, 0.0,
              Chance.REPEAT_REQUEST, 0.0));

  /** Far more demand than the fleet can serve, so pre-send rejection must limit the load. */
  public static final Scenario SATURATED =
      DEFAULT
          .with(Count.WORKER_CAPACITY, 1)
          .with(Count.WORKERS, 1)
          .with(Count.MOBS, 40)
          .with(Chance.REQUEST, 0.25);

  /**
   * Chunks are repeatedly unloaded and loaded, so regions keep becoming unreadable and readable.
   */
  public static final Scenario CHURN = DEFAULT.with(Chance.CHUNK_CHURN, 0.08);

  /** One draw from the full range of every setting, including loads the fleet cannot serve. */
  public static Scenario sample(Rng rng) {
    Map<Count, Integer> counts = new EnumMap<>(Count.class);
    for (Count setting : Count.values()) {
      counts.put(setting, setting.floor() + rng.nextInt(setting.ceiling() - setting.floor() + 1));
    }
    Map<Chance, Double> chances = new EnumMap<>(Chance.class);
    for (Chance setting : Chance.values()) {
      chances.put(setting, rng.nextDouble() * setting.ceiling());
    }
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
   * How the {@code n}th node serves. Its workers each take a cost drawn evenly within {@link
   * Count#COST_SPREAD_PERCENT} of the mean either side of it, and the mean gives a worker {@link
   * Count#WORKER_CAPACITY} a tick. A few nodes are four times slower than the rest, so admission
   * control, which assumes uniform capacity, is tested against a fleet that does not have it.
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
