package io.github.davidmenggx.maestro.controller.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NodeHashesTest {

  private static final int MIN_Y = -64;
  private static final int FALL = 3;
  private static final float STEP = 0.6f;

  @Test
  void theHashLosesWhatVanillaLoses() {
    assertEquals(NodeHashes.hash(5, 64, 10), NodeHashes.hash(5, 64, 266));
    assertEquals(NodeHashes.hash(5, 0, 5), NodeHashes.hash(5, 256, 5));
    // Bit 7 of one horizontal axis is lost where the other is negative.
    assertEquals(NodeHashes.hash(-5, 64, 10), NodeHashes.hash(-5, 64, 138));
    assertNotEquals(NodeHashes.hash(5, 64, 10), NodeHashes.hash(5, 64, 138));
    assertEquals(NodeHashes.hash(0, 64, -3), NodeHashes.hash(128, 64, -3));
    assertNotEquals(NodeHashes.hash(0, 64, 3), NodeHashes.hash(128, 64, 3));
    assertEquals(0xFF7FFF40, NodeHashes.hash(-1, 64, -1));
  }

  @Test
  void aTargetThatSharesAHashWithAPositionInReachIsCaught() {
    Pos start = new Pos(100, 64, 100);
    assertTrue(mayCollide(start, new Pos(100, 64, 356), 16f, true));
    assertFalse(mayCollide(start, new Pos(100, 64, 228), 16f, true));
    assertTrue(mayCollide(new Pos(-100, 64, 100), new Pos(-100, 64, 228), 16f, true));
    assertTrue(mayCollide(new Pos(100, 64, -100), new Pos(228, 64, -100), 16f, true));
  }

  @Test
  void aReachWideEnoughForTwoPositionsToShareAHashIsCaught() {
    assertTrue(mayCollide(new Pos(-10, 64, 500), 64f));
    assertFalse(mayCollide(new Pos(-10, 64, 500), 63f));
    assertTrue(mayCollide(new Pos(500, 64, -10), 64f));
    assertFalse(mayCollide(new Pos(500, 64, 500), 64f));
    assertTrue(mayCollide(new Pos(500, 64, 500), Float.POSITIVE_INFINITY));
  }

  @Test
  void aMobThatSinksCanReachTheBottomOfTheWorld() {
    Pos high = new Pos(500, 200, 500);
    assertTrue(mayCollide(high, high, 16f, false));
    assertFalse(mayCollide(high, high, 16f, true));
    Pos low = new Pos(500, 150, 500);
    assertFalse(mayCollide(low, low, 16f, false));
  }

  /** Where the check passes a search, no two positions it may create share a hash. */
  @Test
  void aSearchJustInsideTheLimitsHasNoSharedHash() {
    assertNoSharedHash(new Pos(-60, 64, -60), new Pos(-50, 64, -40), 63f);
    assertNoSharedHash(new Pos(4100, 70, 4100), new Pos(4110, 70, 4120), 64f);
  }

  private static boolean mayCollide(Pos start, float maxPathLength) {
    return mayCollide(start, start, maxPathLength, true);
  }

  private static boolean mayCollide(Pos start, Pos target, float maxPathLength, boolean canFloat) {
    return NodeHashes.mayCollide(start, target, maxPathLength, FALL, STEP, canFloat, MIN_Y);
  }

  /**
   * Hashes every position vanilla's rules let a search create, plus the target. A node is expanded
   * while its float distance from the start is below the bound, and creates nodes one block around
   * it, from its fall below to its jump above. The positions come from those rules rather than from
   * the bounds under test, so a wrong bound fails here.
   */
  private static void assertNoSharedHash(Pos start, Pos target, float maxPathLength) {
    assertFalse(mayCollide(start, target, maxPathLength, true));
    int jump = (int) Math.floor(Math.max(1.0f, STEP));
    int scan = (int) Math.ceil(maxPathLength) + 1;
    int side = 2 * scan + 1;
    // Lowest and highest expanded y offset per column.
    int[] low = new int[side * side];
    int[] high = new int[side * side];
    Arrays.fill(low, Integer.MAX_VALUE);
    Arrays.fill(high, Integer.MIN_VALUE);
    for (int dx = -scan; dx <= scan; dx++) {
      for (int dz = -scan; dz <= scan; dz++) {
        for (int dy = -scan; dy <= scan; dy++) {
          // `Node.distanceTo`: float differences, a float sum and a float root.
          float xd = dx;
          float yd = dy;
          float zd = dz;
          float distance = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
          if (!(distance >= maxPathLength)) {
            int column = (dx + scan) * side + dz + scan;
            low[column] = Math.min(low[column], dy);
            high[column] = Math.max(high[column], dy);
          }
        }
      }
    }

    int[] hashes = new int[side * side * (2 * scan + FALL + jump + 3) + 1];
    int count = 0;
    boolean targetCreated = false;
    for (int cx = -scan; cx <= scan; cx++) {
      for (int cz = -scan; cz <= scan; cz++) {
        int bottom = Integer.MAX_VALUE;
        int top = Integer.MIN_VALUE;
        for (int nx = Math.max(-scan, cx - 1); nx <= Math.min(scan, cx + 1); nx++) {
          for (int nz = Math.max(-scan, cz - 1); nz <= Math.min(scan, cz + 1); nz++) {
            int column = (nx + scan) * side + nz + scan;
            if (low[column] <= high[column]) {
              bottom = Math.min(bottom, low[column] - FALL - 1);
              top = Math.max(top, high[column] + jump);
            }
          }
        }
        for (int dy = bottom; dy <= top; dy++) {
          int x = start.x() + cx;
          int y = start.y() + dy;
          int z = start.z() + cz;
          hashes[count++] = NodeHashes.hash(x, y, z);
          targetCreated |= x == target.x() && y == target.y() && z == target.z();
        }
      }
    }
    if (!targetCreated) {
      hashes[count++] = NodeHashes.hash(target.x(), target.y(), target.z());
    }
    int[] sorted = Arrays.copyOf(hashes, count);
    Arrays.sort(sorted);
    for (int i = 1; i < sorted.length; i++) {
      assertNotEquals(sorted[i - 1], sorted[i], "two positions share hash " + sorted[i]);
    }
  }
}
