package io.github.davidmenggx.maestro.controller.region;

import io.github.davidmenggx.maestro.controller.world.Chunks;

/**
 * A square, full-height area of the world made of whole chunks, with its corner at {@code (minX,
 * minZ)}. {@code id} names its world stream and is never reused within a controller run. Ids start
 * at 1, so 0 means no region on the wire.
 */
public record Region(int id, int minX, int minZ, int size) {

  public Region {
    if (id < 1) {
      throw new IllegalArgumentException("region ids start at 1: " + id);
    }
    if (size <= 0 || size % Chunks.SIZE != 0) {
      throw new IllegalArgumentException("a region is whole chunks: " + size);
    }
    if (Math.floorMod(minX, Chunks.SIZE) != 0 || Math.floorMod(minZ, Chunks.SIZE) != 0) {
      throw new IllegalArgumentException("a region starts at a chunk: " + minX + "," + minZ);
    }
    if ((long) minX + size > Integer.MAX_VALUE || (long) minZ + size > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("a region past the int range: " + minX + "," + minZ);
    }
  }

  public boolean contains(int x, int z) {
    return x >= minX && x - minX < size && z >= minZ && z - minZ < size;
  }

  public boolean overlaps(Region other) {
    return minX < other.minX + other.size
        && other.minX < minX + size
        && minZ < other.minZ + other.size
        && other.minZ < minZ + size;
  }
}
