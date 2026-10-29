package io.github.davidmenggx.maestro.gametest.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameTestServer.class)
public abstract class GameTestServerMixin {

  /** Lays the tests out from the origin, inside the square the fleet covers, not at random. */
  @Redirect(
      method = "startTests",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/util/RandomSource;nextIntBetweenInclusive(II)I"))
  private int maestro$fromTheOrigin(RandomSource random, int min, int max) {
    return 0;
  }
}
