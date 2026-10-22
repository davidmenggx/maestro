package io.github.davidmenggx.maestro.controller.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionMapTest {

  @Test
  void aRegionIsWholeChunksStartingAtAChunk() {
    assertThrows(IllegalArgumentException.class, () -> new Region(1, 0, 0, 24));
    assertThrows(IllegalArgumentException.class, () -> new Region(1, 8, 0, 16));
    assertThrows(IllegalArgumentException.class, () -> new Region(0, 0, 0, 16));
    assertThrows(
        IllegalArgumentException.class, () -> new Region(1, Integer.MAX_VALUE - 15, 0, 32));

    Region region = new Region(1, -32, -16, 32);
    assertTrue(region.contains(-32, -16));
    assertTrue(region.contains(-1, 15));
    assertFalse(region.contains(0, 0));
    assertFalse(region.contains(-33, 0));
    assertFalse(region.contains(-1, 16));
  }

  @Test
  void idsComeFromACounterAndRegionsNeverOverlap() {
    RegionMap map = new RegionMap();
    Region first = map.add(-256, -256, 256);
    Region second = map.add(0, -256, 256);
    assertEquals(1, first.id());
    assertEquals(2, second.id());
    // A refused region takes no id.
    assertThrows(IllegalArgumentException.class, () -> map.add(-128, -128, 256));
    assertEquals(3, map.add(0, 0, 256).id());

    assertEquals(first, map.regionAt(-256, -1));
    assertEquals(second, map.regionAt(255, -256));
    assertNull(map.regionAt(-257, 0));
    assertNull(map.regionAt(-1, 0));
    assertEquals(List.of(first, second, map.region(3)), List.copyOf(map.regions()));
    assertThrows(IllegalArgumentException.class, () -> map.region(4));
  }

  @Test
  void aNodeHoldsOneRegionAndARegionMayHaveSeveralHolders() {
    RegionMap map = new RegionMap();
    Region region = map.add(0, 0, 16);
    Region other = map.add(16, 0, 16);
    map.assign(region.id(), NodeId.of(4));
    map.assign(region.id(), NodeId.of(1));
    map.assign(other.id(), NodeId.of(0));

    assertEquals(List.of(NodeId.of(4), NodeId.of(1)), map.holders(region.id()));
    assertEquals(List.of(NodeId.of(4), NodeId.of(1), NodeId.of(0)), map.nodes());
    assertEquals(region.id(), map.regionOf(NodeId.of(1)));
    assertEquals(RegionMap.NONE, map.regionOf(NodeId.of(9)));
    assertThrows(IllegalArgumentException.class, () -> map.assign(other.id(), NodeId.of(4)));
    assertThrows(IllegalArgumentException.class, () -> map.assign(7, NodeId.of(9)));
  }
}
