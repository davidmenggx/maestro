package io.github.davidmenggx.maestro.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.davidmenggx.maestro.bridge.Searches;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WallClimberNavigation.class)
public abstract class WallClimberNavigationMixin {

  /**
   * Overrides the navigation's {@code moveTo(Entity, double)}, so {@link PathNavigationMixin} does
   * not wrap it. It follows the path it searches for, so its search may be offloaded.
   */
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
}
