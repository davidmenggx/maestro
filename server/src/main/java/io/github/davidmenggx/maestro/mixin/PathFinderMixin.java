package io.github.davidmenggx.maestro.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.davidmenggx.maestro.bridge.SearchCall;
import io.github.davidmenggx.maestro.bridge.Searches;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Target;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PathFinder.class)
public abstract class PathFinderMixin {

  @Shadow @Final private NodeEvaluator nodeEvaluator;

  @Shadow private int maxVisitedNodes;

  /**
   * Wraps the search itself, which runs after the evaluator's {@code prepare} has resolved the
   * start node and before its {@code done}, so the mob's profile is read as the search sees it.
   */
  @WrapOperation(
      method =
          "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath(Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;"))
  private Path maestro$search(
      PathFinder finder,
      Node from,
      Map<Target, BlockPos> targets,
      float maxPathLength,
      int reachRange,
      float maxVisitedNodesMultiplier,
      Operation<Path> search,
      @Local(argsOnly = true) Mob mob) {
    Path vanilla =
        search.call(finder, from, targets, maxPathLength, reachRange, maxVisitedNodesMultiplier);
    Searches.Observer observer = Searches.observer();
    if (observer == null || !Searches.intercepted(finder, nodeEvaluator, targets.size())) {
      return vanilla;
    }
    // The budget as vanilla computes it, float multiply included.
    int budget = (int) (maxVisitedNodes * maxVisitedNodesMultiplier);
    BlockPos target = targets.values().iterator().next();
    SearchCall call =
        SearchCall.read(mob, nodeEvaluator, from, target, maxPathLength, reachRange, budget);
    if (call != null) {
      observer.searched(call, vanilla);
    }
    return vanilla;
  }
}
