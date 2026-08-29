package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;

/**
 * How much load a run puts on the fleet, and how much the world moves underneath it. Separate from
 * {@link FaultPlan}, which says what the network does rather than what the server asks for.
 *
 * <p>{@link #sample} draws one from the ranges below, so a seed sweep covers the settings and not
 * only the event ordering. The named constants are for tests that need one specific pressure.
 */
public record Scenario(
    int nodeCapacity, int mobCount, double requestChance, double chunkChurnChance) {

  public Scenario {
    if (nodeCapacity < 1) {
      throw new IllegalArgumentException("a node serves at least one request a tick");
    }
    if (mobCount < 0) {
      throw new IllegalArgumentException("mob count must not be negative: " + mobCount);
    }
    requireChance(requestChance, "requestChance");
    requireChance(chunkChurnChance, "chunkChurnChance");
  }

  /** A fleet with headroom and a world whose chunks stay resident. */
  public static final Scenario DEFAULT =
      new Scenario(8, 12, SyntheticWorkload.DEFAULT_REQUEST_CHANCE, 0.0);

  /** Far more demand than the fleet can serve, so pre-send rejection has to hold the line. */
  public static final Scenario SATURATED = new Scenario(1, 40, 0.25, 0.0);

  /** Chunks come and go under the controller, so regions keep losing and regaining readability. */
  public static final Scenario CHURN =
      new Scenario(8, 12, SyntheticWorkload.DEFAULT_REQUEST_CHANCE, 0.08);

  private static final int MAX_NODE_CAPACITY = 12;
  private static final int MAX_MOB_COUNT = 48;
  private static final double MAX_REQUEST_CHANCE = 0.3;
  private static final double MAX_CHUNK_CHURN_CHANCE = 0.12;

  /** One draw from the full range of each setting, including ones the fleet cannot serve. */
  public static Scenario sample(Rng rng) {
    return new Scenario(
        1 + rng.nextInt(MAX_NODE_CAPACITY),
        rng.nextInt(MAX_MOB_COUNT + 1),
        rng.nextDouble() * MAX_REQUEST_CHANCE,
        rng.nextDouble() * MAX_CHUNK_CHURN_CHANCE);
  }

  private static void requireChance(double chance, String name) {
    if (chance < 0 || chance > 1) {
      throw new IllegalArgumentException(name + " must be in [0, 1]: " + chance);
    }
  }
}
