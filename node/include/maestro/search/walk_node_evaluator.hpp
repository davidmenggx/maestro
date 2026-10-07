#pragma once

#include <array>
#include <cstdint>
#include <span>
#include <vector>

#include "maestro/search/coord_map.hpp"
#include "maestro/search/node.hpp"
#include "maestro/search/search.hpp"

namespace maestro::search {

/// Port of vanilla's `WalkNodeEvaluator`: which positions a land mob can move
/// to from a node, and what each costs. Method names follow vanilla's so the
/// two read side by side.
///
/// Two inputs vanilla reads are not on the wire. The mob's position is taken
/// to be the bottom centre of the start node, and "in water" means the cell's
/// type is `WATER` rather than that its fluid is water.
class WalkNodeEvaluator {
public:
  /// Readies the evaluator for one search. All three must outlive `done`.
  void prepare(const world::Area &Area, const Profile &Mob,
               const Request &Call);
  void done();

  /// `getStartNode` at the start the server resolved.
  NodeId getStart();

  /// `getTargetNodeAt`. Like vanilla, this registers a node at the target
  /// before the search starts.
  NodeId getTarget(BlockPos Pos);

  /// Writes the neighbours of `Current` worth visiting to `Out`, which holds
  /// at least eight, and returns how many there are.
  int getNeighbors(std::span<NodeId> Out, NodeId Current);

  Node &node(NodeId Id) { return Nodes[Id]; }
  const Node &node(NodeId Id) const { return Nodes[Id]; }

  /// The node table, which grows as the search reaches new positions.
  std::vector<Node> &nodes() { return Nodes; }

  /// `getCachedPathType`: `getPathTypeOfMob`, cached for the search.
  world::PathType getCachedPathType(int X, int Y, int Z);

  /// `getPathTypeOfMob`: the type of the mob's whole footprint standing at
  /// the position, with its door and rail abilities applied.
  world::PathType getPathTypeOfMob(int X, int Y, int Z);

  /// `getPathType`: the static type at one position, cached for the search.
  world::PathType getPathType(int X, int Y, int Z);

  /// `getFloorLevel`: the height a mob at the position stands at.
  double getFloorLevel(int X, int Y, int Z) const;

private:
  struct Direction {
    int StepX;
    int StepZ;
  };

  NodeId getNode(int X, int Y, int Z);
  bool isNeighborValid(NodeId Neighbor, NodeId Current) const;
  bool isDiagonalValid(NodeId Pos, NodeId Ew, NodeId Ns) const;
  bool isDiagonalValid(NodeId Diagonal) const;
  bool canReachWithoutCollision(NodeId PosTo) const;
  NodeId findAcceptedNode(int X, int Y, int Z, int JumpSize, double NodeHeight,
                          Direction Travel, world::PathType Current);
  double getMobJumpHeight() const;
  NodeId getNodeAndUpdateCostToMax(int X, int Y, int Z, world::PathType Type,
                                   float Cost);
  NodeId getBlockedNode(int X, int Y, int Z);
  NodeId getClosedNode(int X, int Y, int Z, world::PathType Type);
  NodeId tryJumpOn(int X, int Y, int Z, int JumpSize, double NodeHeight,
                   Direction Travel, world::PathType Current);
  NodeId tryFindFirstNonWaterBelow(int X, int Y, int Z, NodeId Best);
  NodeId tryFindFirstGroundNodeBelow(int X, int Y, int Z);

  /// `getPathTypeWithinMobBB`, as a bit per `PathType` ordinal.
  std::uint32_t getPathTypeWithinMobBB(int X, int Y, int Z);

  static constexpr std::array<Direction, 4> Horizontal = {
      {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}};

  const world::Area *Area = nullptr;
  const Profile *Mob = nullptr;
  const Request *Call = nullptr;
  int EntityWidth = 0;
  int EntityHeight = 0;
  int EntityDepth = 0;

  std::vector<Node> Nodes;
  CoordMap<NodeId> NodeIndex;
  CoordMap<world::PathType> MobTypes;
  CoordMap<world::PathType> StaticTypes;
  std::array<NodeId, Horizontal.size()> ReusableNeighbors{};
};

} // namespace maestro::search
