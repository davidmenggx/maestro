package io.github.davidmenggx.maestro.controller.world;

/**
 * Pull side of the world boundary, read on the main thread when a chunk's state is built.
 * Production reads loaded chunks; the simulator reads its own terrain model.
 *
 * <p>Chunk residency is not queried here. It comes from {@link WorldEvent.ChunkLoaded} and {@link
 * WorldEvent.ChunkUnloaded}, so the controller already knows which chunks are readable and there is
 * no second source that could disagree. Cells are read only inside a loaded chunk. An unloaded
 * chunk is sent as cleared, and the node treats it as air.
 */
public interface WorldSource {

  /**
   * The profile a request names, or null if it has not been interned. The evaluator inputs change
   * at runtime per mob, so a profile is interned when first seen, and its id names that content for
   * the rest of the run.
   */
  MobClass mobClass(short classId);

  /** Lowest block y in the dimension. */
  int minY();

  /** Number of blocks from {@link #minY} up. Columns are always full height. */
  int height();

  /**
   * Per-block {@code PathType} ordinal at the position, the same as a {@link
   * WorldEvent.BlockChanged} would carry.
   */
  byte pathTypeAt(int x, int y, int z);

  /**
   * Top of the block's collision shape in sixteenths, rounded up, the same as a {@link
   * WorldEvent.BlockChanged} would carry.
   */
  int floorAt(int x, int y, int z);
}
