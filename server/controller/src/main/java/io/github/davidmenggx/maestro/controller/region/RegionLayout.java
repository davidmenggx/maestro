package io.github.davidmenggx.maestro.controller.region;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayList;
import java.util.List;

/**
 * The lattice regions are cut from: squares of {@link #SIZE} blocks, one of them centered on the
 * origin. Cell {@code (i, j)} has its corner at {@code (cellMin(i), cellMin(j))}.
 */
public final class RegionLayout {

  /** Side of a region: four pathfinding horizons of about 64 blocks, the spec minimum. */
  public static final int SIZE = 256;

  /** One cell of the lattice, by index along each axis. */
  public record Cell(int x, int z) {}

  private static final int[][] TURNS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

  private RegionLayout() {}

  /** Lowest x or z of the cells at {@code index} along one axis. Cell 0 is centered on 0. */
  public static int cellMin(int index) {
    return -SIZE / 2 + index * SIZE;
  }

  /**
   * The first {@code count} cells of a square spiral out from the origin's cell, so the first
   * {@code n * n} form an {@code n} by {@code n} square.
   */
  public static List<Cell> spiral(int count) {
    if (count < 0) {
      throw new IllegalArgumentException("a negative cell count: " + count);
    }
    List<Cell> cells = new ArrayList<>(count);
    if (count > 0) {
      cells.add(new Cell(0, 0));
    }
    int x = 0;
    int z = 0;
    // Legs of 1, 1, 2, 2, 3, 3, ... cells, turning from +x to +z to -x to -z.
    for (int leg = 0; cells.size() < count; leg++) {
      int[] step = TURNS[leg % TURNS.length];
      for (int i = 0; i <= leg / 2 && cells.size() < count; i++) {
        x += step[0];
        z += step[1];
        cells.add(new Cell(x, z));
      }
    }
    return cells;
  }

  /** One region per node, the {@code i}th node holding the {@code i}th cell of {@link #spiral}. */
  public static RegionMap spiral(List<NodeId> nodes) {
    RegionMap map = new RegionMap();
    List<Cell> cells = spiral(nodes.size());
    for (int i = 0; i < nodes.size(); i++) {
      Cell cell = cells.get(i);
      Region region = map.add(cellMin(cell.x()), cellMin(cell.z()), SIZE);
      map.assign(region.id(), nodes.get(i));
    }
    return map;
  }
}
