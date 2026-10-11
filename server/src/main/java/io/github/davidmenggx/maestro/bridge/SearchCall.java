package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * What the server reads from one intercepted {@code PathFinder.findPath} call: the mob's profile,
 * the start node the evaluator resolved, the target and the per-call search inputs. It is read
 * between the evaluator's {@code prepare} and {@code done}, because {@code onPathfindingStart} may
 * change the malus and {@code onPathfindingDone} may change it back.
 */
public record SearchCall(
    Mob mob,
    MobClass.Shape shape,
    MobClass.Abilities abilities,
    float[] malus,
    BlockPos start,
    BlockPos target,
    WorldEvent.Search search) {

  private static final PathType[] TYPES = PathType.values();

  /**
   * Reads the call. {@code maxVisitedNodes} is the budget the search runs with, already scaled by
   * its multiplier. Returns null if an input does not fit the wire, so the search stays vanilla.
   */
  public static SearchCall read(
      Mob mob,
      NodeEvaluator evaluator,
      Node start,
      BlockPos target,
      float maxPathLength,
      int reachRange,
      int maxVisitedNodes) {
    int maxFallDistance = mob.getMaxFallDistance();
    if (reachRange < 0
        || reachRange > WorldEvent.Search.MAX_REACH_RANGE
        || !(maxPathLength > 0)
        || maxVisitedNodes < 1
        || maxVisitedNodes > WorldEvent.Search.MAX_VISITED_NODES
        || maxFallDistance < 0
        || maxFallDistance > WorldEvent.Search.MAX_FALL_DISTANCE) {
      return null;
    }

    float[] malus = new float[PathTypes.count()];
    for (PathType type : TYPES) {
      malus[type.ordinal()] = mob.getPathfindingMalus(type);
    }
    return new SearchCall(
        mob,
        new MobClass.Shape(mob.getBbWidth(), mob.getBbHeight(), mob.maxUpStep()),
        new MobClass.Abilities(
            evaluator.canPassDoors(),
            evaluator.canOpenDoors(),
            evaluator.canFloat(),
            evaluator.canWalkOverFences()),
        malus,
        new BlockPos(start.x, start.y, start.z),
        target.immutable(),
        new WorldEvent.Search(reachRange, maxPathLength, maxVisitedNodes, maxFallDistance));
  }
}
