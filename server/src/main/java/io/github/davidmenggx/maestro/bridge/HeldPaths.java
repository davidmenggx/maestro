package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.core.RequestTracker;
import io.github.davidmenggx.maestro.controller.env.Clock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Hold-in-place paths: one node at the mob's own block, which a mob follows while its real path is
 * computed. A held path is never done and never advances, so the goal that asked for it keeps
 * running, and the navigation neither moves the mob nor judges it stuck. Releasing it ends it, as
 * when its request is dropped. Used on the server thread only.
 */
public final class HeldPaths {

  /** Mixed into {@code Path}. */
  public interface Holdable {

    /** The last tick the path is held on, or {@link #NOT_HELD}. */
    long maestro$heldUntil();

    void maestro$setHeldUntil(long tick);
  }

  public static final long NOT_HELD = -1;

  /**
   * Ticks a path stays held at most. The controller settles every request within its tick budget,
   * so a hold that outlives it lost its request somewhere, and ends on its own.
   */
  static final int MAX_HOLD_TICKS = RequestTracker.MAX_TICKS_LATE + 2;

  private static Clock clock;

  private HeldPaths() {}

  /** Sets the time holds are judged by. Until then nothing can be held. */
  public static void clock(Clock source) {
    clock = source;
  }

  /** A held path of one node at {@code at}, for a search to {@code target}. */
  public static Path hold(BlockPos at, BlockPos target) {
    // Mutable, since the navigation may trim a path it follows.
    List<Node> nodes = new ArrayList<>(1);
    nodes.add(new Node(at.getX(), at.getY(), at.getZ()));
    Path path = new Path(nodes, target, false);
    ((Holdable) (Object) path).maestro$setHeldUntil(clock.tick() + MAX_HOLD_TICKS);
    return path;
  }

  /** Holds the path for as long as a new one would be held. */
  public static void renew(Path path) {
    ((Holdable) (Object) path).maestro$setHeldUntil(clock.tick() + MAX_HOLD_TICKS);
  }

  /**
   * Whether the path is held now. A hold that ran out, or whose node was trimmed away, ends here as
   * a released one does.
   */
  public static boolean isHeld(Path path) {
    Holdable holdable = (Holdable) (Object) path;
    long until = holdable.maestro$heldUntil();
    if (until == NOT_HELD) {
      return false;
    }
    if (path.getNodeCount() > 0 && clock != null && clock.tick() <= until) {
      return true;
    }
    release(path);
    return false;
  }

  /** Ends the hold. The path is done from now on. */
  public static void release(Path path) {
    ((Holdable) (Object) path).maestro$setHeldUntil(NOT_HELD);
    path.advance();
  }
}
