package io.github.davidmenggx.maestro.load;

import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Seeded terrain that mobs must search their way around: rolling hills, mesas whose cliffs are
 * mostly too high to climb, walls, pillars, and roofless ruins with two gaps in them. It is all
 * full blocks, one vertical band per column, with nothing that hurts a mob. Outside its keeps it is
 * one fixed function of the column, so scenarios whose terrain overlaps agree on it. A keep is a
 * platform raised past where a mob can climb, with nothing around it to climb from.
 */
final class Terrain {

  /**
   * A column's top ground block and its top block, as heights above the bottom of the world. The
   * blocks above the ground up to the top are an obstacle.
   */
  record Column(int ground, int top) {}

  /** What a column holds at one height. */
  enum Layer {
    DIRT,
    GRASS,
    OBSTACLE,
    AIR
  }

  /** The flat world's ground: grass on two dirt on bedrock. */
  static final int FLOOR = 3;

  /** Blocks rebuilt in each column, from the one above the bottom of the world. */
  static final int BAND = 24;

  /** Height of an obstacle, past the one block a mob can climb. */
  static final int OBSTACLE = 2;

  /** Height of a keep above the highest ground on it and around it. */
  static final int KEEP_RISE = 4;

  /** Blocks around a keep kept clear of obstacles, so none is a step onto it. */
  static final int KEEP_MARGIN = 2;

  private static final long SEED = 0x6d61657374726fL;

  private static final int HILL_CELL = 16;
  private static final int HILL_RELIEF = 4;
  private static final int MESA_CELL = 32;
  private static final int WALL_CELL = 16;
  private static final int RUIN_CELL = 32;
  private static final int PILLAR_ODDS = 40;

  private static final int HILL = 1;
  private static final int MESA = 2;
  private static final int WALL = 3;
  private static final int RUIN = 4;
  private static final int PILLAR = 5;

  /** Writes blocks as they are, without updates that would change them. */
  private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

  private final List<Scenario.Area> keeps;

  /** Each keep's top ground block. */
  private final int[] keepTops;

  Terrain(List<Scenario.Area> keeps) {
    this.keeps = List.copyOf(keeps);
    this.keepTops = new int[keeps.size()];
    for (int i = 0; i < keepTops.length; i++) {
      Scenario.Area keep = keeps.get(i);
      int reach = keep.radius() + KEEP_MARGIN;
      int highest = 0;
      for (int x = keep.x() - reach; x <= keep.x() + reach; x++) {
        for (int z = keep.z() - reach; z <= keep.z() + reach; z++) {
          highest = Math.max(highest, land(x, z));
        }
      }
      keepTops[i] = highest + KEEP_RISE;
    }
  }

  Column column(int x, int z) {
    boolean clear = false;
    for (int i = 0; i < keepTops.length; i++) {
      Scenario.Area keep = keeps.get(i);
      if (keep.contains(x, z, 0)) {
        return new Column(keepTops[i], keepTops[i]);
      }
      clear |= keep.contains(x, z, KEEP_MARGIN);
    }
    int ground = land(x, z);
    boolean obstacle = !clear && (wall(x, z) || ruin(x, z) || pillar(x, z));
    return new Column(ground, obstacle ? ground + OBSTACLE : ground);
  }

  /**
   * The layer of {@code column} at {@code y}: dirt up to the ground, cobblestone up to the top, air
   * above. The ground is grass unless an obstacle stands on it, since grass under a block turns to
   * dirt and the terrain would not stay as built.
   */
  static Layer layer(Column column, int y) {
    if (y > column.top()) {
      return Layer.AIR;
    }
    if (y > column.ground()) {
      return Layer.OBSTACLE;
    }
    return y == column.ground() && column.top() == column.ground() ? Layer.GRASS : Layer.DIRT;
  }

  /**
   * Rebuilds the columns of {@code areas} in {@code level} as this terrain, each {@link #layer} as
   * its block. Returns how many blocks changed, none for columns already built.
   */
  int build(ServerLevel level, List<Scenario.Area> areas) {
    BlockState dirt = Blocks.DIRT.defaultBlockState();
    BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
    BlockState obstacle = Blocks.COBBLESTONE.defaultBlockState();
    BlockState air = Blocks.AIR.defaultBlockState();
    BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
    int bottom = level.getMinY();
    int changed = 0;
    for (Scenario.Area area : areas) {
      for (int x = area.x() - area.radius(); x <= area.x() + area.radius(); x++) {
        for (int z = area.z() - area.radius(); z <= area.z() + area.radius(); z++) {
          Column column = column(x, z);
          for (int y = 1; y < BAND; y++) {
            BlockState state =
                switch (layer(column, y)) {
                  case DIRT -> dirt;
                  case GRASS -> grass;
                  case OBSTACLE -> obstacle;
                  case AIR -> air;
                };
            at.set(x, bottom + y, z);
            if (level.getBlockState(at) != state) {
              level.setBlock(at, state, FLAGS);
              changed++;
            }
          }
        }
      }
    }
    return changed;
  }

  /** The ground with no keep: hills, raised where a mesa stands. */
  private static int land(int x, int z) {
    return FLOOR + hill(x, z) + mesa(x, z);
  }

  /** Heights drawn at the corners of cells and blended, so neighbours differ by a block at most. */
  private static int hill(int x, int z) {
    int cellX = Math.floorDiv(x, HILL_CELL);
    int cellZ = Math.floorDiv(z, HILL_CELL);
    double fx = Math.floorMod(x, HILL_CELL) / (double) HILL_CELL;
    double fz = Math.floorMod(z, HILL_CELL) / (double) HILL_CELL;
    double north = lerp(corner(cellX, cellZ), corner(cellX + 1, cellZ), fx);
    double south = lerp(corner(cellX, cellZ + 1), corner(cellX + 1, cellZ + 1), fx);
    return (int) Math.round(lerp(north, south, fz));
  }

  private static int corner(int cellX, int cellZ) {
    return (int) Long.remainderUnsigned(hash(HILL, cellX, cellZ), HILL_RELIEF + 1);
  }

  /** In a third of the cells, a rectangle raised two or three blocks. */
  private static int mesa(int x, int z) {
    SplittableRandom random =
        new SplittableRandom(hash(MESA, Math.floorDiv(x, MESA_CELL), Math.floorDiv(z, MESA_CELL)));
    if (random.nextInt(3) != 0) {
      return 0;
    }
    int width = 6 + random.nextInt(11);
    int depth = 6 + random.nextInt(11);
    int x0 = 2 + random.nextInt(MESA_CELL - 3 - width);
    int z0 = 2 + random.nextInt(MESA_CELL - 3 - depth);
    int rise = 2 + random.nextInt(2);
    int localX = Math.floorMod(x, MESA_CELL);
    int localZ = Math.floorMod(z, MESA_CELL);
    boolean inside = localX >= x0 && localX < x0 + width && localZ >= z0 && localZ < z0 + depth;
    return inside ? rise : 0;
  }

  /** Up to two straight walls a cell, which keep off its edges so no two cells' walls meet. */
  private static boolean wall(int x, int z) {
    SplittableRandom random =
        new SplittableRandom(hash(WALL, Math.floorDiv(x, WALL_CELL), Math.floorDiv(z, WALL_CELL)));
    int localX = Math.floorMod(x, WALL_CELL);
    int localZ = Math.floorMod(z, WALL_CELL);
    for (int walls = random.nextInt(3); walls > 0; walls--) {
      boolean alongX = random.nextBoolean();
      int length = 5 + random.nextInt(8);
      int from = 1 + random.nextInt(WALL_CELL - 1 - length);
      int across = 1 + random.nextInt(WALL_CELL - 2);
      int along = alongX ? localX : localZ;
      if ((alongX ? localZ : localX) == across && along >= from && along < from + length) {
        return true;
      }
    }
    return false;
  }

  /**
   * In a quarter of the cells, the walls of a rectangle with a gap two blocks wide in its north or
   * south side and another in its west or east side.
   */
  private static boolean ruin(int x, int z) {
    SplittableRandom random =
        new SplittableRandom(hash(RUIN, Math.floorDiv(x, RUIN_CELL), Math.floorDiv(z, RUIN_CELL)));
    if (random.nextInt(4) != 0) {
      return false;
    }
    int width = 6 + random.nextInt(7);
    int depth = 6 + random.nextInt(7);
    int x0 = 2 + random.nextInt(RUIN_CELL - 3 - width);
    int z0 = 2 + random.nextInt(RUIN_CELL - 3 - depth);
    int x1 = x0 + width - 1;
    int z1 = z0 + depth - 1;
    int localX = Math.floorMod(x, RUIN_CELL);
    int localZ = Math.floorMod(z, RUIN_CELL);
    boolean outside = localX < x0 || localX > x1 || localZ < z0 || localZ > z1;
    boolean inside = localX > x0 && localX < x1 && localZ > z0 && localZ < z1;
    if (outside || inside) {
      return false;
    }
    int gapZ = random.nextBoolean() ? z0 : z1;
    int gapX = x0 + 1 + random.nextInt(width - 3);
    int sideX = random.nextBoolean() ? x0 : x1;
    int sideZ = z0 + 1 + random.nextInt(depth - 3);
    boolean gap =
        (localZ == gapZ && (localX == gapX || localX == gapX + 1))
            || (localX == sideX && (localZ == sideZ || localZ == sideZ + 1));
    return !gap;
  }

  private static boolean pillar(int x, int z) {
    return Long.remainderUnsigned(hash(PILLAR, x, z), PILLAR_ODDS) == 0;
  }

  private static long hash(int salt, int a, int b) {
    return mix(mix(mix(SEED + salt) + a) + b);
  }

  /** SplitMix64's finalizer. */
  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
