package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;

/**
 * The coordinate space a run happens in and how it is divided among nodes: a square grid of square,
 * full-height regions, one node each. Separate from {@link Scenario}, which says what happens
 * inside that space.
 */
public record Topology(int regionSize, int regionsPerSide, int minY, int height, int relief) {

  /** Chunk width. Regions are chunk aligned, or residency cannot be decided per region. */
  public static final int CHUNK_SIZE = 16;

  private static final int MIN_HEIGHT = 32;

  private static final int MAX_HEIGHT = 96;
  private static final int MAX_REGIONS_PER_SIDE = 3;
  private static final int MAX_RELIEF = MIN_HEIGHT / 4;

  private static final int[] FLOORS = {0, -16, -64};

  private static final int[] REGION_SIZES = {CHUNK_SIZE, 2 * CHUNK_SIZE};

  public static final Topology DEFAULT = new Topology(CHUNK_SIZE, 2, 0, 64, 2);

  public Topology {
    if (regionSize <= 0 || regionSize % CHUNK_SIZE != 0) {
      throw new IllegalArgumentException("region size must be a multiple of " + CHUNK_SIZE);
    }
    if (regionsPerSide < 1) {
      throw new IllegalArgumentException("a grid holds at least one region: " + regionsPerSide);
    }
    if (height < MIN_HEIGHT) {
      throw new IllegalArgumentException("a column is at least " + MIN_HEIGHT + " blocks");
    }
    if (relief < 0 || relief > height / 4) {
      throw new IllegalArgumentException("relief must not exceed a quarter of the column");
    }
  }

  /** Block extent of the whole grid along one axis. */
  public int extent() {
    return regionSize * regionsPerSide;
  }

  /** One node per region, since resharding is not modeled. */
  public int nodeCount() {
    return regionsPerSide * regionsPerSide;
  }

  public int chunksPerSide() {
    return extent() / CHUNK_SIZE;
  }

  public static Topology sample(Rng rng) {
    return new Topology(
        REGION_SIZES[rng.nextInt(REGION_SIZES.length)],
        1 + rng.nextInt(MAX_REGIONS_PER_SIDE),
        FLOORS[rng.nextInt(FLOORS.length)],
        MIN_HEIGHT + rng.nextInt(MAX_HEIGHT - MIN_HEIGHT + 1),
        rng.nextInt(MAX_RELIEF + 1));
  }
}
