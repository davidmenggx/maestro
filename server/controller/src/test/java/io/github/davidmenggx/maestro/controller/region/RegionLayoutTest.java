package io.github.davidmenggx.maestro.controller.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RegionLayoutTest {

  private static final int CELL = RegionLayout.CELL;

  @Test
  void theOriginIsTheCenterOfItsCell() {
    assertEquals(-CELL / 2, RegionLayout.cellMin(0));
    assertEquals(CELL / 2, RegionLayout.cellMin(1));
    assertEquals(-3 * CELL / 2, RegionLayout.cellMin(-1));
  }

  @Test
  void theSquareIsCenteredOnTheOriginsCell() {
    // Odd sides are symmetric; even ones have one cell fewer below the origin's.
    assertEquals(-CELL / 2, new RegionLayout(1).min());
    assertEquals(-CELL / 2, new RegionLayout(2).min());
    assertEquals(-3 * CELL / 2, new RegionLayout(3).min());
    assertEquals(-3 * CELL / 2, new RegionLayout(4).min());
    assertEquals(5 * CELL, new RegionLayout(5).size());

    RegionLayout layout = new RegionLayout(3);
    int low = layout.min();
    int high = low + layout.size() - 1;
    assertTrue(layout.covers(low, high));
    assertFalse(layout.covers(low - 1, 0));
    assertFalse(layout.covers(0, high + 1));
    // An area reaches one halo past the square.
    assertTrue(layout.reaches(low - Region.HALO, high + Region.HALO));
    assertFalse(layout.reaches(low - Region.HALO - 1, 0));
    assertFalse(layout.reaches(0, high + Region.HALO + 1));
  }

  @Test
  void aLayoutsAreasFitTheWiresPositions() {
    new RegionLayout(RegionLayout.MAX_CELLS);
    assertTrue(RegionLayout.MAX_CELLS * CELL + 2 * Region.HALO <= Short.MAX_VALUE);
    assertTrue((RegionLayout.MAX_CELLS + 1) * CELL + 2 * Region.HALO > Short.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> new RegionLayout(0));
    assertThrows(
        IllegalArgumentException.class, () -> new RegionLayout(RegionLayout.MAX_CELLS + 1));
  }

  @Test
  void aRegionIsHalvedAcrossItsLongerSideInWholeCells() {
    // Square: across x.
    assertHalves(halves(0, 0, 4 * CELL, 4 * CELL), 0, 0, 2 * CELL, 4 * CELL);
    // Taller than wide: across z.
    assertHalves(halves(0, 0, 2 * CELL, 4 * CELL), 0, 0, 2 * CELL, 2 * CELL);
    // An odd side leaves the upper half a cell larger.
    List<int[]> odd = halves(-CELL, 0, 3 * CELL, CELL);
    assertArrayEquals(new int[] {-CELL, 0, CELL, CELL}, odd.get(0));
    assertArrayEquals(new int[] {0, 0, 2 * CELL, CELL}, odd.get(1));
    assertThrows(IllegalArgumentException.class, () -> RegionLayout.halving(CELL, CELL));

    assertTrue(RegionLayout.splittable(new Region(1, 0, 0, 2 * CELL, CELL)));
    assertFalse(RegionLayout.splittable(new Region(1, 0, 0, CELL, CELL)));
  }

  @Test
  void aCutLeavesItsLowerCellsOnTheSideWithTheCorner() {
    List<int[]> acrossX =
        RegionLayout.sides(0, CELL, 4 * CELL, 2 * CELL, new RegionLayout.Cut(true, 3));
    assertArrayEquals(new int[] {0, CELL, 3 * CELL, 2 * CELL}, acrossX.get(0));
    assertArrayEquals(new int[] {3 * CELL, CELL, CELL, 2 * CELL}, acrossX.get(1));
    List<int[]> acrossZ =
        RegionLayout.sides(0, CELL, 4 * CELL, 2 * CELL, new RegionLayout.Cut(false, 1));
    assertArrayEquals(new int[] {0, CELL, 4 * CELL, CELL}, acrossZ.get(0));
    assertArrayEquals(new int[] {0, 2 * CELL, 4 * CELL, CELL}, acrossZ.get(1));

    // Each side keeps a cell.
    assertThrows(IllegalArgumentException.class, () -> new RegionLayout.Cut(true, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> RegionLayout.sides(0, 0, 2 * CELL, 2 * CELL, new RegionLayout.Cut(true, 2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> RegionLayout.sides(0, 0, 2 * CELL, CELL, new RegionLayout.Cut(false, 1)));
  }

  private static List<int[]> halves(int minX, int minZ, int sizeX, int sizeZ) {
    return RegionLayout.sides(minX, minZ, sizeX, sizeZ, RegionLayout.halving(sizeX, sizeZ));
  }

  private static void assertHalves(List<int[]> halves, int minX, int minZ, int sizeX, int sizeZ) {
    assertArrayEquals(new int[] {minX, minZ, sizeX, sizeZ}, halves.get(0));
    int[] upper = halves.get(1);
    boolean acrossX = upper[1] == minZ;
    assertArrayEquals(
        acrossX
            ? new int[] {minX + sizeX, minZ, sizeX, sizeZ}
            : new int[] {minX, minZ + sizeZ, sizeX, sizeZ},
        upper);
  }

  @Test
  void theFleetPastItsSparesHoldsTheSquareHalvedLargestFirst() {
    RegionLayout layout = new RegionLayout(4);
    List<NodeId> fleet = nodes(5);

    RegionMap map = layout.initial(fleet, 1);

    assertEquals(4, map.regions().size());
    List<Region> regions = new ArrayList<>(map.regions());
    int min = layout.min();
    // Halved across x, then each half across z.
    assertEquals(new Region(1, min, min, 2 * CELL, 2 * CELL), regions.get(0));
    assertEquals(new Region(2, min, min + 2 * CELL, 2 * CELL, 2 * CELL), regions.get(1));
    assertEquals(new Region(3, min + 2 * CELL, min, 2 * CELL, 2 * CELL), regions.get(2));
    assertEquals(new Region(4, min + 2 * CELL, min + 2 * CELL, 2 * CELL, 2 * CELL), regions.get(3));
    for (int i = 0; i < 4; i++) {
      assertEquals(List.of(fleet.get(i)), map.holders(regions.get(i).id()));
    }
    assertEquals(Regions.NONE, map.regionOf(fleet.get(4)));
    assertEquals(map.region(1), map.regionAt(0, 0));
    List<String> found = new ArrayList<>();
    map.check(found);
    assertEquals(List.of(), found);
  }

  @Test
  void theRegionCountStaysWithinOneAndOnePerCell() {
    RegionLayout layout = new RegionLayout(2);
    // Every node a spare still leaves one region, held by the first node.
    RegionMap lone = layout.initial(nodes(2), 5);
    assertEquals(1, lone.regions().size());
    assertEquals(List.of(NodeId.of(0)), lone.nodes());
    // More nodes than cells leaves the rest spare.
    RegionMap crowded = layout.initial(nodes(7), 0);
    assertEquals(4, crowded.regions().size());
    assertEquals(4, crowded.nodes().size());
    for (Region region : crowded.regions()) {
      assertEquals(CELL, region.sizeX());
      assertEquals(CELL, region.sizeZ());
    }
    // Uneven counts still tile the square.
    for (int nodes = 1; nodes <= 9; nodes++) {
      RegionMap map = new RegionLayout(3).initial(nodes(nodes), 0);
      List<String> found = new ArrayList<>();
      map.check(found);
      assertEquals(List.of(), found, "nodes " + nodes);
      assertEquals(nodes, map.regions().size());
    }
    assertThrows(IllegalArgumentException.class, () -> layout.initial(List.of(), 0));
    assertThrows(IllegalArgumentException.class, () -> layout.initial(nodes(1), -1));
  }

  private static List<NodeId> nodes(int count) {
    return IntStream.range(0, count).mapToObj(NodeId::of).toList();
  }
}
