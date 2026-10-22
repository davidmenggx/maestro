package io.github.davidmenggx.maestro.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.davidmenggx.maestro.bridge.HeldPaths;
import io.github.davidmenggx.maestro.bridge.PathOffload;
import io.github.davidmenggx.maestro.bridge.Searches;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin implements PathOffload.NavigationAccess {

  @Shadow protected Path path;

  @Shadow protected double speedModifier;

  @Shadow private BlockPos targetPos;

  @Override
  public double maestro$speedModifier() {
    return speedModifier;
  }

  @Override
  public void maestro$setTargetPos(BlockPos target) {
    targetPos = target;
  }

  // The `moveTo` overloads follow the path they search for, so their searches may be offloaded.

  @WrapMethod(method = "moveTo(DDDD)Z")
  private boolean maestro$offloadable(
      double x, double y, double z, double speedModifier, Operation<Boolean> original) {
    Searches.enter();
    try {
      return original.call(x, y, z, speedModifier);
    } finally {
      Searches.leave();
    }
  }

  @WrapMethod(method = "moveTo(DDDID)Z")
  private boolean maestro$offloadable(
      double x,
      double y,
      double z,
      int reachRange,
      double speedModifier,
      Operation<Boolean> original) {
    Searches.enter();
    try {
      return original.call(x, y, z, reachRange, speedModifier);
    } finally {
      Searches.leave();
    }
  }

  @WrapMethod(method = "moveTo(Lnet/minecraft/world/entity/Entity;D)Z")
  private boolean maestro$offloadable(
      Entity target, double speedModifier, Operation<Boolean> original) {
    Searches.enter();
    try {
      return original.call(target, speedModifier);
    } finally {
      Searches.leave();
    }
  }

  /** A held path stands still on purpose, so it is neither stuck nor late to its node. */
  @Inject(method = "doStuckDetection", at = @At("HEAD"), cancellable = true)
  private void maestro$notStuckWhileHeld(Vec3 mobPos, CallbackInfo ci) {
    if (path != null && HeldPaths.isHeld(path)) {
      ci.cancel();
    }
  }

  /** A mob holding in place is not walked to the center of its block. */
  @WrapWithCondition(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/ai/control/MoveControl;setWantedPosition(DDDD)V"))
  private boolean maestro$stayPutWhileHeld(
      MoveControl control, double x, double y, double z, double speedModifier) {
    return path == null || !HeldPaths.isHeld(path);
  }
}
