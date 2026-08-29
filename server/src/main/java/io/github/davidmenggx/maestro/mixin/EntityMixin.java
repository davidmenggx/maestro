package io.github.davidmenggx.maestro.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {

  @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
  private void maestro$refuseOtherDimensions(
      TeleportTransition transition, CallbackInfoReturnable<Entity> cir) {
    if (transition.newLevel().dimension() != Level.OVERWORLD) {
      cir.setReturnValue(null);
    }
  }
}
