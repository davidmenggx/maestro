package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.NodeHashes;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
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

  /**
   * Largest footprint, in cells, a search is offloaded for. The cost of a search grows with its
   * footprint, and admission assumes requests cost about the same, so a larger mob's search stays
   * vanilla.
   */
  public static final int MAX_FOOTPRINT = 512;

  private static final PathType[] TYPES = PathType.values();

  /**
   * Reads the call and interns the mob's profile in {@code profiles}. {@code maxVisitedNodes} is
   * the budget the search runs with, already scaled by its multiplier. {@code scratch} is a buffer
   * of {@link PathTypes#count()} floats that the malus is read into; nothing keeps it. Returns null
   * if an input does not fit the wire or a {@link MobClass.Shape}, if the footprint is larger than
   * {@link #MAX_FOOTPRINT}, if two of the search's nodes could share vanilla's node hash ({@link
   * NodeHashes}), or if the profile ids are exhausted, so the search stays vanilla.
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
    if (!MobClass.Shape.fits(mob.getBbWidth(), mob.getBbHeight(), mob.maxUpStep())
        || footprint(mob.getBbWidth(), mob.getBbHeight()) > MAX_FOOTPRINT
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

  /**
   * Cells the evaluator tests for a mob of this size at each node, {@code entityWidth *
   * entityHeight * entityDepth} as {@code NodeEvaluator.prepare} sizes them.
   */
  static long footprint(float width, float height) {
    long side = Mth.floor(width + 1.0F);
    return side * Mth.floor(height + 1.0F) * side;
  }
}
