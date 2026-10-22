package io.github.davidmenggx.maestro.mixin;

import io.github.davidmenggx.maestro.bridge.HeldPaths;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets a path be held in place. See {@link HeldPaths}. */
@Mixin(Path.class)
public abstract class PathMixin implements HeldPaths.Holdable {

  @Unique private long maestro$heldUntil = HeldPaths.NOT_HELD;

  @Override
  public long maestro$heldUntil() {
    return maestro$heldUntil;
  }

  @Override
  public void maestro$setHeldUntil(long tick) {
    maestro$heldUntil = tick;
  }

  @Inject(method = "isDone", at = @At("HEAD"), cancellable = true)
  private void maestro$notDoneWhileHeld(CallbackInfoReturnable<Boolean> cir) {
    if (maestro$heldUntil != HeldPaths.NOT_HELD && HeldPaths.isHeld((Path) (Object) this)) {
      cir.setReturnValue(false);
    }
  }

  @Inject(method = "advance", at = @At("HEAD"), cancellable = true)
  private void maestro$stayWhileHeld(CallbackInfo ci) {
    if (maestro$heldUntil != HeldPaths.NOT_HELD && HeldPaths.isHeld((Path) (Object) this)) {
      ci.cancel();
    }
  }
}
