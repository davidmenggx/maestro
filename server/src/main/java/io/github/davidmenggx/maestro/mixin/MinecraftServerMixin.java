package io.github.davidmenggx.maestro.mixin;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

  private static final Logger LOG = LoggerFactory.getLogger("maestro");

  /**
   * {@code createLevels} iterates the {@code LEVEL_STEM} registry to create every other dimension
   * and set up each border, the overworld's included. Iterating only the overworld's entry keeps
   * its border and creates no other dimension.
   */
  @Redirect(
      method = "createLevels",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Registry;entrySet()Ljava/util/Set;"))
  private Set<Map.Entry<ResourceKey<LevelStem>, LevelStem>> maestro$overworldOnly(
      Registry<LevelStem> registry) {
    return registry.entrySet().stream()
        .filter(entry -> entry.getKey().equals(LevelStem.OVERWORLD))
        .collect(Collectors.toUnmodifiableSet());
  }

  @Inject(method = "loadLevel", at = @At("TAIL"))
  private void maestro$afterLoadLevel(CallbackInfo ci) {
    LOG.info("Maestro mixin applied and level loaded");
  }
}
