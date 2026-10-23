#pragma once

#include <cstddef>
#include <vector>

#include "maestro/search/node.hpp"

namespace maestro::search {

/// Vanilla's `BinaryHeap`: a min-heap on `Node::F` that keeps each node's
/// `HeapIdx` up to date. Ties are ported exactly, since they pick the path.
class BinaryHeap {
public:
  /// Heap entries are indices into `Nodes`, which may grow during use.
  explicit BinaryHeap(std::vector<Node> &Nodes) : Nodes(Nodes) {}

  /// `Id` must not already be in the heap.
  void insert(NodeId Id);

  /// Removes and returns the node with the lowest `F`. The heap must not be
  /// empty.
  NodeId pop();

  /// Sets `F` for a node in the heap and restores the heap order.
  void changeCost(NodeId Id, float NewCost);

  void clear() { Heap.clear(); }
  bool empty() const { return Heap.empty(); }
  std::size_t size() const { return Heap.size(); }

private:
  void upHeap(std::size_t Idx);
  void downHeap(std::size_t Idx);

  std::vector<Node> &Nodes;
  std::vector<NodeId> Heap;
};

} // namespace maestro::search
