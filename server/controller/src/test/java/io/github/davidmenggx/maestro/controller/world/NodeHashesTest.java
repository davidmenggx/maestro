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

  /** Hashes every position in the bounds the check documents, plus the target. */
  private static void assertNoSharedHash(Pos start, Pos target, float maxPathLength) {
    assertFalse(mayCollide(start, target, maxPathLength, true));
    int reach = (int) Math.ceil(maxPathLength);
    int top = start.y() + reach - 1 + 1;
    int bottom = start.y() - reach - FALL;
    int side = 2 * reach + 1;
    int[] hashes = new int[side * side * (top - bottom + 1) + 1];
    int count = 0;
    boolean targetInside = false;
    for (int x = start.x() - reach; x <= start.x() + reach; x++) {
      for (int z = start.z() - reach; z <= start.z() + reach; z++) {
        for (int y = bottom; y <= top; y++) {
          hashes[count++] = NodeHashes.hash(x, y, z);
          targetInside |= x == target.x() && y == target.y() && z == target.z();
        }
      }
    }
    if (!targetInside) {
      hashes[count++] = NodeHashes.hash(target.x(), target.y(), target.z());
    }
    int[] sorted = Arrays.copyOf(hashes, count);
    Arrays.sort(sorted);
    for (int i = 1; i < sorted.length; i++) {
      assertNotEquals(sorted[i - 1], sorted[i], "two positions share hash " + sorted[i]);
    }
  }
}
