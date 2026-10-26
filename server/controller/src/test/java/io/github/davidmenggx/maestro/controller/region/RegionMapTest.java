package io.github.davidmenggx.maestro.controller.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionMapTest {

  private static final int CELL = RegionLayout.CELL;

  /** Two by two cells, from -128 to 383. */
  private static final RegionLayout LAYOUT = new RegionLayout(2);

  private static final int MIN = LAYOUT.min();

  @Test
  void aRegionIsWholeChunksStartingAtAChunk() {
    assertThrows(IllegalArgumentException.class, () -> new Region(1, 0, 0, 24, 16));
    assertThrows(IllegalArgumentException.class, () -> new Region(1, 0, 0, 16, 24));
    assertThrows(IllegalArgumentException.class, () -> new Region(1, 8, 0, 16, 16));
    assertThrows(IllegalArgumentException.class, () -> new Region(0, 0, 0, 16, 16));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Region(1, Integer.MAX_VALUE - 15 - Region.HALO, 0, 16, 16));
    assertThrows(IllegalArgumentException.class, () -> new Region(1, Integer.MIN_VALUE, 0, 16, 16));

    Region region = new Region(1, -32, -16, 32, 48);
    assertTrue(region.contains(-32, -16));
    assertTrue(region.contains(-1, 31));
    assertFalse(region.contains(0, 0));
    assertFalse(region.contains(-33, 0));
    assertFalse(region.contains(-1, 32));
  }

  @Test
  void anAreaIsTheRegionAndAHaloAroundIt() {
    Region region = new Region(1, 0, 256, 512, 256);
    assertEquals(-Region.HALO, region.areaMinX());
    assertEquals(256 - Region.HALO, region.areaMinZ());
    assertEquals(512 + 2 * Region.HALO, region.areaSizeX());
    assertEquals(256 + 2 * Region.HALO, region.areaSizeZ());
    assertTrue(region.inArea(-Region.HALO, 256 - Region.HALO));
    assertTrue(region.inArea(511 + Region.HALO, 511 + Region.HALO));
    assertFalse(region.inArea(-Region.HALO - 1, 300));
    assertFalse(region.inArea(300, 512 + Region.HALO));
  }

  @Test
  void idsComeFromACounterAndRegionsAreWholeCellsThatNeverOverlap() {
    RegionMap map = new RegionMap(LAYOUT);
    Region first = map.add(MIN, MIN, CELL, CELL);
    Region second = map.add(MIN + CELL, MIN, CELL, 2 * CELL);
    assertEquals(1, first.id());
    assertEquals(2, second.id());
    // A refused region takes no id.
    assertThrows(IllegalArgumentException.class, () -> map.add(MIN, MIN, CELL, 2 * CELL));
    assertThrows(IllegalArgumentException.class, () -> map.add(MIN + 16, MIN, CELL, CELL));
    assertThrows(IllegalArgumentException.class, () -> map.add(MIN, MIN + CELL, CELL - 16, CELL));
    assertThrows(IllegalArgumentException.class, () -> map.add(MIN - CELL, MIN, CELL, CELL));
    assertThrows(IllegalArgumentException.class, () -> map.add(MIN, MIN + CELL, CELL, 2 * CELL));
    assertEquals(3, map.add(MIN, MIN + CELL, CELL, CELL).id());

    assertEquals(first, map.regionAt(MIN, MIN + CELL - 1));
    assertEquals(second, map.regionAt(MIN + 2 * CELL - 1, MIN + 2 * CELL - 1));
    assertNull(map.regionAt(MIN - 1, MIN));
    assertEquals(List.of(first, second, map.region(3)), List.copyOf(map.regions()));
    assertThrows(IllegalArgumentException.class, () -> map.region(4));
  }

  @Test
  void aBlockNearAnEdgeIsInTheAreasOfTheRegionsBesideIt() {
    RegionMap map = LAYOUT.initial(nodes(4), 0);
    Region southWest = map.regionAt(MIN, MIN);
    Region southEast = map.regionAt(MIN + CELL, MIN);
    Region northWest = map.regionAt(MIN, MIN + CELL);
    Region northEast = map.regionAt(MIN + CELL, MIN + CELL);
    int edge = MIN + CELL;

    assertEquals(List.of(southWest), map.reaching(MIN, MIN));
    // Within a halo of the edge between two regions, both reach it.
    assertEquals(List.of(southWest, southEast), sorted(map.reaching(edge - 1, MIN)));
    assertEquals(List.of(southWest, southEast), sorted(map.reaching(edge + Region.HALO - 1, MIN)));
    assertEquals(List.of(southEast), map.reaching(edge + Region.HALO, MIN));
    // At a corner, all four.
    assertEquals(4, map.reaching(edge, edge).size());
    // Past the square, only the regions whose halo covers it.
    assertEquals(List.of(southWest), map.reaching(MIN - 1, MIN));
    assertEquals(List.of(), map.reaching(MIN - Region.HALO - 1, MIN));
    assertEquals(List.of(northEast), map.reaching(MIN + 2 * CELL + Region.HALO - 1, edge + 200));
    assertTrue(map.reaching(MIN, MIN + 2 * CELL).contains(northWest));
  }

  @Test
  void aNodeHoldsOneRegionAndARegionMayHaveSeveralHolders() {
    RegionMap map = new RegionMap(LAYOUT);
    Region region = map.add(MIN, MIN, CELL, CELL);
    Region other = map.add(MIN + CELL, MIN, CELL, CELL);
    map.assign(region.id(), NodeId.of(4));
    map.assign(region.id(), NodeId.of(1));
    map.assign(other.id(), NodeId.of(0));

    assertEquals(List.of(NodeId.of(4), NodeId.of(1)), map.holders(region.id()));
    assertEquals(List.of(NodeId.of(4), NodeId.of(1), NodeId.of(0)), map.nodes());
    assertEquals(region.id(), map.regionOf(NodeId.of(1)));
    assertEquals(Regions.NONE, map.regionOf(NodeId.of(9)));
    assertThrows(IllegalArgumentException.class, () -> map.assign(other.id(), NodeId.of(4)));
    assertThrows(IllegalArgumentException.class, () -> map.assign(7, NodeId.of(9)));

    assertEquals(region.id(), map.unassign(NodeId.of(4)));
    assertEquals(List.of(NodeId.of(1)), map.holders(region.id()));
    assertEquals(Regions.NONE, map.regionOf(NodeId.of(4)));
    assertEquals(Regions.NONE, map.unassign(NodeId.of(4)));
    map.assign(other.id(), NodeId.of(4));
    assertEquals(List.of(NodeId.of(0), NodeId.of(4)), map.holders(other.id()));
  }

  @Test
  void aSplitReplacesARegionWithItsSidesUnderNewIds() {
    RegionMap map = LAYOUT.initial(nodes(1), 0);
    Region whole = map.region(1);
    RegionLayout.Cut half = RegionLayout.halving(whole.sizeX(), whole.sizeZ());
    assertThrows(IllegalArgumentException.class, () -> map.split(whole.id(), half));
    map.unassign(NodeId.of(0));

    List<Region> halves = map.split(whole.id(), half);

    assertEquals(List.of(2, 3), halves.stream().map(Region::id).toList());
    assertEquals(new Region(2, MIN, MIN, CELL, 2 * CELL), halves.get(0));
    assertEquals(new Region(3, MIN + CELL, MIN, CELL, 2 * CELL), halves.get(1));
    assertThrows(IllegalArgumentException.class, () -> map.region(whole.id()));
    assertEquals(halves, List.copyOf(map.regions()));
    List<Region> quarters = map.split(halves.get(1).id(), new RegionLayout.Cut(false, 1));
    assertEquals(new Region(4, MIN + CELL, MIN, CELL, CELL), quarters.get(0));
    assertEquals(new Region(5, MIN + CELL, MIN + CELL, CELL, CELL), quarters.get(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> map.split(quarters.get(0).id(), new RegionLayout.Cut(true, 1)));
    List<String> found = new ArrayList<>();
    map.check(found);
    assertEquals(List.of(), found);
  }

  @Test
  void theCheckFindsAnUncoveredSquare() {
    RegionMap map = new RegionMap(LAYOUT);
    map.add(MIN, MIN, CELL, CELL);
    List<String> found = new ArrayList<>();
    map.check(found);
    assertEquals(1, found.size(), found.toString());
  }

  private static List<Region> sorted(List<Region> regions) {
    List<Region> copy = new ArrayList<>(regions);
    copy.sort((a, b) -> Integer.compare(a.id(), b.id()));
    return copy;
  }

  private static List<NodeId> nodes(int count) {
    List<NodeId> nodes = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      nodes.add(NodeId.of(i));
    }
    return nodes;
  }
}
