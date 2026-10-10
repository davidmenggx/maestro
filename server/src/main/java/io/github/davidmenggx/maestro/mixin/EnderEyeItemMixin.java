package io.github.davidmenggx.maestro.mixin;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnderEyeItem.class)
public abstract class EnderEyeItemMixin {

  /**
   * `useOn` places an eye in an end portal frame and completes the portal. Throwing eyes to locate
   * strongholds still works; only filling the frame is blocked.
   */
  @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
  private void maestro$refuseEndPortals(
      UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
    cir.setReturnValue(InteractionResult.PASS);
  }
}
