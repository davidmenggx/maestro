package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.List;

/**
 * Which node owns which region of a {@link Topology}. Resharding is controller logic and is not
 * modeled here. The harness only needs each position to have a fixed owner.
 */
public record RegionMap(Topology topology, List<NodeId> nodes) {

  public RegionMap {
    nodes = List.copyOf(nodes);
    if (nodes.size() != topology.nodeCount()) {
      throw new IllegalArgumentException(
          "one node per region: "
              + nodes.size()
              + " nodes for "
              + topology.nodeCount()
              + " regions");
    }
  }

  public int count() {
    return topology.nodeCount();
  }

  /** Region holding the position, or -1 when it falls outside the grid. */
  public int regionAt(int x, int z) {
    int rx = Math.floorDiv(x, topology.regionSize());
    int rz = Math.floorDiv(z, topology.regionSize());
    if (rx < 0 || rx >= topology.regionsPerSide() || rz < 0 || rz >= topology.regionsPerSide()) {
      return -1;
    }
    return rx * topology.regionsPerSide() + rz;
  }

  public NodeId owner(int regionIndex) {
    return nodes.get(requireRegion(regionIndex));
  }

  public int indexOf(NodeId node) {
    return nodes.indexOf(node);
  }

  public int minX(int regionIndex) {
    return (requireRegion(regionIndex) / topology.regionsPerSide()) * topology.regionSize();
  }

  public int minZ(int regionIndex) {
    return (requireRegion(regionIndex) % topology.regionsPerSide()) * topology.regionSize();
  }

  private int requireRegion(int regionIndex) {
    if (regionIndex < 0 || regionIndex >= count()) {
      throw new IllegalArgumentException("no such region: " + regionIndex);
    }
    return regionIndex;
  }
}
