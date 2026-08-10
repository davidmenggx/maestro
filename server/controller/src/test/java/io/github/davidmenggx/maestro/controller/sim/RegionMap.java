package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.world.Pos;
import java.util.List;

/**
 * Square, full-height regions laid out on a grid, one per node. Resharding is controller logic and
 * is not modeled here: the harness only needs a stable owner per position.
 */
public record RegionMap(int regionSize, int regionsPerSide, List<NodeId> nodes) {

  public RegionMap {
    nodes = List.copyOf(nodes);
    if (nodes.size() != regionsPerSide * regionsPerSide) {
      throw new IllegalArgumentException("one node per region: " + nodes.size() + " nodes");
    }
  }

  public int count() {
    return regionsPerSide * regionsPerSide;
  }

  public int regionIndex(int x, int z) {
    int rx = Math.floorDiv(x, regionSize);
    int rz = Math.floorDiv(z, regionSize);
    if (rx < 0 || rx >= regionsPerSide || rz < 0 || rz >= regionsPerSide) {
      return -1;
    }
    return rx * regionsPerSide + rz;
  }

  public int regionIndex(Pos pos) {
    return regionIndex(pos.x(), pos.z());
  }

  public NodeId owner(int regionIndex) {
    return nodes.get(regionIndex);
  }

  public int indexOf(NodeId node) {
    return nodes.indexOf(node);
  }

  public int minX(int regionIndex) {
    return (regionIndex / regionsPerSide) * regionSize;
  }

  public int minZ(int regionIndex) {
    return (regionIndex % regionsPerSide) * regionSize;
  }
}
