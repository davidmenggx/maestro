package com.smpler.maestro.mixin;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PortalShape.class)
public abstract class PortalShapeMixin {

  /** Reporting no ignitable frame stops nether portals from ever forming. */
  @Inject(method = "findEmptyPortalShape", at = @At("HEAD"), cancellable = true)
  private static void maestro$refuseNetherPortals(
      LevelAccessor level,
      BlockPos pos,
      Direction.Axis axis,
      CallbackInfoReturnable<Optional<PortalShape>> cir) {
    cir.setReturnValue(Optional.empty());
  }
}
