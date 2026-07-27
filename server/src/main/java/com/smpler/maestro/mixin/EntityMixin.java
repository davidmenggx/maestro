package com.smpler.maestro.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {

  /**
   * Every cross-dimension move funnels through `teleport`, so refusing non-overworld destinations
   * here covers portals, end gateways and command teleports alike. A `null` return is vanilla's own
   * "the teleport did not happen" contract.
   */
  @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
  private void maestro$refuseOtherDimensions(
      TeleportTransition transition, CallbackInfoReturnable<Entity> cir) {
    if (transition.newLevel().dimension() != Level.OVERWORLD) {
      cir.setReturnValue(null);
    }
  }
}
