package io.github.davidmenggx.maestro.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerrainTest {

  private static final Terrain BARE = new Terrain(List.of());

  /** A square of 256 by 256 columns across the origin, so negative ones are covered too. */
  private static final int LOW = -128;

  private static final int SIDE = 256;

  @Test
  void everyColumnIsTheSameFunctionOfWhereItIs() {
    Terrain other = new Terrain(List.of());
    for (int x = LOW; x < LOW + SIDE; x += 7) {
      for (int z = LOW; z < LOW + SIDE; z += 5) {
        assertEquals(BARE.column(x, z), other.column(x, z));
      }
    }
  }

  @Test
  void everyColumnIsGroundUnderAnObstacleOrNothingWithinTheBand() {
    for (int x = LOW; x < LOW + SIDE; x++) {
      for (int z = LOW; z < LOW + SIDE; z++) {
        Terrain.Column column = BARE.column(x, z);
        assertTrue(column.ground() >= Terrain.FLOOR, column.toString());
        assertTrue(column.top() < Terrain.BAND, column.toString());
        int obstacle = column.top() - column.ground();
        assertTrue(obstacle == 0 || obstacle == Terrain.OBSTACLE, column.toString());
      }
    }
  }

  @Test
  void theGroundIsGrassOnlyWithNothingOnIt() {
    Terrain.Column open = new Terrain.Column(5, 5);
    Terrain.Column blocked = new Terrain.Column(5, 5 + Terrain.OBSTACLE);

    assertEquals(Terrain.Layer.DIRT, Terrain.layer(open, 4));
    assertEquals(Terrain.Layer.GRASS, Terrain.layer(open, 5));
    assertEquals(Terrain.Layer.AIR, Terrain.layer(open, 6));
    assertEquals(Terrain.Layer.DIRT, Terrain.layer(blocked, 4));
    assertEquals(Terrain.Layer.DIRT, Terrain.layer(blocked, 5));
    assertEquals(Terrain.Layer.OBSTACLE, Terrain.layer(blocked, 6));
    assertEquals(Terrain.Layer.OBSTACLE, Terrain.layer(blocked, 5 + Terrain.OBSTACLE));
    assertEquals(Terrain.Layer.AIR, Terrain.layer(blocked, 6 + Terrain.OBSTACLE));
  }

  @Test
  void grassTopsEveryColumnWithNoObstacleAndNoOther() {
    for (int x = LOW; x < LOW + SIDE; x += 3) {
      for (int z = LOW; z < LOW + SIDE; z += 3) {
        Terrain.Column column = BARE.column(x, z);
        int grass = 0;
        for (int y = 1; y < Terrain.BAND; y++) {
          Terrain.Layer layer = Terrain.layer(column, y);
          Terrain.Layer above = Terrain.layer(column, y + 1);
          if (layer == Terrain.Layer.GRASS) {
            grass++;
            assertEquals(Terrain.Layer.AIR, above, column.toString());
          }
          if (above == Terrain.Layer.AIR && layer != Terrain.Layer.AIR) {
            assertEquals(column.top(), y, column.toString());
          }
        }
        assertEquals(column.top() == column.ground() ? 1 : 0, grass, column.toString());
      }
    }
  }

  @Test
  void obstaclesAndCliffsBlockSomeColumnsButMostAreReachableFromEachOther() {
    Terrain.Column[][] columns = new Terrain.Column[SIDE][SIDE];
    int obstacles = 0;
    int cliffs = 0;
    for (int x = 0; x < SIDE; x++) {
      for (int z = 0; z < SIDE; z++) {
        columns[x][z] = BARE.column(LOW + x, LOW + z);
        obstacles += columns[x][z].top() > columns[x][z].ground() ? 1 : 0;
        if (x > 0 && Math.abs(columns[x][z].ground() - columns[x - 1][z].ground()) > 1) {
          cliffs++;
        }
      }
    }
    int all = SIDE * SIDE;

    assertTrue(obstacles > all / 50 && obstacles < all / 5, "obstacles: " + obstacles);
    assertTrue(cliffs > 100, "cliffs: " + cliffs);
    int reachable = largestWalkableRegion(columns);
    assertTrue(reachable > all * 3 / 4, "reachable: " + reachable + " of " + all);
  }

  @Test
  void aKeepRisesPastClimbingWithNothingAroundItToClimbFrom() {
    Scenario.Area keep = new Scenario.Area(40, -24, 3);
    Terrain terrain = new Terrain(List.of(keep));
    int reach = keep.radius() + Terrain.KEEP_MARGIN;
    int top = terrain.column(keep.x(), keep.z()).ground();

    for (int x = keep.x() - reach; x <= keep.x() + reach; x++) {
      for (int z = keep.z() - reach; z <= keep.z() + reach; z++) {
        Terrain.Column column = terrain.column(x, z);
        if (keep.contains(x, z, 0)) {
          assertEquals(new Terrain.Column(top, top), column);
        } else {
          assertEquals(column.ground(), column.top(), "an obstacle beside the keep");
          assertTrue(column.top() <= top - Terrain.KEEP_RISE, column.toString());
        }
      }
    }
  }

  @Test
  void aKeepChangesNothingPastItsMargin() {
    Scenario.Area keep = new Scenario.Area(-8, 16, 2);
    Terrain terrain = new Terrain(List.of(keep));
    int reach = keep.radius() + Terrain.KEEP_MARGIN;

    for (int x = keep.x() - reach - 8; x <= keep.x() + reach + 8; x++) {
      for (int z = keep.z() - reach - 8; z <= keep.z() + reach + 8; z++) {
        if (!keep.contains(x, z, Terrain.KEEP_MARGIN)) {
          assertEquals(BARE.column(x, z), terrain.column(x, z));
        } else if (!keep.contains(x, z, 0)) {
          assertEquals(BARE.column(x, z).ground(), terrain.column(x, z).ground());
        }
      }
    }
  }

  /**
   * The most columns reachable from one another, stepping between neighbours with no obstacle a
   * block up or down at most.
   */
  private static int largestWalkableRegion(Terrain.Column[][] columns) {
    boolean[][] seen = new boolean[SIDE][SIDE];
    int largest = 0;
    int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    for (int x = 0; x < SIDE; x++) {
      for (int z = 0; z < SIDE; z++) {
        if (seen[x][z] || columns[x][z].top() > columns[x][z].ground()) {
          continue;
        }
        int size = 0;
        ArrayDeque<int[]> open = new ArrayDeque<>();
        open.add(new int[] {x, z});
        seen[x][z] = true;
        while (!open.isEmpty()) {
          int[] at = open.poll();
          size++;
          Terrain.Column from = columns[at[0]][at[1]];
          for (int[] step : steps) {
            int nx = at[0] + step[0];
            int nz = at[1] + step[1];
            if (nx < 0 || nz < 0 || nx >= SIDE || nz >= SIDE || seen[nx][nz]) {
              continue;
            }
            Terrain.Column to = columns[nx][nz];
            if (to.top() == to.ground() && Math.abs(to.ground() - from.ground()) <= 1) {
              seen[nx][nz] = true;
              open.add(new int[] {nx, nz});
            }
          }
        }
        largest = Math.max(largest, size);
      }
    }
    return largest;
  }
}
