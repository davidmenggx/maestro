package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import net.minecraft.core.BlockPos;
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

  public LevelWorldSource(LevelReader level, ProfileTable profiles) {
    this.level = level;
    this.profiles = profiles;
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

  /**
   * Reads the section's palette instead of its blocks. A section of air, or one holding a single
   * block state, has one cell. The cell of a single state is read at one block, which is the same
   * for every block since both values come from the state alone. A chunk that is not loaded is left
   * to the block reads, which only ever happen in a loaded chunk.
   */
  @Override
  public int uniformCell(int chunkX, int chunkZ, int section) {
    ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
    if (chunk == null) {
      return NOT_UNIFORM;
    }
    LevelChunkSection blocks = chunk.getSection(section);
    if (blocks.hasOnlyAir()) {
      return AIR;
    }
    BlockState[] first = new BlockState[1];
    boolean[] single = {true};
    blocks
        .getStates()
        .getAll(
            state -> {
              if (first[0] == null) {
                first[0] = state;
              } else if (first[0] != state) {
                single[0] = false;
              }
            });
    if (!single[0]) {
      return NOT_UNIFORM;
    }
    int x = chunkX * 16;
    int y = minY() + section * SECTION_HEIGHT;
    int z = chunkZ * 16;
    return (floorAt(x, y, z) << 8) | (pathTypeAt(x, y, z) & 0xFF);
  }
}
