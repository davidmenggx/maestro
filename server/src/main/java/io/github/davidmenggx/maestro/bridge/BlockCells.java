package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.Cells;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
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
   * Top of the block's collision shape in sixteenths, rounded up and clamped to 0 through {@link
   * Cells#MAX_FLOOR}, or 0 if it has none. A block a piston is moving may lie below or above its
   * cell.
   */
  public static int floor(BlockGetter level, BlockPos pos) {
    return floor(level.getBlockState(pos), level, pos);
  }

  /** {@link #floor(BlockGetter, BlockPos)} where {@code state} is the block at the position. */
  static int floor(BlockState state, BlockGetter level, BlockPos pos) {
    VoxelShape shape = state.getCollisionShape(level, pos);
    if (shape.isEmpty()) {
      return 0;
    }
    return (int)
        Math.clamp(Math.ceil(shape.max(Direction.Axis.Y) * Cells.FULL_FLOOR), 0, Cells.MAX_FLOOR);
  }

  /** Reaches the protected {@code getPathTypeFromState} without a mixin. */
  private abstract static class StateTypes extends WalkNodeEvaluator {

    static PathType of(BlockGetter level, BlockPos pos) {
      return getPathTypeFromState(level, pos);
    }
  }
}
