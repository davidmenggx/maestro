package io.github.davidmenggx.maestro.mixin;

import io.github.davidmenggx.maestro.capture.ApproximationProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports vanilla's collision and floor answers to the {@link ApproximationProbe}, which does
 * nothing unless a capture is running.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {

  @Inject(method = "hasCollisions", at = @At("RETURN"))
  private void maestro$probeCollision(AABB box, CallbackInfoReturnable<Boolean> cir) {
    if ((Object) this.getClass() == WalkNodeEvaluator.class) {
      ApproximationProbe.collision(box, cir.getReturnValueZ());
    }
  }

  @Inject(method = "getFloorLevel(Lnet/minecraft/core/BlockPos;)D", at = @At("RETURN"))
  private void maestro$probeFloor(BlockPos pos, CallbackInfoReturnable<Double> cir) {
    if ((Object) this.getClass() == WalkNodeEvaluator.class) {
      ApproximationProbe.floor(
          pos, ((NodeEvaluator) (Object) this).canFloat(), cir.getReturnValueD());
    }
  }
}
