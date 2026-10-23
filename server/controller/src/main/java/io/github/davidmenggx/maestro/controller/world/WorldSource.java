package io.github.davidmenggx.maestro.controller.world;

/**
 * Pull side of the world boundary, read on the main thread when a chunk's state is built.
 *
 * <p>Residency comes from {@link WorldEvent.ChunkLoaded} and {@link WorldEvent.ChunkUnloaded}, not
 * from here. Cells are read only inside a loaded chunk. {@link #readable} only guards a chunk that
 * is loaded but about to leave.
 */
public interface WorldSource {

  /** The profile a request names, or null if it has not been interned. */
  MobClass mobClass(short classId);

  /** Lowest block y in the dimension. */
  int minY();

  /** Number of blocks from {@link #minY} up. Columns are always full height. */
  int height();

  /** Per-block {@code PathType} ordinal, as a {@link WorldEvent.BlockChanged} would carry. */
  byte pathTypeAt(int x, int y, int z);

  /** Top of the collision shape in sixteenths, as a {@link WorldEvent.BlockChanged} would carry. */
  int floorAt(int x, int y, int z);

  /** Blocks in one section, the unit of {@link #uniformCell} and {@link #readSection}. */
  int SECTION_HEIGHT = 16;

  /** Returned by {@link #uniformCell} for a section that is not known to be uniform. */
  int NOT_UNIFORM = -1;

  /**
   * Whether the chunk's blocks can be read now without loading it. A chunk that cannot is read
   * again later and not sent meanwhile.
   */
  default boolean readable(int chunkX, int chunkZ) {
    return true;
  }

  /**
   * The {@link Cells#pack packed} cell shared by every block of a section, or {@link #NOT_UNIFORM}
   * if they differ or the source cannot tell cheaply. Section {@code n} covers {@link
   * #SECTION_HEIGHT} blocks from {@code minY() + n * SECTION_HEIGHT}. Must agree with {@link
   * #pathTypeAt} and {@link #floorAt}.
   */
  default int uniformCell(int chunkX, int chunkZ, int section) {
    return NOT_UNIFORM;
  }

  /**
   * Reads a section's {@link Cells#pack packed} cells into {@code cells} at {@code (dx *
   * Chunks.SIZE + dz) * SECTION_HEIGHT + dy} and returns true, or returns false if this source
   * reads blocks one at a time. Must agree with {@link #pathTypeAt} and {@link #floorAt}.
   */
  default boolean readSection(int chunkX, int chunkZ, int section, int[] cells) {
    return false;
  }
}
