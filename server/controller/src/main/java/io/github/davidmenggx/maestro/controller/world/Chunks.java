package io.github.davidmenggx.maestro.controller.world;

/**
 * Chunk geometry, as Minecraft defines it. Regions are chunk aligned, so world state is tracked and
 * sent a chunk at a time.
 */
public final class Chunks {

  /** Block width of a chunk along both horizontal axes. */
  public static final int SIZE = 16;

  private Chunks() {}

  /** Chunk coordinate holding a block coordinate. */
  public static int of(int block) {
    return Math.floorDiv(block, SIZE);
  }

  /** Both coordinates in one value, so a set of chunk positions can key on a primitive. */
  public static long key(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
  }
}
