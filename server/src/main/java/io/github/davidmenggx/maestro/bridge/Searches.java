package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import java.util.Set;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Which {@code PathFinder.findPath} calls Maestro takes over, and who answers them. Used on the
 * server thread only.
 */
public final class Searches {

  /** Answers each intercepted search, by running vanilla's or not. */
  public interface Interceptor {

    /** Where the profiles of the searches this interceptor answers are interned. */
    ProfileTable profiles();

    /** Whether to take the call at all. One it does not take runs vanilla's search. */
    boolean wants(Mob mob);

    /** The path the search returns, or null for none. */
    Path search(Mob mob, SearchCall call, Vanilla vanilla);

    /** The navigation of {@code mob} was handed {@code path} to follow. */
    default void handed(Mob mob, Path path) {}
  }

  /**
   * Vanilla's search for one intercepted call. Runs once within the call, in full or on a share of
   * its node budget, and may run again later from where the mob then stands.
   */
  public static final class Vanilla {

    private final DoubleFunction<Path> search;
    private final Supplier<Path> later;
    private boolean ran;

    /**
     * {@code search} runs the call's search on the given share of its node budget. {@code later}
     * runs the same search anew.
     */
    public Vanilla(DoubleFunction<Path> search, Supplier<Path> later) {
      this.search = search;
      this.later = later;
    }

    /** Vanilla's answer, which may be null. Before the call returns, and once. */
    public Path search() {
      return run(1);
    }

    /**
     * Vanilla's answer on {@code share} of its node budget: a path toward the target that costs a
     * share of a full search. Instead of {@link #search}, and under the same rules.
     */
    public Path cheap(float share) {
      return run(share);
    }

    /** The full search, run anew from where the mob stands when it runs, at any time. */
    public Supplier<Path> later() {
      return later;
    }

    private Path run(double share) {
      if (ran) {
        throw new IllegalStateException("vanilla's search already ran for this call");
      }
      ran = true;
      return search.apply(share);
    }
  }

  /**
   * Blocks a region read for a search reaches past its path length bound (the larger of the
   * navigation's two margins).
   */
  private static final int REGION_MARGIN = 16;

  private static Interceptor interceptor;

  /** How many calls that may be offloaded are running, nested. */
  private static int offloadable;

  private Searches() {}

  /**
   * Whether the node reproduces the call: a single target, searched by exactly {@code PathFinder}
   * and {@code WalkNodeEvaluator}. Subclasses (the Warden's finder, the Creaking's evaluator)
   * change the search and stay vanilla.
   */
  public static boolean intercepted(PathFinder finder, NodeEvaluator evaluator, int targets) {
    return finder.getClass() == PathFinder.class
        && evaluator.getClass() == WalkNodeEvaluator.class
        && targets == 1;
  }

  /** The current interceptor, or null if none. */
  public static Interceptor interceptor() {
    return interceptor;
  }

  /** Tells the interceptor what the navigation of {@code mob} was handed to follow. */
  public static void handed(Mob mob, Path path) {
    if (interceptor != null && path != null) {
      interceptor.handed(mob, path);
    }
  }

  /**
   * Vanilla's search from where {@code mob} stands now, over a region around it as its navigation
   * would read one. Not a call that may be offloaded, so it is never intercepted.
   */
  public static Path searchAgain(
      PathFinder finder,
      Mob mob,
      BlockPos target,
      float maxPathLength,
      int reachRange,
      float maxVisitedNodesMultiplier) {
    BlockPos from = mob.blockPosition();
    int radius = (int) (maxPathLength + REGION_MARGIN);
    PathNavigationRegion region =
        new PathNavigationRegion(
            mob.level(),
            from.offset(-radius, -radius, -radius),
            from.offset(radius, radius, radius));
    return finder.findPath(
        region, mob, Set.of(target), maxPathLength, reachRange, maxVisitedNodesMultiplier);
  }

  /** Replaces the interceptor and returns the one it replaced, or null. Null removes it. */
  public static Interceptor intercept(Interceptor next) {
    Interceptor previous = interceptor;
    interceptor = next;
    return previous;
  }

  /**
   * Whether the running search comes from a caller that follows the path it gets (a navigation
   * {@code moveTo} or {@code MeleeAttackGoal.canUse}). A caller that only reads a path would take a
   * placeholder for the answer.
   */
  public static boolean offloadable() {
    return offloadable > 0;
  }

  /** Marks the start of a call whose searches may be offloaded. Pair with {@link #leave}. */
  public static void enter() {
    offloadable++;
  }

  public static void leave() {
    offloadable--;
  }
}
