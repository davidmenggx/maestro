#include "maestro/search/binary_heap.hpp"

#include <cassert>
#include <limits>

namespace maestro::search {

void BinaryHeap::insert(NodeId Id) {
  assert(!Nodes[Id].inOpenSet() && "node is already in the heap");
  Heap.push_back(Id);
  Nodes[Id].HeapIdx = static_cast<int>(Heap.size() - 1);
  upHeap(Heap.size() - 1);
}

NodeId BinaryHeap::pop() {
  assert(!Heap.empty() && "pop from an empty heap");
  const NodeId Popped = Heap.front();
  Heap.front() = Heap.back();
  Heap.pop_back();
  if (!Heap.empty())
    downHeap(0);
  Nodes[Popped].HeapIdx = -1;
  return Popped;
}

void BinaryHeap::changeCost(NodeId Id, float NewCost) {
  Node &Changed = Nodes[Id];
  const float OldCost = Changed.F;
  Changed.F = NewCost;
  if (NewCost < OldCost)
    upHeap(static_cast<std::size_t>(Changed.HeapIdx));
  else
    downHeap(static_cast<std::size_t>(Changed.HeapIdx));
}

void BinaryHeap::upHeap(std::size_t Idx) {
  const NodeId Moving = Heap[Idx];
  const float Cost = Nodes[Moving].F;
  while (Idx > 0) {
    const std::size_t ParentIdx = (Idx - 1) >> 1;
    const NodeId Parent = Heap[ParentIdx];
    if (!(Cost < Nodes[Parent].F))
      break;
    Heap[Idx] = Parent;
    Nodes[Parent].HeapIdx = static_cast<int>(Idx);
    Idx = ParentIdx;
  }
  Heap[Idx] = Moving;
  Nodes[Moving].HeapIdx = static_cast<int>(Idx);
}

void BinaryHeap::downHeap(std::size_t Idx) {
  const NodeId Moving = Heap[Idx];
  const float Cost = Nodes[Moving].F;
  while (true) {
    const std::size_t LeftIdx = 1 + (Idx << 1);
    const std::size_t RightIdx = LeftIdx + 1;
    if (LeftIdx >= Heap.size())
      break;

    const NodeId Left = Heap[LeftIdx];
    const float LeftCost = Nodes[Left].F;
    const bool HasRight = RightIdx < Heap.size();
    const float RightCost = HasRight ? Nodes[Heap[RightIdx]].F
                                     : std::numeric_limits<float>::infinity();

    // As in vanilla, a tie between the children picks the right one.
    if (LeftCost < RightCost) {
      if (!(LeftCost < Cost))
        break;
      Heap[Idx] = Left;
      Nodes[Left].HeapIdx = static_cast<int>(Idx);
      Idx = LeftIdx;
    } else {
      if (!(RightCost < Cost))
        break;
      const NodeId Right = Heap[RightIdx];
      Heap[Idx] = Right;
      Nodes[Right].HeapIdx = static_cast<int>(Idx);
      Idx = RightIdx;
    }
  }
  Heap[Idx] = Moving;
  Nodes[Moving].HeapIdx = static_cast<int>(Idx);
}

} // namespace maestro::search
