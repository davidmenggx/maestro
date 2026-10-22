package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import java.util.function.Supplier;
import net.minecraft.world.entity.Mob;
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

    /**
     * The path the search returns, or null for none. {@code vanilla} runs vanilla's search and
     * returns its answer, which may be null. It may be called once, and only before this returns.
     */
    Path search(Mob mob, SearchCall call, Supplier<Path> vanilla);

    /**
     * The mob's navigation is about to stop following {@code current}, for {@code next}, or for
     * nothing if null.
     */
    default void displaced(Mob mob, Path current, Path next) {}
  }

  private static Interceptor interceptor;

  /** How many calls that may be offloaded are running, nested. */
  private static int offloadable;

  private Searches() {}

  /**
   * Whether the call is one the node reproduces: a single target, searched by exactly vanilla's
   * {@code PathFinder} and {@code WalkNodeEvaluator}. Subclasses change the search, so they stay
   * vanilla: the Warden's path finder measures distance on the horizontal plane only, and the
   * Creaking's evaluator changes which cells are walkable.
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

  /** Replaces the interceptor and returns the one it replaced, or null. Null removes it. */
  public static Interceptor intercept(Interceptor next) {
    Interceptor previous = interceptor;
    interceptor = next;
    return previous;
  }

  /**
   * Whether the search running now was asked for by a caller that follows the path it gets: one of
   * the navigation's {@code moveTo} methods, or {@code MeleeAttackGoal.canUse}, which moves along
   * its path once it starts. A caller that only reads a path, as to judge whether a target can be
   * reached, would take a placeholder for the answer.
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
