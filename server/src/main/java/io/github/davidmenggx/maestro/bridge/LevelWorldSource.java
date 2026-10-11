package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/** The world boundary's pull side over a Minecraft level. Read on the server thread only. */
public final class LevelWorldSource implements WorldSource {

  private final BlockGetter level;
  private final ProfileTable profiles;
  private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

  public LevelWorldSource(BlockGetter level, ProfileTable profiles) {
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
}
