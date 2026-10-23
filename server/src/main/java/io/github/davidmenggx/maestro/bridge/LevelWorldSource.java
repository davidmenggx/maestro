package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The world boundary's pull side over a Minecraft level. Read on the server thread only.
 *
 * <p>A read never loads a chunk. A chunk not loaded at {@code FULL} status reads as air, as the
 * node holds it.
 */
public final class LevelWorldSource implements WorldSource {

  /** A loaded chunk at {@code FULL} status, or null. Never loads one. */
  @FunctionalInterface
  interface LoadedChunks {
    ChunkAccess get(int chunkX, int chunkZ);
  }

  /** The cell of air: no collision height, and the {@code OPEN} type. */
  private static final int AIR = Cells.pack(0, (byte) maestro.fbs.PathType.OPEN);

  private final LevelHeightAccessor heights;
  private final LoadedChunks chunks;
  private final ProfileTable profiles;
  private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
  private final Sections sections = new Sections();

  public LevelWorldSource(ServerLevel level, ProfileTable profiles) {
    this(level, level.getChunkSource()::getChunkNow, profiles);
  }

  LevelWorldSource(LevelHeightAccessor heights, LoadedChunks chunks, ProfileTable profiles) {
    this.heights = heights;
    this.chunks = chunks;
    this.profiles = profiles;
  }

  @Override
  public MobClass mobClass(short classId) {
    return profiles.get(classId);
  }

  @Override
  public int minY() {
    return heights.getMinY();
  }

  @Override
  public int height() {
    return heights.getHeight();
  }

  @Override
  public boolean readable(int chunkX, int chunkZ) {
    return chunks.get(chunkX, chunkZ) != null;
  }

  @Override
  public byte pathTypeAt(int x, int y, int z) {
    ChunkAccess chunk = chunks.get(Chunks.of(x), Chunks.of(z));
    return chunk == null ? Cells.typeOf(AIR) : BlockCells.pathType(chunk, cursor.set(x, y, z));
  }

  @Override
  public int floorAt(int x, int y, int z) {
    ChunkAccess chunk = chunks.get(Chunks.of(x), Chunks.of(z));
    return chunk == null ? Cells.floorOf(AIR) : BlockCells.floor(chunk, cursor.set(x, y, z));
  }

  @Override
  public int uniformCell(int chunkX, int chunkZ, int section) {
    ChunkAccess chunk = chunks.get(chunkX, chunkZ);
    if (chunk == null) {
      return AIR;
    }
    return sections.uniformCell(
        chunk,
        chunk.getSection(section),
        chunkX * Chunks.SIZE,
        minY() + section * SECTION_HEIGHT,
        chunkZ * Chunks.SIZE);
  }

  /** Returns false for a chunk that is not loaded, which reads as air block by block. */
  @Override
  public boolean readSection(int chunkX, int chunkZ, int section, int[] cells) {
    ChunkAccess chunk = chunks.get(chunkX, chunkZ);
    if (chunk == null) {
      return false;
    }
    sections.read(
        chunk,
        chunk.getSection(section),
        chunkX * Chunks.SIZE,
        minY() + section * SECTION_HEIGHT,
        chunkZ * Chunks.SIZE,
        cells);
    return true;
  }

  /**
   * Reads a section's cells from its palette instead of block by block. A block's type and floor
   * are functions of its state, except for dynamic shapes (shulker boxes, moving pistons), whose
   * shape may read the position or block entity. So each distinct state is read once per section,
   * and only a dynamic shape's floor is read per block.
   */
  static final class Sections {

    private static final int UNSEEN = -1;

    /** Marks a state whose floor is read per block. Its type is in the low byte. */
    private static final int FLOOR_PER_BLOCK = 1 << 30;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final Reference2IntOpenHashMap<BlockState> cellsByState =
        new Reference2IntOpenHashMap<>();

    Sections() {
      cellsByState.defaultReturnValue(UNSEEN);
    }

    /**
     * Uniform if the section is air, or its palette holds a single state whose floor is not read
     * per block. A palette can list a state no block has any more, so that is not uniform.
     */
    int uniformCell(BlockGetter level, LevelChunkSection blocks, int minX, int minY, int minZ) {
      if (blocks.hasOnlyAir()) {
        return AIR;
      }
      BlockState first = blocks.getBlockState(0, 0, 0);
      if (first.getBlock().hasDynamicShape() || blocks.maybeHas(state -> state != first)) {
        return NOT_UNIFORM;
      }
      return firstCell(level, first, cursor.set(minX, minY, minZ));
    }

    /** Reads the section with lowest corner {@code (minX, minY, minZ)} into {@code cells}. */
    void read(
        BlockGetter level, LevelChunkSection blocks, int minX, int minY, int minZ, int[] cells) {
      cellsByState.clear();
      BlockState last = null;
      int lastCell = UNSEEN;
      for (int dx = 0; dx < Chunks.SIZE; dx++) {
        for (int dz = 0; dz < Chunks.SIZE; dz++) {
          int first = (dx * Chunks.SIZE + dz) * SECTION_HEIGHT;
          for (int dy = 0; dy < SECTION_HEIGHT; dy++) {
            BlockState state = blocks.getBlockState(dx, dy, dz);
            if (state != last) {
              last = state;
              lastCell = cellsByState.getInt(state);
              if (lastCell == UNSEEN) {
                lastCell = firstCell(level, state, cursor.set(minX + dx, minY + dy, minZ + dz));
                cellsByState.put(state, lastCell);
              }
            }
            if ((lastCell & FLOOR_PER_BLOCK) == 0) {
              cells[first + dy] = lastCell;
            } else {
              cursor.set(minX + dx, minY + dy, minZ + dz);
              cells[first + dy] =
                  Cells.pack(BlockCells.floor(state, level, cursor), Cells.typeOf(lastCell));
            }
          }
        }
      }
    }

    /**
     * The cell of {@code state}, read at {@code pos}, which holds it. If the state's floor is read
     * per block, its type marked {@link #FLOOR_PER_BLOCK} instead.
     */
    private static int firstCell(BlockGetter level, BlockState state, BlockPos pos) {
      byte type = BlockCells.pathType(level, pos);
      if (state.getBlock().hasDynamicShape()) {
        return FLOOR_PER_BLOCK | Cells.pack(0, type);
      }
      return Cells.pack(BlockCells.floor(state, level, pos), type);
    }
  }
}
