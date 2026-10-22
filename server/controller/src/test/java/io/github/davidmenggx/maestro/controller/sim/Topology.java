package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import java.util.List;

/**
 * The coordinate space of a run and how it is divided among nodes: a square grid of square,
 * full-height regions of {@link #REGION_SIZE} blocks, cut from the production lattice ({@link
 * RegionLayout}), so the origin is at the center of a region and positions on both sides of it are
 * covered. Each region has one node, and the first {@code replicated} have a second, which shares
 * its requests. Separate from {@link Scenario}, which describes what happens in that space.
 *
 * <p>The {@code with} methods let a focused test change one dimension of {@link #DEFAULT} without
 * restating the rest.
 */
public record Topology(int regionsPerSide, int replicated, int minY, int height, int relief) {

  public static final int REGION_SIZE = RegionLayout.SIZE;

  static final int MIN_HEIGHT = 32;
  static final int MAX_HEIGHT = 96;
  static final int MAX_REGIONS_PER_SIDE = 3;
  static final int MAX_REPLICATED = 2;

  /** Capped by the shortest column the sampler can draw, so every draw passes the check. */
  static final int MAX_RELIEF = MIN_HEIGHT / 4;

  public static final Topology DEFAULT = new Topology(2, 1, 0, 64, 2);

  /** Floors a sampled world may use. The overworld's is negative. */
  static List<Integer> floors() {
    return List.of(0, -16, -64);
  }

  /** Number of nodes in the largest fleet {@link #sample} can draw. */
  static int maxNodeCount() {
    return MAX_REGIONS_PER_SIDE * MAX_REGIONS_PER_SIDE + MAX_REPLICATED;
  }

  public Topology {
    if (regionsPerSide < 1) {
      throw new IllegalArgumentException("a grid holds at least one region: " + regionsPerSide);
    }
    if (replicated < 0 || replicated > regionsPerSide * regionsPerSide) {
      throw new IllegalArgumentException("cannot replicate " + replicated + " regions");
    }
    if (height < MIN_HEIGHT) {
      throw new IllegalArgumentException("a column is at least " + MIN_HEIGHT + " blocks");
    }
    if (relief < 0 || relief > height / 4) {
      throw new IllegalArgumentException("relief must not exceed a quarter of the column");
    }
  }

  /**
   * The regions, in grid order, each held by the next node of {@code fleet}, then the replicated
   * ones held again by the nodes after those. {@code fleet} has {@link #nodeCount} nodes.
   */
  public RegionMap regions(List<NodeId> fleet) {
    if (fleet.size() != nodeCount()) {
      throw new IllegalArgumentException(fleet.size() + " nodes for " + nodeCount());
    }
    RegionMap map = new RegionMap();
    int next = 0;
    for (int rx = 0; rx < regionsPerSide; rx++) {
      for (int rz = 0; rz < regionsPerSide; rz++) {
        Region region = map.add(min() + rx * REGION_SIZE, min() + rz * REGION_SIZE, REGION_SIZE);
        map.assign(region.id(), fleet.get(next++));
      }
    }
    List<Region> all = List.copyOf(map.regions());
    for (int i = 0; i < replicated; i++) {
      map.assign(all.get(i).id(), fleet.get(next++));
    }
    return map;
  }

  /**
   * Lowest x and z of the grid. It starts {@code (regionsPerSide - 1) / 2} cells below the
   * origin's, so the origin's cell is the middle one, or the lower of the middle two.
   */
  public int min() {
    return RegionLayout.cellMin(-((regionsPerSide - 1) / 2));
  }

  /** Size of the whole grid along one axis, in blocks. */
  public int extent() {
    return REGION_SIZE * regionsPerSide;
  }

  public int nodeCount() {
    return regionsPerSide * regionsPerSide + replicated;
  }

  public int chunksPerSide() {
    return extent() / Chunks.SIZE;
  }

  public Topology withRegionsPerSide(int value) {
    return new Topology(value, Math.min(replicated, value * value), minY, height, relief);
  }

  public Topology withReplicated(int value) {
    return new Topology(regionsPerSide, value, minY, height, relief);
  }

  public Topology withMinY(int value) {
    return new Topology(regionsPerSide, replicated, value, height, relief);
  }

  public Topology withHeight(int value) {
    return new Topology(regionsPerSide, replicated, minY, value, relief);
  }

  public Topology withRelief(int value) {
    return new Topology(regionsPerSide, replicated, minY, height, value);
  }

  public static Topology sample(Rng rng) {
    List<Integer> floors = floors();
    int perSide = 1 + rng.nextInt(MAX_REGIONS_PER_SIDE);
    return new Topology(
        perSide,
        rng.nextInt(Math.min(MAX_REPLICATED, perSide * perSide) + 1),
        floors.get(rng.nextInt(floors.size())),
        MIN_HEIGHT + rng.nextInt(MAX_HEIGHT - MIN_HEIGHT + 1),
        rng.nextInt(MAX_RELIEF + 1));
  }
}
