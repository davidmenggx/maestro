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

  /** Blocks in one section, the unit of {@link #uniformCell} and {@link #readSection}. */
  int SECTION_HEIGHT = 16;

  /** Returned by {@link #uniformCell} for a section that is not known to be uniform. */
  int NOT_UNIFORM = -1;

  /**
   * The cell, as {@code (floor << 8) | pathType}, shared by every block of one section of the
   * chunk, or {@link #NOT_UNIFORM} if the blocks differ or the source cannot tell cheaply. Section
   * {@code n} covers the {@link #SECTION_HEIGHT} blocks from {@code minY() + n * SECTION_HEIGHT}.
   * Must agree with {@link #pathTypeAt} and {@link #floorAt} for every block in the section. It
   * only saves reading them one at a time, so the default knows nothing.
   */
  default int uniformCell(int chunkX, int chunkZ, int section) {
    return NOT_UNIFORM;
  }

  /**
   * Reads every block of one section as a cell, {@code (floor << 8) | pathType}, into {@code cells}
   * at {@code (dx * Chunks.SIZE + dz) * SECTION_HEIGHT + dy}, and returns true, or returns false,
   * leaving {@code cells} undefined, if this source reads blocks one at a time. Must agree with
   * {@link #pathTypeAt} and {@link #floorAt} for every block of the section.
   */
  default boolean readSection(int chunkX, int chunkZ, int section, int[] cells) {
    return false;
  }
}
