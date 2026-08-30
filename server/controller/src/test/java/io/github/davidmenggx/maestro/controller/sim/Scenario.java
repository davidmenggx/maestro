package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;

/**
 * How much load a run puts on the fleet, and how much the world moves underneath it. Separate from
 * {@link FaultPlan}, which says what the network does rather than what the server asks for, and
 * from {@link Topology}, which says what shape the world is.
 *
 * <p>{@link #sample} draws one from the ranges below, so a seed sweep covers the settings and not
 * only the event ordering. Every range includes the degenerate end (no mobs, no players, no block
 * changes), because an empty server is a state the controller has to sit in without producing
 * anything. The named constants are for tests that need one specific pressure.
 */
public record Scenario(
    int nodeCapacity,
    int slowNodes,
    int mobCount,
    int mobClassCount,
    int maxBlockChangesPerTick,
    int playerCount,
    double requestChance,
    double cancelChance,
    double mobChurnChance,
    double chunkChurnChance,
    double playerChurnChance,
    double strayRequestChance) {

  public Scenario {
    if (nodeCapacity < 1) {
      throw new IllegalArgumentException("a node serves at least one request a tick");
    }
    requireCount(slowNodes, "slowNodes");
    requireCount(mobCount, "mobCount");
    requireCount(maxBlockChangesPerTick, "maxBlockChangesPerTick");
    requireCount(playerCount, "playerCount");
    if (mobClassCount < 1) {
      throw new IllegalArgumentException("a world has at least one mob profile: " + mobClassCount);
    }
    requireChance(requestChance, "requestChance");
    requireChance(cancelChance, "cancelChance");
    requireChance(mobChurnChance, "mobChurnChance");
    requireChance(chunkChurnChance, "chunkChurnChance");
    requireChance(playerChurnChance, "playerChurnChance");
    requireChance(strayRequestChance, "strayRequestChance");
  }

  /** A fleet with headroom and a world whose chunks stay resident. */
  public static final Scenario DEFAULT =
      new Scenario(
          8, 0, 12, 2, 3, 1, SyntheticWorkload.DEFAULT_REQUEST_CHANCE, 0.02, 0.05, 0.0, 0.01, 0.0);

  /** Far more demand than the fleet can serve, so pre-send rejection has to hold the line. */
  public static final Scenario SATURATED =
      DEFAULT.withNodeCapacity(1).withMobCount(40).withRequestChance(0.25);

  /** Chunks come and go under the controller, so regions keep losing and regaining readability. */
  public static final Scenario CHURN = DEFAULT.withChunkChurnChance(0.08);

  private static final int MAX_NODE_CAPACITY = 12;
  private static final int MAX_SLOW_NODES = 2;
  private static final int MAX_MOB_COUNT = 48;
  private static final int MAX_MOB_CLASSES = 4;
  private static final int MAX_BLOCK_CHANGES_PER_TICK = 6;
  private static final int MAX_PLAYERS = 3;
  private static final double MAX_REQUEST_CHANCE = 0.3;
  private static final double MAX_CANCEL_CHANCE = 0.1;
  private static final double MAX_MOB_CHURN_CHANCE = 0.15;
  private static final double MAX_CHUNK_CHURN_CHANCE = 0.12;
  private static final double MAX_PLAYER_CHURN_CHANCE = 0.1;
  private static final double MAX_STRAY_REQUEST_CHANCE = 0.1;

  /** One draw from the full range of each setting, including ones the fleet cannot serve. */
  public static Scenario sample(Rng rng) {
    return new Scenario(
        1 + rng.nextInt(MAX_NODE_CAPACITY),
        rng.nextInt(MAX_SLOW_NODES + 1),
        rng.nextInt(MAX_MOB_COUNT + 1),
        1 + rng.nextInt(MAX_MOB_CLASSES),
        rng.nextInt(MAX_BLOCK_CHANGES_PER_TICK + 1),
        rng.nextInt(MAX_PLAYERS + 1),
        rng.nextDouble() * MAX_REQUEST_CHANCE,
        rng.nextDouble() * MAX_CANCEL_CHANCE,
        rng.nextDouble() * MAX_MOB_CHURN_CHANCE,
        rng.nextDouble() * MAX_CHUNK_CHURN_CHANCE,
        rng.nextDouble() * MAX_PLAYER_CHURN_CHANCE,
        rng.nextDouble() * MAX_STRAY_REQUEST_CHANCE);
  }

  /**
   * Capacity of the {@code n}th node. A few nodes run slower than the rest, so admission control,
   * which assumes one uniform capacity, has to meet a fleet that does not have one.
   */
  public int capacityOf(int nodeIndex) {
    return nodeIndex < slowNodes ? Math.max(1, nodeCapacity / 4) : nodeCapacity;
  }

  public Scenario withNodeCapacity(int capacity) {
    return new Scenario(
        capacity,
        slowNodes,
        mobCount,
        mobClassCount,
        maxBlockChangesPerTick,
        playerCount,
        requestChance,
        cancelChance,
        mobChurnChance,
        chunkChurnChance,
        playerChurnChance,
        strayRequestChance);
  }

  public Scenario withMobCount(int count) {
    return new Scenario(
        nodeCapacity,
        slowNodes,
        count,
        mobClassCount,
        maxBlockChangesPerTick,
        playerCount,
        requestChance,
        cancelChance,
        mobChurnChance,
        chunkChurnChance,
        playerChurnChance,
        strayRequestChance);
  }

  public Scenario withRequestChance(double chance) {
    return new Scenario(
        nodeCapacity,
        slowNodes,
        mobCount,
        mobClassCount,
        maxBlockChangesPerTick,
        playerCount,
        chance,
        cancelChance,
        mobChurnChance,
        chunkChurnChance,
        playerChurnChance,
        strayRequestChance);
  }

  public Scenario withMaxBlockChangesPerTick(int changes) {
    return new Scenario(
        nodeCapacity,
        slowNodes,
        mobCount,
        mobClassCount,
        changes,
        playerCount,
        requestChance,
        cancelChance,
        mobChurnChance,
        chunkChurnChance,
        playerChurnChance,
        strayRequestChance);
  }

  public Scenario withChunkChurnChance(double chance) {
    return new Scenario(
        nodeCapacity,
        slowNodes,
        mobCount,
        mobClassCount,
        maxBlockChangesPerTick,
        playerCount,
        requestChance,
        cancelChance,
        mobChurnChance,
        chance,
        playerChurnChance,
        strayRequestChance);
  }

  private static void requireCount(int count, String name) {
    if (count < 0) {
      throw new IllegalArgumentException(name + " must not be negative: " + count);
    }
  }

  private static void requireChance(double chance, String name) {
    if (chance < 0 || chance > 1) {
      throw new IllegalArgumentException(name + " must be in [0, 1]: " + chance);
    }
  }
}
