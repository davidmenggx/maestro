package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** The world boundary's pull side over a Minecraft level. Read on the server thread only. */
public final class LevelWorldSource implements WorldSource {

  /** The cell of air: no collision height, and the {@code OPEN} type. */
  private static final int AIR = maestro.fbs.PathType.OPEN;

  private final LevelReader level;
  private final ProfileTable profiles;
  private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
  private final Sections sections;

  public LevelWorldSource(LevelReader level, ProfileTable profiles) {
    this.level = level;
    this.profiles = profiles;
    this.sections = new Sections(level);
  }

  @Override
  public MobClass mobClass(short classId) {
    return profiles.get(classId);
  }

  @Override
  public int minY() {
    return level.getMinY();
  }

  @Override
  public int height() {
    return level.getHeight();
  }

  @Override
  public byte pathTypeAt(int x, int y, int z) {
    return BlockCells.pathType(level, cursor.set(x, y, z));
  }

  @Override
  public int floorAt(int x, int y, int z) {
    return BlockCells.floor(level, cursor.set(x, y, z));
  }

  @Override
  public int uniformCell(int chunkX, int chunkZ, int section) {
    LevelChunkSection blocks = section(chunkX, chunkZ, section);
    if (blocks == null) {
      return NOT_UNIFORM;
    }
    return sections.uniformCell(
        blocks, chunkX * Chunks.SIZE, minY() + section * SECTION_HEIGHT, chunkZ * Chunks.SIZE);
  }

  @Override
  public boolean readSection(int chunkX, int chunkZ, int section, int[] cells) {
    LevelChunkSection blocks = section(chunkX, chunkZ, section);
    if (blocks == null) {
      return false;
    }
    sections.read(
        blocks,
        chunkX * Chunks.SIZE,
        minY() + section * SECTION_HEIGHT,
        chunkZ * Chunks.SIZE,
        cells);
    return true;
  }

  /**
   * The section, or null if its chunk is not loaded. Such a chunk is left to the block reads, which
   * only ever happen in a loaded chunk.
   */
  private LevelChunkSection section(int chunkX, int chunkZ, int section) {
    ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
    return chunk == null ? null : chunk.getSection(section);
  }

  /**
   * Reads a section's cells from its palette instead of through the level. A block's type is a
   * function of its state, and so is its floor unless the block has a dynamic shape, whose
   * collision shape is not cached per state and may read the position or a block entity (shulker
   * boxes and moving pistons read their block entity). So each distinct state is read once per
   * section, at the first block that has it, and only a dynamic shape's floor is read per block.
   */
  static final class Sections {

    private static final int UNSEEN = -1;

    /** Marks a state whose floor is read per block. Its type is in the low byte. */
    private static final int FLOOR_PER_BLOCK = 1 << 30;

    private final BlockGetter level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final Reference2IntOpenHashMap<BlockState> cellsByState =
        new Reference2IntOpenHashMap<>();

    Sections(BlockGetter level) {
      this.level = level;
      cellsByState.defaultReturnValue(UNSEEN);
    }

    /**
     * Uniform if the section is air, or its palette holds a single state whose floor is not read
     * per block. A palette can still list a state no block has any more, so such a section is not
     * reported uniform. {@code (minX, minY, minZ)} is its lowest corner.
     */
    int uniformCell(LevelChunkSection blocks, int minX, int minY, int minZ) {
      if (blocks.hasOnlyAir()) {
        return AIR;
      }
      BlockState first = blocks.getBlockState(0, 0, 0);
      if (first.getBlock().hasDynamicShape() || blocks.maybeHas(state -> state != first)) {
        return NOT_UNIFORM;
      }
      return firstCell(first, cursor.set(minX, minY, minZ));
    }

    /** Reads the section with lowest corner {@code (minX, minY, minZ)} into {@code cells}. */
    void read(LevelChunkSection blocks, int minX, int minY, int minZ, int[] cells) {
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
                lastCell = firstCell(state, cursor.set(minX + dx, minY + dy, minZ + dz));
                cellsByState.put(state, lastCell);
              }
            }
            if ((lastCell & FLOOR_PER_BLOCK) == 0) {
              cells[first + dy] = lastCell;
            } else {
              cursor.set(minX + dx, minY + dy, minZ + dz);
              cells[first + dy] = (BlockCells.floor(state, level, cursor) << 8) | (lastCell & 0xFF);
            }
          }
        }
      }
    }

    /**
     * The cell of {@code state}, read at {@code pos}, which holds it. If the state's floor is read
     * per block, its type marked {@link #FLOOR_PER_BLOCK} instead.
     */
    private int firstCell(BlockState state, BlockPos pos) {
      int type = BlockCells.pathType(level, pos) & 0xFF;
      if (state.getBlock().hasDynamicShape()) {
        return FLOOR_PER_BLOCK | type;
      }
      return (BlockCells.floor(state, level, pos) << 8) | type;
    }
  }
}
