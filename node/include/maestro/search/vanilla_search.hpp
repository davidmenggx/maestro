#pragma once

#include <array>

#include "maestro/search/binary_heap.hpp"
#include "maestro/search/search.hpp"
#include "maestro/search/walk_node_evaluator.hpp"

namespace maestro::search {

/// Port of vanilla's `PathFinder` driving a `WalkNodeEvaluator`, decision for
/// decision, so the same world and inputs give the same path. This keeps
/// vanilla's quirks: the heuristic is weighted by 1.5, a node's walked
/// distance is overwritten even when the path through it is not taken, and the
/// result ends at the node nearest the target, not the node that reached it.
class VanillaSearch final : public Search {
public:
  VanillaSearch() : OpenSet(Evaluator.nodes()) {}

  /// `OpenSet` points into `Evaluator`'s node table, so a copy or a move would
  /// point into the original's.
  VanillaSearch(const VanillaSearch &) = delete;
  VanillaSearch &operator=(const VanillaSearch &) = delete;

  fbs::Pathfinder kind() const override { return fbs::Pathfinder::VANILLA; }

  void find(const world::Area &Area, const Profile &Mob, const Request &Call,
            Result &Out) override;

private:
  /// `getBestH`: the distance to the target, tracking the nearest node seen.
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
