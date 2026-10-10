package io.github.davidmenggx.maestro.controller.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunksTest {

  @Test
  void blockCoordinatesRoundTowardNegativeInfinity() {
    assertEquals(0, Chunks.of(0));
    assertEquals(0, Chunks.of(15));
    assertEquals(1, Chunks.of(16));
    // Truncating division would put -1 in chunk 0 with 0.
    assertEquals(-1, Chunks.of(-1));
    assertEquals(-1, Chunks.of(-16));
    assertEquals(-2, Chunks.of(-17));
  }

  @Test
  void keysAreDistinctAcrossSignsAndUnpackToTheirCoordinates() {
    int[] coordinates = {Integer.MIN_VALUE, -2, -1, 0, 1, 2, Integer.MAX_VALUE};
    Set<Long> keys = new HashSet<>();
    for (int x : coordinates) {
      for (int z : coordinates) {
        long key = Chunks.key(x, z);
        assertTrue(keys.add(key), x + "," + z);
        // A negative `z` must not sign-extend into the `x` half.
        assertEquals(x, (int) (key >> 32));
        assertEquals(z, (int) key);
      }
    }
  }
}
