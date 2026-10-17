package io.github.davidmenggx.maestro.controller.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionMapTest {

  private static final RegionGrid GRID = new RegionGrid(32, 3);

  @Test
  void aGridIsWholeChunksWithItsCornerAtTheOrigin() {
    assertThrows(IllegalArgumentException.class, () -> new RegionGrid(24, 2));
    assertThrows(IllegalArgumentException.class, () -> new RegionGrid(16, 0));

    assertEquals(9, GRID.regionCount());
    assertEquals(0, GRID.regionAt(0, 0));
    assertEquals(1, GRID.regionAt(0, 32));
    assertEquals(3, GRID.regionAt(32, 0));
    assertEquals(8, GRID.regionAt(95, 95));
    // Outside the grid on either side.
    assertEquals(-1, GRID.regionAt(-1, 0));
    assertEquals(-1, GRID.regionAt(0, 96));
    assertEquals(64, GRID.minX(7));
    assertEquals(32, GRID.minZ(7));
    assertThrows(IllegalArgumentException.class, () -> GRID.minX(9));
  }

  @Test
  void aRegionsIdIsItsPlaceInTheGridWhicheverNodeHoldsIt() {
    List<NodeId> owners = List.of(5, 3, 8, 0, 1, 2, 4, 6, 7).stream().map(NodeId::of).toList();
    RegionMap regions = new RegionMap(GRID, owners);

    assertEquals(owners, regions.nodes());
    assertEquals(NodeId.of(5), regions.owner(0));
    assertEquals(0, regions.regionOf(NodeId.of(5)));
    assertEquals(2, regions.regionOf(NodeId.of(8)));
    assertEquals(-1, regions.regionOf(NodeId.of(9)));
    assertEquals(GRID.regionAt(40, 70), regions.regionAt(40, 70));
    assertThrows(IllegalArgumentException.class, () -> regions.owner(-1));
    assertThrows(IllegalArgumentException.class, () -> regions.owner(9));
  }

  @Test
  void everyRegionHasOneOwnerAndANodeHoldsOneRegion() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RegionMap(new RegionGrid(16, 2), List.of(NodeId.of(0), NodeId.of(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RegionMap(
                new RegionGrid(16, 2),
                List.of(NodeId.of(0), NodeId.of(1), NodeId.of(2), NodeId.of(1))));
  }
}
