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
 * What the server reads from one intercepted {@code PathFinder.findPath} call: the interned
 * profile, the resolved start node, the target, the per-call inputs, and whether the mob is hunting
 * (has a target). Read between the evaluator's {@code prepare} and {@code done}, since {@code
 * onPathfindingStart} may change malus.
 */
public record SearchCall(
    MobClass profile, BlockPos start, BlockPos target, WorldEvent.Search search, boolean hunting) {

  private static final PathType[] TYPES = PathType.values();

  /**
   * Reads the call and interns the mob's profile in {@code profiles}. {@code maxVisitedNodes} is
   * the budget already scaled by its multiplier, and {@code minY} is the world's floor. {@code
   * scratch} holds {@link PathTypes#count()} floats for the malus and is not kept. Returns null, so
   * the search stays vanilla, if an input does not fit the wire or a {@link MobClass.Shape}
   * (including a footprint over {@link MobClass.Shape#MAX_FOOTPRINT} cells), two nodes could share
   * vanilla's hash ({@link NodeHashes}), or profile ids are exhausted.
   */
  public static SearchCall read(
      Mob mob,
      NodeEvaluator evaluator,
      Node start,
      BlockPos target,
      float maxPathLength,
      int reachRange,
      int maxVisitedNodes,
      int minY,
      ProfileTable profiles,
      float[] scratch) {
    int maxFallDistance = mob.getMaxFallDistance();
    if (!MobClass.Shape.fits(mob.getBbWidth(), mob.getBbHeight(), mob.maxUpStep())
        || !WorldEvent.Search.fits(reachRange, maxPathLength, maxVisitedNodes, maxFallDistance)) {
      return null;
    }
    if (NodeHashes.mayCollide(
        new Pos(start.x, start.y, start.z),
        new Pos(target.getX(), target.getY(), target.getZ()),
        maxPathLength,
        maxFallDistance,
        mob.maxUpStep(),
        evaluator.canFloat(),
        minY)) {
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
        profile,
        new BlockPos(start.x, start.y, start.z),
        target.immutable(),
        new WorldEvent.Search(reachRange, maxPathLength, maxVisitedNodes, maxFallDistance),
        mob.getTarget() != null);
  }
}
