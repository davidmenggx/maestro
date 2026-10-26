package io.github.davidmenggx.maestro.controller.region;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The regions of one run and the nodes holding them. Regions are rectangles of whole cells of the
 * {@link RegionLayout}, inside its square, and never overlap. Ids come from a counter and are never
 * reused. A node holds at most one region, and a region may have several holders, all sent its one
 * world stream and sharing its requests.
 */
public final class RegionMap implements Regions {

  private final RegionLayout layout;
  private final Map<Integer, Region> regions = new LinkedHashMap<>();
  private final Map<Integer, List<NodeId>> holders = new LinkedHashMap<>();
  private final Map<NodeId, Integer> regionByNode = new LinkedHashMap<>();
  private int nextId = 1;

  public RegionMap(RegionLayout layout) {
    this.layout = layout;
  }

  /** A new region of whole cells inside the square, held by no node yet. */
  public Region add(int minX, int minZ, int sizeX, int sizeZ) {
    int min = layout.min();
    if (Math.floorMod(minX - min, RegionLayout.CELL) != 0
        || Math.floorMod(minZ - min, RegionLayout.CELL) != 0
        || sizeX % RegionLayout.CELL != 0
        || sizeZ % RegionLayout.CELL != 0
        || minX < min
        || minZ < min
        || (long) minX + sizeX > (long) min + layout.size()
        || (long) minZ + sizeZ > (long) min + layout.size()) {
      throw new IllegalArgumentException(
          "not whole cells of " + layout + ": " + minX + "," + minZ + " " + sizeX + "x" + sizeZ);
    }
    Region region = new Region(nextId, minX, minZ, sizeX, sizeZ);
    for (Region other : regions.values()) {
      if (region.overlaps(other)) {
        throw new IllegalArgumentException(region + " overlaps " + other);
      }
    }
    nextId++;
    regions.put(region.id(), region);
    holders.put(region.id(), new ArrayList<>());
    return region;
  }

  /** Replaces a region no node holds with its two {@link RegionLayout#sides} of {@code cut}. */
  public List<Region> split(int regionId, RegionLayout.Cut cut) {
    Region region = region(regionId);
    if (!holders.get(regionId).isEmpty()) {
      throw new IllegalArgumentException("region " + regionId + " is still held");
    }
    List<int[]> sides =
        RegionLayout.sides(region.minX(), region.minZ(), region.sizeX(), region.sizeZ(), cut);
    regions.remove(regionId);
    holders.remove(regionId);
    List<Region> made = new ArrayList<>(sides.size());
    for (int[] side : sides) {
      made.add(add(side[0], side[1], side[2], side[3]));
    }
    return made;
  }

  /** {@code node}, which holds no region, holds {@code regionId} from now on, after its others. */
  public void assign(int regionId, NodeId node) {
    region(regionId);
    if (regionByNode.containsKey(node)) {
      throw new IllegalArgumentException(node + " already holds region " + regionByNode.get(node));
    }
    regionByNode.put(node, regionId);
    holders.get(regionId).add(node);
  }

  /** {@code node} holds no region from now on. Returns the one it held, or {@link #NONE}. */
  public int unassign(NodeId node) {
    Integer regionId = regionByNode.remove(node);
    if (regionId == null) {
      return NONE;
    }
    holders.get(regionId).remove(node);
    return regionId;
  }

  @Override
  public Region region(int regionId) {
    Region region = regions.get(regionId);
    if (region == null) {
      throw new IllegalArgumentException("no such region: " + regionId);
    }
    return region;
  }

  @Override
  public Region regionAt(int x, int z) {
    if (!layout.covers(x, z)) {
      return null;
    }
    for (Region region : regions.values()) {
      if (region.contains(x, z)) {
        return region;
      }
    }
    return null;
  }

  @Override
  public List<Region> reaching(int x, int z) {
    if (!layout.reaches(x, z)) {
      return List.of();
    }
    List<Region> found = new ArrayList<>(4);
    for (Region region : regions.values()) {
      if (region.inArea(x, z)) {
        found.add(region);
      }
    }
    return found;
  }

  @Override
  public Collection<Region> regions() {
    return Collections.unmodifiableCollection(regions.values());
  }

  @Override
  public List<NodeId> holders(int regionId) {
    region(regionId);
    return Collections.unmodifiableList(holders.get(regionId));
  }

  @Override
  public int regionOf(NodeId node) {
    return regionByNode.getOrDefault(node, NONE);
  }

  @Override
  public List<NodeId> nodes() {
    List<NodeId> all = new ArrayList<>();
    for (List<NodeId> held : holders.values()) {
      all.addAll(held);
    }
    return all;
  }

  /** Regions that leave part of the square uncovered, and holder indexes that disagree. */
  public void check(List<String> found) {
    long covered = 0;
    for (Region region : regions.values()) {
      covered += (long) region.sizeX() * region.sizeZ();
    }
    if (covered != (long) layout.size() * layout.size()) {
      found.add("regions cover " + covered + " blocks of a square of " + layout.size());
    }
    int held = 0;
    for (Map.Entry<Integer, List<NodeId>> entry : holders.entrySet()) {
      for (NodeId node : entry.getValue()) {
        held++;
        if (!entry.getKey().equals(regionByNode.get(node))) {
          found.add(node + " holds region " + entry.getKey() + " but is indexed elsewhere");
        }
      }
    }
    if (held != regionByNode.size()) {
      found.add(held + " holders but " + regionByNode.size() + " nodes indexed");
    }
  }
}
