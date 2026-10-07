#pragma once

#include <array>

#include "maestro/search/binary_heap.hpp"
#include "maestro/search/search.hpp"
#include "maestro/search/walk_node_evaluator.hpp"

namespace maestro::search {

/// Port of vanilla's `PathFinder` driving a `WalkNodeEvaluator`, decision for
/// decision, so that given the same world and inputs it returns the same path.
/// That includes vanilla's quirks: the heuristic is weighted by 1.5, a node's
/// walked distance is overwritten even when the path through it is not taken,
/// and the result ends at the node nearest the target rather than at the node
/// that reached it.
class VanillaSearch final : public Search {
public:
  VanillaSearch() : OpenSet(Evaluator.nodes()) {}

  fbs::Pathfinder kind() const override { return fbs::Pathfinder::VANILLA; }

  void find(const world::Area &Area, const Profile &Mob, const Request &Call,
            Result &Out) override;

private:
  /// `getBestH`: the distance to the target, recording the nearest node seen.
  float getBestH(NodeId Id);

  WalkNodeEvaluator Evaluator;
  BinaryHeap OpenSet;
  std::array<NodeId, 32> Neighbors{};

  /// Vanilla's `Target`: the target position and the nearest node to it.
  Node Goal;
  float BestHeuristic = 0.0F;
  NodeId BestNode = NoNode;
};

} // namespace maestro::search
