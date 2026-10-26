package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import java.util.List;

/**
 * The coordinate space of a run and its fleet: the production layout's square of {@code cells} by
 * {@code cells} cells around the origin, and a world reaching one halo past it, so the outer
 * regions' areas hold real chunks too. The controller cuts the square into a region for each of the
 * first {@code nodes - spares} nodes ({@link RegionLayout#initial}); the rest start as spares.
 *
 * <p>The {@code with} methods change one dimension of {@link #DEFAULT} in a focused test.
 */
public record Topology(int cells, int nodes, int spares, int minY, int height, int relief) {

  static final int MIN_HEIGHT = 32;
  static final int MAX_HEIGHT = 96;
  static final int MAX_CELLS = 3;
  static final int MAX_SPARES = 2;

  /** Capped by the shortest column the sampler can draw, so every draw passes the check. */
  static final int MAX_RELIEF = MIN_HEIGHT / 4;

  /** Four single-cell regions and a spare. */
  public static final Topology DEFAULT = new Topology(2, 5, 1, 0, 64, 2);

  /** Floors a sampled world may use. The overworld's is negative. */
  static List<Integer> floors() {
    return List.of(0, -16, -64);
  }

  /** Number of nodes in the largest fleet {@link #sample} can draw. */
  static int maxNodeCount() {
    return MAX_CELLS * MAX_CELLS + MAX_SPARES;
  }

  public Topology {
    if (cells < 1 || cells > RegionLayout.MAX_CELLS) {
      throw new IllegalArgumentException("a square of 1 to " + RegionLayout.MAX_CELLS + " cells");
    }
    if (nodes < 1 || spares < 0) {
      throw new IllegalArgumentException("a fleet of " + nodes + " with " + spares + " spares");
    }
    if (height < MIN_HEIGHT) {
      throw new IllegalArgumentException("a column is at least " + MIN_HEIGHT + " blocks");
    }
    if (relief < 0 || relief > height / 4) {
      throw new IllegalArgumentException("relief must not exceed a quarter of the column");
    }
  }

  public RegionLayout layout() {
    return new RegionLayout(cells);
  }

  /** Regions the run starts with. */
  public int regionCount() {
    return layout().regionCount(nodes, spares);
  }

  /** Lowest x and z of the world: one halo below the square. */
  public int min() {
    return layout().min() - Region.HALO;
  }

  /** Size of the world along one axis, in blocks. */
  public int extent() {
    return layout().size() + 2 * Region.HALO;
  }

  public int nodeCount() {
    return nodes;
  }

  public int chunksPerSide() {
    return extent() / Chunks.SIZE;
  }

  public Topology withCells(int value) {
    return new Topology(value, nodes, spares, minY, height, relief);
  }

  public Topology withNodes(int value) {
    return new Topology(cells, value, spares, minY, height, relief);
  }

  public Topology withSpares(int value) {
    return new Topology(cells, nodes, value, minY, height, relief);
  }

  public Topology withMinY(int value) {
    return new Topology(cells, nodes, spares, value, height, relief);
  }

  public Topology withHeight(int value) {
    return new Topology(cells, nodes, spares, minY, value, relief);
  }

  public Topology withRelief(int value) {
    return new Topology(cells, nodes, spares, minY, height, value);
  }

  /** A square, a region count up to one per cell, and up to {@link #MAX_SPARES} spares. */
  public static Topology sample(Rng rng) {
    List<Integer> floors = floors();
    int cells = 1 + rng.nextInt(MAX_CELLS);
    int regions = 1 + rng.nextInt(cells * cells);
    int spares = rng.nextInt(MAX_SPARES + 1);
    return new Topology(
        cells,
        regions + spares,
        spares,
        floors.get(rng.nextInt(floors.size())),
        MIN_HEIGHT + rng.nextInt(MAX_HEIGHT - MIN_HEIGHT + 1),
        rng.nextInt(MAX_RELIEF + 1));
  }
}
