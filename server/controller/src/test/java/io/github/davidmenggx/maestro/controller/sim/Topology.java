package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import java.util.List;

/**
 * The coordinate space a run happens in and how it is divided among nodes: a square grid of square,
 * full-height regions, one node each. Separate from {@link Scenario}, which says what happens
 * inside that space.
 */
public record Topology(int regionSize, int regionsPerSide, int minY, int height, int relief) {

  /** Chunk width. Regions are chunk aligned, or residency cannot be decided per region. */
  public static final int CHUNK_SIZE = 16;

  static final int MIN_HEIGHT = 32;
  static final int MAX_HEIGHT = 96;
  static final int MAX_REGIONS_PER_SIDE = 3;

  /** Capped against the shortest column the sampler can draw, so every draw clears the check. */
  static final int MAX_RELIEF = MIN_HEIGHT / 4;

  public static final Topology DEFAULT = new Topology(CHUNK_SIZE, 2, 0, 64, 2);

  /** Floors a sampled world may sit on. The overworld's is negative, so the sampler covers that. */
  static List<Integer> floors() {
    return List.of(0, -16, -64);
  }

  static List<Integer> regionSizes() {
    return List.of(CHUNK_SIZE, 2 * CHUNK_SIZE);
  }

  /**
   * Nodes in the widest grid {@link #sample} can draw, and so one past the highest node id any run
   * can assign. Ids at or above it are free for fixtures built outside a fleet.
   */
  static int maxNodeCount() {
    return MAX_REGIONS_PER_SIDE * MAX_REGIONS_PER_SIDE;
  }

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
    List<Integer> sizes = regionSizes();
    List<Integer> floors = floors();
    return new Topology(
        sizes.get(rng.nextInt(sizes.size())),
        1 + rng.nextInt(MAX_REGIONS_PER_SIDE),
        floors.get(rng.nextInt(floors.size())),
        MIN_HEIGHT + rng.nextInt(MAX_HEIGHT - MIN_HEIGHT + 1),
        rng.nextInt(MAX_RELIEF + 1));
  }
}
