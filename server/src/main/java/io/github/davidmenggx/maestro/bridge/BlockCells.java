package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.Cells;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.shapes.VoxelShape;

/** One block as a wire cell: its per-block {@code PathType} and its collision height. */
public final class BlockCells {

  private BlockCells() {}

  /** {@code getPathTypeFromState} at the position, as an ordinal of the shared enum. */
  public static byte pathType(BlockGetter level, BlockPos pos) {
    return (byte) StateTypes.of(level, pos).ordinal();
  }

  /**
   * Top of the block's collision shape in sixteenths, rounded up and capped at {@link
   * Cells#MAX_FLOOR}, or 0 if it has none. This is the shape {@code getFloorLevel} reads.
   */
  public static int floor(BlockGetter level, BlockPos pos) {
    VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
    if (shape.isEmpty()) {
      return 0;
    }
    return Math.min(
        Cells.MAX_FLOOR, (int) Math.ceil(shape.max(Direction.Axis.Y) * Cells.FULL_FLOOR));
  }

  /** Reaches the protected {@code getPathTypeFromState} without a mixin. */
  private abstract static class StateTypes extends WalkNodeEvaluator {

    static PathType of(BlockGetter level, BlockPos pos) {
      return getPathTypeFromState(level, pos);
    }
  }
}
