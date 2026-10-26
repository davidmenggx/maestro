package io.github.davidmenggx.maestro.controller.region;

import io.github.davidmenggx.maestro.controller.world.Chunks;

/**
 * A full-height rectangle of the world made of whole chunks, with its corner at {@code (minX,
 * minZ)}. Its holders are sent its area: the region and {@link #HALO} blocks around it, so a search
 * that starts in the region reads real blocks past its edge. {@code id} names its world stream and
 * is never reused within a controller run. Ids start at 1, so 0 means no region on the wire.
 */
public record Region(int id, int minX, int minZ, int sizeX, int sizeZ) {

  /** One pathfinding horizon, in whole chunks. Areas of neighboring regions overlap by twice it. */
  public static final int HALO = 64;

  public Region {
    if (id < 1) {
      throw new IllegalArgumentException("region ids start at 1: " + id);
    }
    if (sizeX <= 0 || sizeX % Chunks.SIZE != 0 || sizeZ <= 0 || sizeZ % Chunks.SIZE != 0) {
      throw new IllegalArgumentException("a region is whole chunks: " + sizeX + " x " + sizeZ);
    }
    if (Math.floorMod(minX, Chunks.SIZE) != 0 || Math.floorMod(minZ, Chunks.SIZE) != 0) {
      throw new IllegalArgumentException("a region starts at a chunk: " + minX + "," + minZ);
    }
    if ((long) minX - HALO < Integer.MIN_VALUE
        || (long) minZ - HALO < Integer.MIN_VALUE
        || (long) minX + sizeX + HALO > Integer.MAX_VALUE
        || (long) minZ + sizeZ + HALO > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("an area past the int range: " + minX + "," + minZ);
    }
  }

  public boolean contains(int x, int z) {
    return x >= minX && x - minX < sizeX && z >= minZ && z - minZ < sizeZ;
  }

  public boolean overlaps(Region other) {
    return minX < other.minX + other.sizeX
        && other.minX < minX + sizeX
        && minZ < other.minZ + other.sizeZ
        && other.minZ < minZ + sizeZ;
  }

  public int areaMinX() {
    return minX - HALO;
  }

  public int areaMinZ() {
    return minZ - HALO;
  }

  public int areaSizeX() {
    return sizeX + 2 * HALO;
  }

  public int areaSizeZ() {
    return sizeZ + 2 * HALO;
  }

  /** Whether the block lies in the area its holders are sent. */
  public boolean inArea(int x, int z) {
    return x >= areaMinX()
        && x - areaMinX() < areaSizeX()
        && z >= areaMinZ()
        && z - areaMinZ() < areaSizeZ();
  }
}
