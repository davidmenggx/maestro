package io.github.davidmenggx.maestro.controller.region;

import io.github.davidmenggx.maestro.controller.world.Chunks;

/**
 * A square grid of square, full-height regions with its corner at the world origin. Region {@code
 * rx * regionsPerSide + rz} covers x from {@code rx * regionSize} and z from {@code rz *
 * regionSize}.
 */
public record RegionGrid(int regionSize, int regionsPerSide) {

  public RegionGrid {
    if (regionSize <= 0 || regionSize % Chunks.SIZE != 0) {
      throw new IllegalArgumentException("region size must be a multiple of " + Chunks.SIZE);
    }
    if (regionsPerSide < 1) {
      throw new IllegalArgumentException("a grid holds at least one region: " + regionsPerSide);
    }
  }

  public int regionCount() {
    return regionsPerSide * regionsPerSide;
  }

  /** The region holding the position, or -1 when it falls outside the grid. */
  public int regionAt(int x, int z) {
    int rx = Math.floorDiv(x, regionSize);
    int rz = Math.floorDiv(z, regionSize);
    if (rx < 0 || rx >= regionsPerSide || rz < 0 || rz >= regionsPerSide) {
      return -1;
    }
    return rx * regionsPerSide + rz;
  }

  public int minX(int region) {
    return (require(region) / regionsPerSide) * regionSize;
  }

  public int minZ(int region) {
    return (require(region) % regionsPerSide) * regionSize;
  }

  private int require(int region) {
    if (region < 0 || region >= regionCount()) {
      throw new IllegalArgumentException("no such region: " + region);
    }
    return region;
  }
}
