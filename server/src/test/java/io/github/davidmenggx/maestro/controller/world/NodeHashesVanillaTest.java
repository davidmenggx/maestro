package io.github.davidmenggx.maestro.controller.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;
import net.minecraft.world.level.pathfinder.Node;
import org.junit.jupiter.api.Test;

/**
 * {@link NodeHashes#hash} is vanilla's {@code Node.createHash}. In the controller's package, since
 * the controller cannot see the game.
 */
class NodeHashesVanillaTest {

  /** Each bit the hash keeps, drops or folds, and the ends of the range. */
  private static final int[] EDGES = {
    0,
    1,
    -1,
    127,
    128,
    -128,
    -129,
    255,
    256,
    -256,
    -257,
    32767,
    32768,
    -32768,
    -32769,
    65535,
    65536,
    -30_000_000,
    30_000_000,
    Integer.MAX_VALUE,
    Integer.MIN_VALUE
  };

  @Test
  void everyEdgeHashesAsVanillaHashesIt() {
    for (int x : EDGES) {
      for (int y : EDGES) {
        for (int z : EDGES) {
          assertHashesAsVanilla(x, y, z);
        }
      }
    }
  }

  @Test
  void randomPositionsHashAsVanillaHashesThem() {
    SplittableRandom rng = new SplittableRandom(20261030L);
    for (int i = 0; i < 200_000; i++) {
      assertHashesAsVanilla(rng.nextInt(), rng.nextInt(), rng.nextInt());
      // Where searches run: inside the world border and its height.
      assertHashesAsVanilla(
          rng.nextInt(-30_000_000, 30_000_000),
          rng.nextInt(-2048, 2048),
          rng.nextInt(-30_000_000, 30_000_000));
    }
  }

  private static void assertHashesAsVanilla(int x, int y, int z) {
    assertEquals(Node.createHash(x, y, z), NodeHashes.hash(x, y, z), x + "," + y + "," + z);
  }
}
