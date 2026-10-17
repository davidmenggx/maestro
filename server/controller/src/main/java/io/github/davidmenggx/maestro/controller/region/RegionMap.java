package io.github.davidmenggx.maestro.controller.region;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which node holds which region of a {@link RegionGrid}. A region's id is its place in the grid,
 * and names its world stream whichever node holds it. A node holds at most one region, because it
 * holds one area.
 */
public final class RegionMap {

  private final RegionGrid grid;
  private final List<NodeId> owners;
  private final Map<NodeId, Integer> regionByNode = new LinkedHashMap<>();

  /** {@code owners.get(r)} holds region {@code r}. */
  public RegionMap(RegionGrid grid, List<NodeId> owners) {
    this.grid = grid;
    this.owners = List.copyOf(owners);
    if (this.owners.size() != grid.regionCount()) {
      throw new IllegalArgumentException(
          "one owner per region: " + owners.size() + " for " + grid.regionCount() + " regions");
    }
    for (int region = 0; region < this.owners.size(); region++) {
      if (regionByNode.put(this.owners.get(region), region) != null) {
        throw new IllegalArgumentException(this.owners.get(region) + " holds two regions");
      }
    }
  }

  public RegionGrid grid() {
    return grid;
  }

  public int regionCount() {
    return grid.regionCount();
  }

  /** The fleet, in region order. */
  public List<NodeId> nodes() {
    return owners;
  }

  /** The region holding the position, or -1 when it falls outside the grid. */
  public int regionAt(int x, int z) {
    return grid.regionAt(x, z);
  }

  public NodeId owner(int region) {
    if (region < 0 || region >= owners.size()) {
      throw new IllegalArgumentException("no such region: " + region);
    }
    return owners.get(region);
  }

  /** The region the node holds, or -1 if it holds none. */
  public int regionOf(NodeId node) {
    return regionByNode.getOrDefault(node, -1);
  }

  public int minX(int region) {
    return grid.minX(region);
  }

  public int minZ(int region) {
    return grid.minZ(region);
  }

  public int regionSize() {
    return grid.regionSize();
  }
}
