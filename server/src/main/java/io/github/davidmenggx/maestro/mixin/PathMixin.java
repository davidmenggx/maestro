package io.github.davidmenggx.maestro.mixin;

import io.github.davidmenggx.maestro.bridge.HeldPaths;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets a path be held in place, and dates it. See {@link HeldPaths}. */
@Mixin(Path.class)
public abstract class PathMixin implements HeldPaths.Marks {

  @Unique private long maestro$heldUntil = HeldPaths.NOT_HELD;

  @Unique private long maestro$madeAt = HeldPaths.UNKNOWN;

  @Override
  public long maestro$heldUntil() {
    return maestro$heldUntil;
  }

  @Override
  public void maestro$setHeldUntil(long tick) {
    maestro$heldUntil = tick;
  }

  @Override
  public long maestro$madeAt() {
    return maestro$madeAt;
  }

  @Inject(method = "<init>", at = @At("RETURN"))
  private void maestro$date(CallbackInfo ci) {
    maestro$madeAt = HeldPaths.now();
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
