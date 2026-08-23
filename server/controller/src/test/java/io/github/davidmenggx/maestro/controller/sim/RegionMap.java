package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.world.Pos;
import java.util.List;

/**
 * Square, full-height regions laid out on a grid, one per node. Resharding is controller logic and
 * is not modeled here: the harness only needs a stable owner per position.
 */
public record RegionMap(int regionSize, int regionsPerSide, List<NodeId> nodes) {

  /** Chunk width. A region has to be chunk aligned, or residency cannot be decided per region. */
  private static final int CHUNK_SIZE = 16;

  public RegionMap {
    nodes = List.copyOf(nodes);
    if (regionSize <= 0 || regionSize % CHUNK_SIZE != 0) {
      throw new IllegalArgumentException("region size must be a multiple of " + CHUNK_SIZE);
    }
    if (nodes.size() != regionsPerSide * regionsPerSide) {
      throw new IllegalArgumentException("one node per region: " + nodes.size() + " nodes");
    }
  }

  /** Block extent of the whole grid along one axis. */
  public int extent() {
    return regionSize * regionsPerSide;
  }

  public int count() {
    return regionsPerSide * regionsPerSide;
  }

  /** Region holding the position, or -1 when it falls outside the grid. */
  public int regionIndex(Pos pos) {
    int rx = Math.floorDiv(pos.x(), regionSize);
    int rz = Math.floorDiv(pos.z(), regionSize);
    if (rx < 0 || rx >= regionsPerSide || rz < 0 || rz >= regionsPerSide) {
      return -1;
    }
    return rx * regionsPerSide + rz;
  }

  public NodeId owner(int regionIndex) {
    return nodes.get(requireRegion(regionIndex));
  }

  public int indexOf(NodeId node) {
    return nodes.indexOf(node);
  }

  public int minX(int regionIndex) {
    return (requireRegion(regionIndex) / regionsPerSide) * regionSize;
  }

  public int minZ(int regionIndex) {
    return (requireRegion(regionIndex) % regionsPerSide) * regionSize;
  }

  private int requireRegion(int regionIndex) {
    if (regionIndex < 0 || regionIndex >= count()) {
      throw new IllegalArgumentException("no such region: " + regionIndex);
    }
    return regionIndex;
  }
}
