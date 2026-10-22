#include "maestro/search/binary_heap.hpp"

#include <vector>

#include <gtest/gtest.h>

using namespace maestro::search;

namespace {

std::vector<Node> withCosts(std::initializer_list<float> Costs) {
  std::vector<Node> Nodes;
  for (float Cost : Costs)
    Nodes.push_back(Node{.F = Cost});
  return Nodes;
}

std::vector<NodeId> drain(BinaryHeap &Heap) {
  std::vector<NodeId> Order;
  while (!Heap.empty())
    Order.push_back(Heap.pop());
  return Order;
}

} // namespace

TEST(BinaryHeap, PopsInCostOrder) {
  std::vector<Node> Nodes = withCosts({5.0F, 1.0F, 4.0F, 2.0F, 3.0F});
  BinaryHeap Heap(Nodes);
  for (NodeId Id = 0; Id < Nodes.size(); ++Id)
    Heap.insert(Id);
  EXPECT_EQ(drain(Heap), (std::vector<NodeId>{1, 3, 4, 2, 0}));
}

TEST(BinaryHeap, BreaksTiesAsVanillaDoes) {
  // Sift-down moves only for a strictly lower child cost, so equal costs do
  // not leave in insertion order.
  std::vector<Node> Nodes = withCosts({1.0F, 1.0F, 1.0F});
  BinaryHeap Heap(Nodes);
  for (NodeId Id = 0; Id < Nodes.size(); ++Id)
    Heap.insert(Id);
  EXPECT_EQ(drain(Heap), (std::vector<NodeId>{0, 2, 1}));
}

TEST(BinaryHeap, TiesBetweenChildrenGoRight) {
  std::vector<Node> Nodes = withCosts({0.0F, 2.0F, 2.0F, 5.0F});
  BinaryHeap Heap(Nodes);
  for (NodeId Id = 0; Id < Nodes.size(); ++Id)
    Heap.insert(Id);
  // Popping 0 moves 3 to the root, which sinks to the right child.
  EXPECT_EQ(Heap.pop(), 0u);
  EXPECT_EQ(Heap.pop(), 2u);
  EXPECT_EQ(Heap.pop(), 1u);
}

TEST(BinaryHeap, TracksWhereEachNodeIs) {
  std::vector<Node> Nodes = withCosts({3.0F, 1.0F, 2.0F});
  BinaryHeap Heap(Nodes);
  for (NodeId Id = 0; Id < Nodes.size(); ++Id)
    Heap.insert(Id);
  for (const Node &N : Nodes)
    EXPECT_TRUE(N.inOpenSet());
  const NodeId First = Heap.pop();
  EXPECT_FALSE(Nodes[First].inOpenSet());
  EXPECT_EQ(Heap.size(), 2u);
}

TEST(BinaryHeap, AChangedCostMovesTheNodeEitherWay) {
  std::vector<Node> Nodes = withCosts({1.0F, 2.0F, 3.0F, 4.0F});
  BinaryHeap Heap(Nodes);
  for (NodeId Id = 0; Id < Nodes.size(); ++Id)
    Heap.insert(Id);
  Heap.changeCost(3, 0.5F);
  Heap.changeCost(0, 10.0F);
  EXPECT_EQ(Nodes[3].F, 0.5F);
  EXPECT_EQ(drain(Heap), (std::vector<NodeId>{3, 1, 2, 0}));
}

TEST(BinaryHeap, KeepsWorkingWhileTheNodeTableGrows) {
  std::vector<Node> Nodes = withCosts({2.0F});
  BinaryHeap Heap(Nodes);
  Heap.insert(0);
  for (int I = 0; I < 1000; ++I)
    Nodes.push_back(Node{.F = 3.0F});
  Nodes.push_back(Node{.F = 1.0F});
  Heap.insert(static_cast<NodeId>(Nodes.size() - 1));
  EXPECT_EQ(Heap.pop(), Nodes.size() - 1);
  EXPECT_EQ(Heap.pop(), 0u);
}
