package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Which {@code PathFinder.findPath} calls Maestro takes over, and who is told about them. Used on
 * the server thread only.
 */
public final class Searches {

  /** Told about each intercepted search and vanilla's answer to it. */
  public interface Observer {

    /** Where the profiles of the searches this observer is told about are interned. */
    ProfileTable profiles();

    void searched(SearchCall call, Path vanilla);
  }

  private static Observer observer;

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

  /** The current observer, or null if none. */
  public static Observer observer() {
    return observer;
  }

  /** Replaces the observer. Null removes it. */
  public static void observe(Observer next) {
    observer = next;
  }
}
