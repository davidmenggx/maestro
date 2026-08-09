package com.smpler.maestro.controller.world;

/**
 * Pull side of the world boundary, read on the main thread when a region snapshot is built.
 * Production reads loaded chunks; the simulator reads its own terrain model.
 */
public interface WorldSource {

  /** Lowest block y in the dimension. */
  int minY();

  /** Block count from {@link #minY} upwards. Columns are carried at full height. */
  int height();

  boolean chunkLoaded(int chunkX, int chunkZ);

  /**
   * Per-block {@code PathType} ordinal at the position, matching what a {@link
   * WorldEvent.BlockChanged} would carry. Unloaded chunks read as {@code BLOCKED}.
   */
  byte pathTypeAt(int x, int y, int z);
}
