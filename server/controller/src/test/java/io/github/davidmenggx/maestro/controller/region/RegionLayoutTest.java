package io.github.davidmenggx.maestro.controller.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.region.RegionLayout.Cell;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RegionLayoutTest {

  @Test
  void theOriginIsTheCenterOfItsCell() {
    assertEquals(-RegionLayout.SIZE / 2, RegionLayout.cellMin(0));
    assertEquals(RegionLayout.SIZE / 2, RegionLayout.cellMin(1));
    assertEquals(-3 * RegionLayout.SIZE / 2, RegionLayout.cellMin(-1));
  }

  @Test
  void theSpiralStartsAtTheOriginAndTurnsOutward() {
    assertEquals(
        List.of(
            new Cell(0, 0),
            new Cell(1, 0),
            new Cell(1, 1),
            new Cell(0, 1),
            new Cell(-1, 1),
            new Cell(-1, 0),
            new Cell(-1, -1),
            new Cell(0, -1),
            new Cell(1, -1),
            new Cell(2, -1)),
        RegionLayout.spiral(10));
    assertEquals(List.of(), RegionLayout.spiral(0));
    assertThrows(IllegalArgumentException.class, () -> RegionLayout.spiral(-1));
  }

  @Test
  void theFirstSquareNumberOfCellsFormASquare() {
    for (int side = 1; side <= 6; side++) {
      List<Cell> cells = RegionLayout.spiral(side * side);
      assertEquals(side * side, new TreeSet<>(cells.stream().map(Cell::toString).toList()).size());
      int minX = cells.stream().mapToInt(Cell::x).min().orElseThrow();
      int maxX = cells.stream().mapToInt(Cell::x).max().orElseThrow();
      int minZ = cells.stream().mapToInt(Cell::z).min().orElseThrow();
      int maxZ = cells.stream().mapToInt(Cell::z).max().orElseThrow();
      assertEquals(side - 1, maxX - minX, "side " + side);
      assertEquals(side - 1, maxZ - minZ, "side " + side);
      // As many cells below the origin's as above it, or one fewer.
      assertEquals(-((side - 1) / 2), minX, "side " + side);
    }
  }

  @Test
  void eachNodeHoldsTheNextCellOfTheSpiral() {
    List<NodeId> nodes = IntStream.range(0, 5).mapToObj(NodeId::of).toList();

    RegionMap map = RegionLayout.spiral(nodes);

    assertEquals(nodes, map.nodes());
    List<Cell> cells = RegionLayout.spiral(nodes.size());
    for (int i = 0; i < nodes.size(); i++) {
      Region region = map.region(map.regionOf(nodes.get(i)));
      assertEquals(RegionLayout.cellMin(cells.get(i).x()), region.minX());
      assertEquals(RegionLayout.cellMin(cells.get(i).z()), region.minZ());
      assertEquals(RegionLayout.SIZE, region.size());
      assertEquals(List.of(nodes.get(i)), map.holders(region.id()));
    }
    assertTrue(map.regionAt(0, 0) != null && map.regionAt(0, 0).id() == 1);
  }
}
