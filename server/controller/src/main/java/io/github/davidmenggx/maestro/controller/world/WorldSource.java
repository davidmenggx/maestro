package io.github.davidmenggx.maestro.controller.world;

/**
 * Pull side of the world boundary, read on the main thread when a chunk's state is built.
 * Production reads loaded chunks; the simulator reads its own terrain model.
 *
 * <p>Chunk residency is not asked for here. It arrives as {@link WorldEvent.ChunkLoaded} and {@link
 * WorldEvent.ChunkUnloaded}, so the controller already knows which chunks are readable and there is
 * no second answer to disagree with. Cells are only read inside a loaded chunk: one that is not
 * loaded is sent as cleared, and the node reads it as air.
 */
public interface WorldSource {

  /**
   * The profile a request names, or null if no such profile has been interned. The evaluator inputs
   * are live per-mob state rather than per-type constants, so a profile is interned when first seen
   * and its id names that content for the rest of the run.
   */
  MobClass mobClass(short classId);

  /** Lowest block y in the dimension. */
  int minY();

  /** Block count from {@link #minY} upwards. Columns are carried at full height. */
  int height();

  /**
   * Per-block {@code PathType} ordinal at the position, matching what a {@link
   * WorldEvent.BlockChanged} would carry.
   */
  byte pathTypeAt(int x, int y, int z);

  /**
   * Top of the block's collision shape in sixteenths, matching what a {@link
   * WorldEvent.BlockChanged} would carry.
   */
  int floorAt(int x, int y, int z);
}
