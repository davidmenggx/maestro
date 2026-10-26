package io.github.davidmenggx.maestro.controller.region;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayList;
import java.util.List;

/**
 * The square of the world the fleet covers: {@code cells} by {@code cells} cells of a fixed lattice
 * of {@link #CELL}-block squares, one of them centered on the origin. A region is a rectangle of
 * whole cells, and the regions of a run tile the square. Searches starting outside it stay on the
 * server.
 */
public record RegionLayout(int cells) {

  /** Side of a lattice cell: four pathfinding horizons of about 64 blocks, the spec minimum. */
  public static final int CELL = 256;

  /** Most cells along a side, so every position in an area fits the wire's 16 bits. */
  public static final int MAX_CELLS = (Short.MAX_VALUE - 2 * Region.HALO) / CELL;

  public RegionLayout {
    if (cells < 1 || cells > MAX_CELLS) {
      throw new IllegalArgumentException("a layout is 1 to " + MAX_CELLS + " cells: " + cells);
    }
  }

  /** Lowest x or z of the cells at {@code index} along one axis. Cell 0 is centered on 0. */
  public static int cellMin(int index) {
    return -CELL / 2 + index * CELL;
  }

  /** Lowest x and z covered. The origin's cell is the middle one, or the lower of two. */
  public int min() {
    return cellMin(-((cells - 1) / 2));
  }

  /** Blocks covered along each axis. */
  public int size() {
    return cells * CELL;
  }

  /** Whether the block lies in a region. */
  public boolean covers(int x, int z) {
    return x >= min() && x - min() < size() && z >= min() && z - min() < size();
  }

  /** Whether the block lies in some region's area: in a region or within its halo. */
  public boolean reaches(int x, int z) {
    int low = min() - Region.HALO;
    int span = size() + 2 * Region.HALO;
    return x >= low && x - low < span && z >= low && z - low < span;
  }

  /**
   * Where a region is cut: across x (between columns of cells) or across z, with {@code lowerCells}
   * cells on the lower side, which keeps the region's corner.
   */
  public record Cut(boolean acrossX, int lowerCells) {

    public Cut {
      if (lowerCells < 1) {
        throw new IllegalArgumentException("a cut leaves a cell on each side: " + lowerCells);
      }
    }
  }

  /** Whether the region spans more than one cell, so it can be cut. */
  public static boolean splittable(Region region) {
    return region.sizeX() > CELL || region.sizeZ() > CELL;
  }

  /**
   * The cut that halves a rectangle of whole cells: across its longer side, or across x if it is
   * square. A side of an odd number of cells leaves the upper half one cell larger.
   */
  public static Cut halving(int sizeX, int sizeZ) {
    int cellsX = sizeX / CELL;
    int cellsZ = sizeZ / CELL;
    if (cellsX <= 1 && cellsZ <= 1) {
      throw new IllegalArgumentException("one cell cannot be halved");
    }
    return cellsX >= cellsZ ? new Cut(true, cellsX / 2) : new Cut(false, cellsZ / 2);
  }

  /** A rectangle's two sides of {@code cut}, lower first, as {@code {minX, minZ, sizeX, sizeZ}}. */
  public static List<int[]> sides(int minX, int minZ, int sizeX, int sizeZ, Cut cut) {
    int lower = cut.lowerCells() * CELL;
    if (lower >= (cut.acrossX() ? sizeX : sizeZ)) {
      throw new IllegalArgumentException(cut + " leaves nothing above it");
    }
    if (cut.acrossX()) {
      return List.of(
          new int[] {minX, minZ, lower, sizeZ},
          new int[] {minX + lower, minZ, sizeX - lower, sizeZ});
    }
    return List.of(
        new int[] {minX, minZ, sizeX, lower}, new int[] {minX, minZ + lower, sizeX, sizeZ - lower});
  }

  /**
   * The regions a run starts with: the square halved, largest region first, until there is one
   * region per node of {@code fleet} past {@code spares}, at least one and at most one per cell.
   * The {@code i}th node holds the {@code i}th region; the nodes left over are spares.
   */
  public RegionMap initial(List<NodeId> fleet, int spares) {
    if (fleet.isEmpty()) {
      throw new IllegalArgumentException("a fleet has at least one node");
    }
    if (spares < 0) {
      throw new IllegalArgumentException("a negative spare count: " + spares);
    }
    int count = regionCount(fleet.size(), spares);
    List<int[]> parts = new ArrayList<>();
    parts.add(new int[] {min(), min(), size(), size()});
    while (parts.size() < count) {
      int largest = 0;
      for (int i = 1; i < parts.size(); i++) {
        if (area(parts.get(i)) > area(parts.get(largest))) {
          largest = i;
        }
      }
      int[] part = parts.remove(largest);
      parts.addAll(largest, sides(part[0], part[1], part[2], part[3], halving(part[2], part[3])));
    }
    RegionMap map = new RegionMap(this);
    for (int i = 0; i < parts.size(); i++) {
      int[] part = parts.get(i);
      map.assign(map.add(part[0], part[1], part[2], part[3]).id(), fleet.get(i));
    }
    return map;
  }

  /** Regions {@link #initial} makes for a fleet of {@code nodes} with {@code spares}. */
  public int regionCount(int nodes, int spares) {
    return Math.min(cells * cells, Math.max(1, nodes - spares));
  }

  private static long area(int[] part) {
    return (long) part[2] * part[3];
  }
}
