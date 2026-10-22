package io.github.davidmenggx.maestro.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.davidmenggx.maestro.bridge.Fleet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

  /**
   * Every block change in a loaded chunk passes here. One that changes the block's cell is reported
   * to the fleet, whatever made it.
   */
  @WrapMethod(
      method =
          "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;")
  private BlockState maestro$blockChanged(
      BlockPos pos, BlockState state, int flags, Operation<BlockState> original) {
    LevelChunk chunk = (LevelChunk) (Object) this;
    Fleet fleet = Fleet.active();
    if (fleet == null || !fleet.watches(chunk)) {
      return original.call(pos, state, flags);
    }
    BlockPos at = pos.immutable();
    int before = Fleet.cell(chunk, at);
    BlockState previous = original.call(pos, state, flags);
    if (previous != null) {
      fleet.blockChanged(chunk, at, before);
    }
    return previous;
  }
}
