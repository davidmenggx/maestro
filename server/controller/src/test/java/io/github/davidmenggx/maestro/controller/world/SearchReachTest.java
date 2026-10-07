package io.github.davidmenggx.maestro.controller.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchReachTest {

  /** A zombie's footprint: one block. */
  private static final float NARROW = 0.6f;

  /** An iron golem's footprint: two blocks. */
  private static final float WIDE = 1.4f;

  @Test
  void theReachIsThePathLengthPlusTheFootprintAndTheTypeScan() {
    assertEquals(16 + 1 + 1, SearchReach.blocks(16f, NARROW));
    assertEquals(16 + 2 + 1, SearchReach.blocks(16f, WIDE));
    // A bound that is not whole still lets a node a fraction short of it expand.
    assertEquals(36 + 1 + 1, SearchReach.blocks(35.5f, NARROW));
  }

  @Test
  void aSearchFitsOnlyIfEveryBlockItReadsIsInTheArea() {
    int reach = SearchReach.blocks(64f, NARROW);
    int minX = -128;
    int minZ = 256;
    int size = 384;

    assertTrue(
        SearchReach.fits(minX, minZ, size, new Pos(minX + 192, 64, minZ + 192), 64f, NARROW));
    // Right at each edge, then one block past it.
    assertTrue(
        SearchReach.fits(minX, minZ, size, new Pos(minX + reach, 64, minZ + 192), 64f, NARROW));
    assertFalse(
        SearchReach.fits(minX, minZ, size, new Pos(minX + reach - 1, 64, minZ + 192), 64f, NARROW));
    assertTrue(
        SearchReach.fits(
            minX, minZ, size, new Pos(minX + size - 1 - reach, 64, minZ + 192), 64f, NARROW));
    assertFalse(
        SearchReach.fits(
            minX, minZ, size, new Pos(minX + size - reach, 64, minZ + 192), 64f, NARROW));
    assertFalse(
        SearchReach.fits(minX, minZ, size, new Pos(minX + 192, 64, minZ + reach - 1), 64f, NARROW));
    assertFalse(
        SearchReach.fits(
            minX, minZ, size, new Pos(minX + 192, 64, minZ + size - reach), 64f, NARROW));
  }

  @Test
  void aOneHorizonHaloDoesNotHoldALongSearchFromTheRegionEdge() {
    // A 256 region with a 64 block halo. An Enderman's 64 block follow range
    // fits from the middle of the region but not from its edge.
    int halo = 64;
    int region = 256;
    int size = region + 2 * halo;
    assertTrue(
        SearchReach.fits(
            0, 0, size, new Pos(halo + region / 2, 64, halo + region / 2), 64f, NARROW));
    assertFalse(SearchReach.fits(0, 0, size, new Pos(halo, 64, halo + region / 2), 64f, NARROW));
  }

  @Test
  void refusesInputsNoSearchHas() {
    assertThrows(IllegalArgumentException.class, () -> SearchReach.blocks(0f, NARROW));
    assertThrows(IllegalArgumentException.class, () -> SearchReach.blocks(Float.NaN, NARROW));
    assertThrows(IllegalArgumentException.class, () -> SearchReach.blocks(16f, 0f));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchReach.fits(0, 0, 0, new Pos(0, 0, 0), 16f, NARROW));
  }
}
