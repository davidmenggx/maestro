package io.github.davidmenggx.maestro.controller.region;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.Collection;
import java.util.List;

/** The regions of one run and the nodes holding them, read only. */
public interface Regions {

  /** {@link #regionOf} for a node that holds no region. */
  int NONE = 0;

  Region region(int regionId);

  /** The region holding the position, or null if none does. */
  Region regionAt(int x, int z);

  /** The regions whose area holds the position, in id order. */
  List<Region> reaching(int x, int z);

  /** Every region, in id order. */
  Collection<Region> regions();

  /** The nodes holding the region, in the order they were assigned. */
  List<NodeId> holders(int regionId);

  /** The id of the region the node holds, or {@link #NONE}. */
  int regionOf(NodeId node);

  /** Every node holding a region, by region id, then in the order assigned. */
  List<NodeId> nodes();
}
