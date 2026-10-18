package io.github.davidmenggx.maestro.controller.region;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The regions of one controller run and the nodes holding them. Ids come from a counter, so none is
 * reused. Regions never overlap. A node holds at most one region, because it holds one area, and a
 * region may have several holders, which are all sent its one world stream and share its requests.
 */
public final class RegionMap {

  /** {@link #regionOf} for a node that holds no region. */
  public static final int NONE = 0;

  private final Map<Integer, Region> regions = new LinkedHashMap<>();
  private final Map<Integer, List<NodeId>> holders = new LinkedHashMap<>();
  private final Map<NodeId, Integer> regionByNode = new LinkedHashMap<>();
  private int nextId = 1;

  /** A new region, held by no node yet. */
  public Region add(int minX, int minZ, int size) {
    Region region = new Region(nextId, minX, minZ, size);
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

  /** {@code node}, which holds no region, holds {@code regionId} from now on, after its others. */
  public void assign(int regionId, NodeId node) {
    region(regionId);
    if (regionByNode.containsKey(node)) {
      throw new IllegalArgumentException(node + " already holds region " + regionByNode.get(node));
    }
    regionByNode.put(node, regionId);
    holders.get(regionId).add(node);
  }

  public Region region(int regionId) {
    Region region = regions.get(regionId);
    if (region == null) {
      throw new IllegalArgumentException("no such region: " + regionId);
    }
    return region;
  }

  /** The region holding the position, or null if none does. */
  public Region regionAt(int x, int z) {
    for (Region region : regions.values()) {
      if (region.contains(x, z)) {
        return region;
      }
    }
    return null;
  }

  /** Every region, in id order. */
  public Collection<Region> regions() {
    return Collections.unmodifiableCollection(regions.values());
  }

  /** The nodes holding the region, in the order they were assigned. */
  public List<NodeId> holders(int regionId) {
    region(regionId);
    return Collections.unmodifiableList(holders.get(regionId));
  }

  /** The id of the region the node holds, or {@link #NONE}. */
  public int regionOf(NodeId node) {
    return regionByNode.getOrDefault(node, NONE);
  }

  /** Every node holding a region, by region id, then in the order assigned. */
  public List<NodeId> nodes() {
    List<NodeId> all = new ArrayList<>();
    for (List<NodeId> held : holders.values()) {
      all.addAll(held);
    }
    return all;
  }
}
