package io.github.davidmenggx.maestro.capture;

import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * Compares the node's world model with vanilla's during a capture, query by query. The node treats
 * every cell as a full-width box of its collision height and reads only path types for water, so it
 * can answer a query differently. A search where every query agreed was not changed by the model.
 *
 * <p>It also notes when a search reads the mob's position for a sweep, which the node replaces with
 * its start node. The world border and entities are not compared.
 *
 * <p>Fed from {@code WalkNodeEvaluatorMixin} on the server thread.
 */
public final class ApproximationProbe {

  private static ApproximationProbe active;

  private final WorldSource world;
  private boolean disagreed;
  private boolean swept;

  private ApproximationProbe(WorldSource world) {
    this.world = world;
  }

  /** Starts comparing against cells read from {@code world}. */
  static ApproximationProbe start(WorldSource world) {
    active = new ApproximationProbe(world);
    return active;
  }

  static void stop() {
    active = null;
  }

  /** Whether any query since the last {@link #reset} got a different answer from the model. */
  boolean disagreed() {
    return disagreed;
  }

  /** Whether the search swept a box from the mob's position since the last {@link #reset}. */
  boolean swept() {
    return swept;
  }

  void reset() {
    disagreed = false;
    swept = false;
  }

  /** The search is about to sweep a box from the mob's position. */
  public static void sweep() {
    if (active != null) {
      active.swept = true;
    }
  }

  /** A {@code hasCollisions} answer from vanilla. */
  public static void collision(AABB box, boolean vanilla) {
    if (active != null && active.collides(box) != vanilla) {
      active.disagreed = true;
    }
  }

  /** A {@code getFloorLevel} answer from vanilla, for an evaluator that floats or not. */
  public static void floor(BlockPos pos, boolean canFloat, double vanilla) {
    if (active != null && active.floorLevel(pos, canFloat) != vanilla) {
      active.disagreed = true;
    }
  }

  /** The node's {@code hasCollisions}: touching faces do not collide. */
  private boolean collides(AABB box) {
    double epsilon = 1.0E-7;
    int x0 = Mth.floor(box.minX - epsilon);
    int x1 = Mth.floor(box.maxX + epsilon);
    int y0 = Mth.floor(box.minY - epsilon) - 1;
    int y1 = Mth.floor(box.maxY + epsilon);
    int z0 = Mth.floor(box.minZ - epsilon);
    int z1 = Mth.floor(box.maxZ + epsilon);
    for (int x = x0; x <= x1; x++) {
      for (int y = y0; y <= y1; y++) {
        for (int z = z0; z <= z1; z++) {
          int floor = world.floorAt(x, y, z);
          if (floor == 0) {
            continue;
          }
          double top = y + floor / (double) Cells.FULL_FLOOR;
          if (box.minX < x + 1.0
              && box.maxX > x
              && box.minY < top
              && box.maxY > y
              && box.minZ < z + 1.0
              && box.maxZ > z) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** The node's {@code getFloorLevel}: water is a cell typed {@code WATER}. */
  private double floorLevel(BlockPos pos, boolean canFloat) {
    int x = pos.getX();
    int y = pos.getY();
    int z = pos.getZ();
    if (canFloat && world.pathTypeAt(x, y, z) == maestro.fbs.PathType.WATER) {
      return y + 0.5;
    }
    return (y - 1) + world.floorAt(x, y - 1, z) / (double) Cells.FULL_FLOOR;
  }
}
