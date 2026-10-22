package io.github.davidmenggx.maestro.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.davidmenggx.maestro.bridge.Searches;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MeleeAttackGoal.class)
public abstract class MeleeAttackGoalMixin {

  /**
   * The goal checks only that its path exists before it starts and moves along it, so its search
   * may be offloaded.
   */
  @WrapMethod(method = "canUse")
  private boolean maestro$offloadable(Operation<Boolean> original) {
    Searches.enter();
    try {
      return original.call();
    } finally {
      Searches.leave();
    }
  }
}
