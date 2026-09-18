package io.github.davidmenggx.maestro.controller.world;

import java.util.List;

/**
 * Pull side of the world boundary, read on the main thread when a region snapshot is built.
 * Production reads loaded chunks; the simulator reads its own terrain model.
 *
 * <p>Chunk residency is not asked for here. It arrives as {@link WorldEvent.ChunkLoaded} and {@link
 * WorldEvent.ChunkUnloaded}, so the controller already knows which regions are readable and there
 * is no second answer to disagree with.
 */
public interface WorldSource {

  /**
   * Every land-mob profile a request may name. Fixed after startup, so the controller reads it once
   * per node and sends it before any request that references a class.
   */
  List<MobClass> mobClasses();

  /** Lowest block y in the dimension. */
  int minY();

  /** Block count from {@link #minY} upwards. Columns are carried at full height. */
  int height();

  /**
   * Per-block {@code PathType} ordinal at the position, matching what a {@link
   * WorldEvent.BlockChanged} would carry. Positions outside a loaded chunk read as {@code BLOCKED}.
   */
  byte pathTypeAt(int x, int y, int z);

  /**
   * Top of the block's collision shape in sixteenths, matching what a {@link
   * WorldEvent.BlockChanged} would carry. Positions outside a loaded chunk read as a full cube.
   */
  int floorAt(int x, int y, int z);
}
