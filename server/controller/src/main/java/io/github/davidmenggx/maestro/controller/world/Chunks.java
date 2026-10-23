package io.github.davidmenggx.maestro.controller.world;

/** Chunk geometry. Regions are chunk-aligned, so world state moves one chunk at a time. */
public final class Chunks {

  /** Block width of a chunk along both horizontal axes. */
  public static final int SIZE = 16;

  private Chunks() {}

  /** Chunk coordinate holding a block coordinate. */
  public static int of(int block) {
    return Math.floorDiv(block, SIZE);
  }

  /** Both coordinates packed in one primitive key. */
  public static long key(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
  }
}
