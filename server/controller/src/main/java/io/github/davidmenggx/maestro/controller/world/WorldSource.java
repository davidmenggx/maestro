package io.github.davidmenggx.maestro.controller.world;

/**
 * Pull side of the world boundary, read on the main thread when a region snapshot is built.
 * Production reads loaded chunks; the simulator reads its own terrain model.
 *
 * <p>Chunk residency is not asked for here. It arrives as {@link WorldEvent.ChunkLoaded} and {@link
 * WorldEvent.ChunkUnloaded}, so the controller already knows which regions are readable and there
 * is no second answer to disagree with.
 */
public interface WorldSource {

  /** Lowest block y in the dimension. */
  int minY();

  /** Block count from {@link #minY} upwards. Columns are carried at full height. */
  int height();

  /**
   * Per-block {@code PathType} ordinal at the position, matching what a {@link
   * WorldEvent.BlockChanged} would carry. Positions outside a loaded chunk read as {@code BLOCKED}.
   */
  byte pathTypeAt(int x, int y, int z);
}
