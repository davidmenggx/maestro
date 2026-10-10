package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The load a run puts on the fleet, and how much the world changes during it. Separate from
 * {@link FaultPlan}, which describes network behavior instead of server requests, and from
 * {@link Topology}, which describes the world's shape.
 *
 * <p>Each setting is declared once below, with a minimum it may never go below and a maximum that
 * {@link #sample} draws up to. The sampler, the validation and the coverage assertions all read
 * this table. The maximum limits only the draw, and a focused test may set a higher value.
 */
public record Scenario(Map<Count, Integer> counts, Map<Chance, Double> chances) {

  /** A whole-number setting. */
  public enum Count {
    NODE_CAPACITY(1, 12),
    SLOW_NODES(0, 2),
    MOBS(0, 48),
    MOB_CLASSES(1, 4),
    BLOCK_CHANGES_PER_TICK(0, 6),
    PLAYERS(0, 3);

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

  /** The probability that the workload emits the event, per mob or per tick depending on the event. */
  public enum Chance {
    REQUEST(0.3),
    CANCEL(0.1),
    MOB_CHURN(0.15),
    CHUNK_CHURN(0.12),
    PLAYER_CHURN(0.1),
    STRAY_REQUEST(0.1);

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
              Count.NODE_CAPACITY, 8,
              Count.SLOW_NODES, 0,
              Count.MOBS, 12,
              Count.MOB_CLASSES, 2,
              Count.BLOCK_CHANGES_PER_TICK, 3,
              Count.PLAYERS, 1),
          Map.of(
              // A mob repaths about every 15 ticks, roughly as in vanilla.
              Chance.REQUEST, 0.07,
              Chance.CANCEL, 0.02,
              Chance.MOB_CHURN, 0.05,
              Chance.CHUNK_CHURN, 0.0,
              Chance.PLAYER_CHURN, 0.01,
              Chance.STRAY_REQUEST, 0.0));

  /** Far more demand than the fleet can serve, so pre-send rejection must limit the load. */
  public static final Scenario SATURATED =
      DEFAULT.with(Count.NODE_CAPACITY, 1).with(Count.MOBS, 40).with(Chance.REQUEST, 0.25);

  /** Chunks are repeatedly unloaded and loaded, so regions keep becoming unreadable and readable. */
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
   * Capacity of the {@code n}th node. A few nodes are slower than the rest, so admission control,
   * which assumes uniform capacity, is tested against a fleet that does not have it.
   */
  public int capacityOf(int nodeIndex) {
    int capacity = counts.get(Count.NODE_CAPACITY);
    return nodeIndex < counts.get(Count.SLOW_NODES) ? Math.max(1, capacity / 4) : capacity;
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
