package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.NodeHashes;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * What the server reads from one intercepted {@code PathFinder.findPath} call: the mob's interned
 * profile, the start node the evaluator resolved, the target and the per-call search inputs. It is
 * read between the evaluator's {@code prepare} and {@code done}, because {@code onPathfindingStart}
 * may change the malus and {@code onPathfindingDone} may change it back.
 */
public record SearchCall(
    Mob mob, MobClass profile, BlockPos start, BlockPos target, WorldEvent.Search search) {

  private static final PathType[] TYPES = PathType.values();

  /**
   * Reads the call and interns the mob's profile in {@code profiles}. {@code maxVisitedNodes} is
   * the budget the search runs with, already scaled by its multiplier. {@code scratch} is a buffer
   * of {@link PathTypes#count()} floats that the malus is read into; nothing keeps it. Returns null
   * if an input does not fit the wire, if two of the search's nodes could share vanilla's node hash
   * ({@link NodeHashes}), or if the profile ids are exhausted, so the search stays vanilla.
   */
  public static SearchCall read(
      Mob mob,
      NodeEvaluator evaluator,
      Node start,
      BlockPos target,
      float maxPathLength,
      int reachRange,
      int maxVisitedNodes,
      ProfileTable profiles,
      float[] scratch) {
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
    if (NodeHashes.mayCollide(
        new Pos(start.x, start.y, start.z),
        new Pos(target.getX(), target.getY(), target.getZ()),
        maxPathLength,
        maxFallDistance,
        mob.maxUpStep(),
        evaluator.canFloat(),
        mob.level().getMinY())) {
      return null;
    }

    for (PathType type : TYPES) {
      scratch[type.ordinal()] = mob.getPathfindingMalus(type);
    }
    MobClass profile =
        profiles.intern(
            new MobClass.Shape(mob.getBbWidth(), mob.getBbHeight(), mob.maxUpStep()),
            new MobClass.Abilities(
                evaluator.canPassDoors(),
                evaluator.canOpenDoors(),
                evaluator.canFloat(),
                evaluator.canWalkOverFences()),
            scratch);
    if (profile == null) {
      return null;
    }
    return new SearchCall(
        mob,
        profile,
        new BlockPos(start.x, start.y, start.z),
        target.immutable(),
        new WorldEvent.Search(reachRange, maxPathLength, maxVisitedNodes, maxFallDistance));
  }
}
