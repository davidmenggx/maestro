package io.github.davidmenggx.maestro.controller.sim;

/**
 * How much load a run puts on the fleet, and how much the world moves underneath it. Separate from
 * {@link FaultPlan}, which says what the network does rather than what the server asks for.
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

  private static void requireChance(double chance, String name) {
    if (chance < 0 || chance > 1) {
      throw new IllegalArgumentException(name + " must be in [0, 1]: " + chance);
    }
  }
}
