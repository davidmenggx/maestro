#include "maestro/search/walk_node_evaluator.hpp"

#include <algorithm>
#include <bit>
#include <cassert>
#include <cmath>

#include "java_math.hpp"

namespace maestro::search {

using world::PathType;

namespace {

constexpr std::uint32_t bit(PathType Type) {
  return std::uint32_t{1} << static_cast<unsigned>(Type);
}

/// Vanilla's `AABB`. Like vanilla, the constructor orders each pair of bounds.
struct Box {
  double MinX, MinY, MinZ, MaxX, MaxY, MaxZ;

  Box(double X1, double Y1, double Z1, double X2, double Y2, double Z2)
      : MinX(std::min(X1, X2)), MinY(std::min(Y1, Y2)), MinZ(std::min(Z1, Z2)),
        MaxX(std::max(X1, X2)), MaxY(std::max(Y1, Y2)), MaxZ(std::max(Z1, Z2)) {
  }

  double xsize() const { return MaxX - MinX; }
  double ysize() const { return MaxY - MinY; }
  double zsize() const { return MaxZ - MinZ; }
  double size() const { return (xsize() + ysize() + zsize()) / 3.0; }

  Box moved(double Dx, double Dy, double Dz) const {
    return {MinX + Dx, MinY + Dy, MinZ + Dz, MaxX + Dx, MaxY + Dy, MaxZ + Dz};
  }
};

/// Whether `Bounds` overlaps a block. A cell is a full-width box from its base
/// up to its collision height, so it only extends above its own cell (a fence
/// is 24 sixteenths) and only the layer below `Bounds` can reach into it.
/// Touching faces do not collide.
bool hasCollisions(const world::Area &Area, const Box &Bounds) {
  constexpr double Epsilon = 1.0E-7;
  const int X0 = java::floor(Bounds.MinX - Epsilon);
  const int X1 = java::floor(Bounds.MaxX + Epsilon);
  const int Y0 = java::floor(Bounds.MinY - Epsilon) - 1;
  const int Y1 = java::floor(Bounds.MaxY + Epsilon);
  const int Z0 = java::floor(Bounds.MinZ - Epsilon);
  const int Z1 = java::floor(Bounds.MaxZ + Epsilon);
  for (int X = X0; X <= X1; ++X) {
    for (int Y = Y0; Y <= Y1; ++Y) {
      for (int Z = Z0; Z <= Z1; ++Z) {
        const world::Cell Block = Area.at(X, Y, Z);
        if (Block.Floor == 0)
          continue;
        const double Top = Y + Block.Floor / 16.0;
        if (Bounds.MinX < X + 1.0 && Bounds.MaxX > X && Bounds.MinY < Top &&
            Bounds.MaxY > Y && Bounds.MinZ < Z + 1.0 && Bounds.MaxZ > Z)
          return true;
      }
    }
  }
  return false;
}

bool doesBlockHavePartialCollision(PathType Type) {
  return Type == PathType::FENCE || Type == PathType::DOOR_WOOD_CLOSED ||
         Type == PathType::DOOR_IRON_CLOSED;
}

} // namespace

void WalkNodeEvaluator::prepare(const world::Area &NewArea,
                                const Profile &NewMob, const Request &NewCall) {
  Area = &NewArea;
  Mob = &NewMob;
  Call = &NewCall;
  Nodes.clear();
  NodeIndex.clear();
  MobTypes.clear();
  StaticTypes.clear();
  EntityWidth = java::floor(Mob->Width + 1.0F);
  EntityHeight = java::floor(Mob->Height + 1.0F);
  EntityDepth = java::floor(Mob->Width + 1.0F);
}

void WalkNodeEvaluator::done() {
  Area = nullptr;
  Mob = nullptr;
  Call = nullptr;
}

NodeId WalkNodeEvaluator::getNode(int X, int Y, int Z) {
  auto [Slot, Inserted] = NodeIndex.tryEmplace(X, Y, Z, NoNode);
  if (Inserted) {
    *Slot = static_cast<NodeId>(Nodes.size());
    Nodes.push_back(Node{.X = X, .Y = Y, .Z = Z});
  }
  return *Slot;
}

NodeId WalkNodeEvaluator::getStart() {
  const BlockPos Start = Call->Start;
  const NodeId Id = getNode(Start.X, Start.Y, Start.Z);
  const PathType Type = getCachedPathType(Start.X, Start.Y, Start.Z);
  Nodes[Id].Type = Type;
  Nodes[Id].CostMalus = Mob->malus(Type);
  return Id;
}

NodeId WalkNodeEvaluator::getTarget(BlockPos Pos) {
  return getNode(Pos.X, Pos.Y, Pos.Z);
}

int WalkNodeEvaluator::getNeighbors(std::span<NodeId> Out, NodeId Current) {
  assert(Out.size() >= 2 * Horizontal.size() && "room for every neighbour");
  int Count = 0;
  int JumpSize = 0;
  const int X = Nodes[Current].X;
  const int Y = Nodes[Current].Y;
  const int Z = Nodes[Current].Z;
  const PathType Above = getCachedPathType(X, Y + 1, Z);
  const PathType Here = getCachedPathType(X, Y, Z);
  if (Mob->malus(Above) >= 0.0F && Here != PathType::STICKY_HONEY)
    JumpSize = java::floor(std::max(1.0F, Mob->MaxUpStep));

  const double PosHeight = getFloorLevel(X, Y, Z);

  for (std::size_t I = 0; I < Horizontal.size(); ++I) {
    const Direction Travel = Horizontal[I];
    const NodeId Neighbor =
        findAcceptedNode(X + Travel.StepX, Y, Z + Travel.StepZ, JumpSize,
                         PosHeight, Travel, Here);
    ReusableNeighbors[I] = Neighbor;
    if (isNeighborValid(Neighbor, Current))
      Out[Count++] = Neighbor;
  }

  // Each direction is paired with the next one clockwise.
  for (std::size_t I = 0; I < Horizontal.size(); ++I) {
    const std::size_t Clockwise = (I + 1) % Horizontal.size();
    const Direction Travel = Horizontal[I];
    const Direction Second = Horizontal[Clockwise];
    if (!isDiagonalValid(Current, ReusableNeighbors[I],
                         ReusableNeighbors[Clockwise]))
      continue;
    const NodeId Diagonal = findAcceptedNode(X + Travel.StepX + Second.StepX, Y,
                                             Z + Travel.StepZ + Second.StepZ,
                                             JumpSize, PosHeight, Travel, Here);
    if (isDiagonalValid(Diagonal))
      Out[Count++] = Diagonal;
  }
  return Count;
}

bool WalkNodeEvaluator::isNeighborValid(NodeId Neighbor, NodeId Current) const {
  return Neighbor != NoNode && !Nodes[Neighbor].Closed &&
         (Nodes[Neighbor].CostMalus >= 0.0F || Nodes[Current].CostMalus < 0.0F);
}

bool WalkNodeEvaluator::isDiagonalValid(NodeId PosId, NodeId EwId,
                                        NodeId NsId) const {
  if (NsId == NoNode || EwId == NoNode)
    return false;
  const Node &Pos = Nodes[PosId];
  const Node &Ew = Nodes[EwId];
  const Node &Ns = Nodes[NsId];
  if (Ns.Y > Pos.Y || Ew.Y > Pos.Y)
    return false;
  if (Ew.Type == PathType::WALKABLE_DOOR || Ns.Type == PathType::WALKABLE_DOOR)
    return false;
  if (Mob->Width > 1.0F && (Ew.CostMalus > 0.0F || Ns.CostMalus > 0.0F))
    return false;
  const bool CanPassBetweenPosts = Ns.Type == PathType::FENCE &&
                                   Ew.Type == PathType::FENCE &&
                                   Mob->Width < 0.5;
  return (Ns.Y < Pos.Y || Ns.CostMalus >= 0.0F || CanPassBetweenPosts) &&
         (Ew.Y < Pos.Y || Ew.CostMalus >= 0.0F || CanPassBetweenPosts);
}

bool WalkNodeEvaluator::isDiagonalValid(NodeId Diagonal) const {
  if (Diagonal == NoNode || Nodes[Diagonal].Closed)
    return false;
  if (Nodes[Diagonal].Type == PathType::WALKABLE_DOOR)
    return false;
  return Nodes[Diagonal].CostMalus >= 0.0F;
}

bool WalkNodeEvaluator::canReachWithoutCollision(NodeId PosTo) const {
  // Vanilla sweeps from the mob's position, which the node is not sent. Use
  // the bottom centre of the start node instead.
  const double MobX = Call->Start.X + 0.5;
  const double MobY = Call->Start.Y;
  const double MobZ = Call->Start.Z + 0.5;
  const float HalfWidth = Mob->Width / 2.0F;
  Box Bounds(MobX - HalfWidth, MobY, MobZ - HalfWidth, MobX + HalfWidth,
             MobY + Mob->Height, MobZ + HalfWidth);

  const Node &To = Nodes[PosTo];
  double Dx = To.X - MobX + Bounds.xsize() / 2.0;
  double Dy = To.Y - MobY + Bounds.ysize() / 2.0;
  double Dz = To.Z - MobZ + Bounds.zsize() / 2.0;
  const int Steps =
      java::ceil(std::sqrt(Dx * Dx + Dy * Dy + Dz * Dz) / Bounds.size());
  if (Steps <= 0)
    return true;
  const float Scale = 1.0F / static_cast<float>(Steps);
  Dx *= Scale;
  Dy *= Scale;
  Dz *= Scale;
  for (int I = 1; I <= Steps; ++I) {
    Bounds = Bounds.moved(Dx, Dy, Dz);
    if (hasCollisions(*Area, Bounds))
      return false;
  }
  return true;
}

double WalkNodeEvaluator::getFloorLevel(int X, int Y, int Z) const {
  if (Mob->CanFloat && Area->at(X, Y, Z).Type == PathType::WATER)
    return Y + 0.5;
  return (Y - 1) + Area->at(X, Y - 1, Z).Floor / 16.0;
}

NodeId WalkNodeEvaluator::findAcceptedNode(int X, int Y, int Z, int JumpSize,
                                           double NodeHeight, Direction Travel,
                                           PathType Current) {
  NodeId Best = NoNode;
  const double MaxYTarget = getFloorLevel(X, Y, Z);
  if (MaxYTarget - NodeHeight > getMobJumpHeight())
    return NoNode;

  const PathType Type = getCachedPathType(X, Y, Z);
  const float PathCost = Mob->malus(Type);
  if (PathCost >= 0.0F)
    Best = getNodeAndUpdateCostToMax(X, Y, Z, Type, PathCost);

  if (doesBlockHavePartialCollision(Current) && Best != NoNode &&
      Nodes[Best].CostMalus >= 0.0F && !canReachWithoutCollision(Best))
    Best = NoNode;

  if (Type == PathType::WALKABLE)
    return Best;

  if ((Best == NoNode || Nodes[Best].CostMalus < 0.0F) && JumpSize > 0 &&
      (Type != PathType::FENCE || Mob->CanWalkOverFences) &&
      Type != PathType::UNPASSABLE_RAIL && Type != PathType::TRAPDOOR &&
      Type != PathType::POWDER_SNOW)
    return tryJumpOn(X, Y, Z, JumpSize, NodeHeight, Travel, Current);
  if (Type == PathType::WATER && !Mob->CanFloat)
    return tryFindFirstNonWaterBelow(X, Y, Z, Best);
  if (Type == PathType::OPEN)
    return tryFindFirstGroundNodeBelow(X, Y, Z);
  if (doesBlockHavePartialCollision(Type) && Best == NoNode)
    return getClosedNode(X, Y, Z, Type);
  return Best;
}

double WalkNodeEvaluator::getMobJumpHeight() const {
  return std::max(1.125, static_cast<double>(Mob->MaxUpStep));
}

NodeId WalkNodeEvaluator::getNodeAndUpdateCostToMax(int X, int Y, int Z,
                                                    PathType Type, float Cost) {
  const NodeId Id = getNode(X, Y, Z);
  Nodes[Id].Type = Type;
  Nodes[Id].CostMalus = java::max(Nodes[Id].CostMalus, Cost);
  return Id;
}

NodeId WalkNodeEvaluator::getBlockedNode(int X, int Y, int Z) {
  const NodeId Id = getNode(X, Y, Z);
  Nodes[Id].Type = PathType::BLOCKED;
  Nodes[Id].CostMalus = -1.0F;
  return Id;
}

NodeId WalkNodeEvaluator::getClosedNode(int X, int Y, int Z, PathType Type) {
  const NodeId Id = getNode(X, Y, Z);
  Nodes[Id].Closed = true;
  Nodes[Id].Type = Type;
  Nodes[Id].CostMalus = defaultMalus()[static_cast<std::size_t>(Type)];
  return Id;
}

NodeId WalkNodeEvaluator::tryJumpOn(int X, int Y, int Z, int JumpSize,
                                    double NodeHeight, Direction Travel,
                                    PathType Current) {
  const NodeId Above =
      findAcceptedNode(X, Y + 1, Z, JumpSize - 1, NodeHeight, Travel, Current);
  if (Above == NoNode)
    return NoNode;
  if (Mob->Width >= 1.0F)
    return Above;

  const Node &Landing = Nodes[Above];
  if (Landing.Type != PathType::OPEN && Landing.Type != PathType::WALKABLE)
    return Above;

  // The box the mob sweeps when jumping from the cell it leaves.
  const double CenterX = X - Travel.StepX + 0.5;
  const double CenterZ = Z - Travel.StepZ + 0.5;
  const double HalfWidth = Mob->Width / 2.0;
  const Box Grow(
      CenterX - HalfWidth,
      getFloorLevel(java::floor(CenterX), Y + 1, java::floor(CenterZ)) + 0.001,
      CenterZ - HalfWidth, CenterX + HalfWidth,
      Mob->Height + getFloorLevel(Landing.X, Landing.Y, Landing.Z) - 0.002,
      CenterZ + HalfWidth);
  return hasCollisions(*Area, Grow) ? NoNode : Above;
}

NodeId WalkNodeEvaluator::tryFindFirstNonWaterBelow(int X, int Y, int Z,
                                                    NodeId Best) {
  --Y;
  while (Y > Area->geometry().MinY) {
    const PathType Type = getCachedPathType(X, Y, Z);
    if (Type != PathType::WATER)
      return Best;
    Best = getNodeAndUpdateCostToMax(X, Y, Z, Type, Mob->malus(Type));
    --Y;
  }
  return Best;
}

NodeId WalkNodeEvaluator::tryFindFirstGroundNodeBelow(int X, int Y, int Z) {
  for (int CurrentY = Y - 1; CurrentY >= Area->geometry().MinY; --CurrentY) {
    if (Y - CurrentY > Call->MaxFallDistance)
      return getBlockedNode(X, CurrentY, Z);

    const PathType Type = getCachedPathType(X, CurrentY, Z);
    const float PathCost = Mob->malus(Type);
    if (Type != PathType::OPEN) {
      if (PathCost >= 0.0F)
        return getNodeAndUpdateCostToMax(X, CurrentY, Z, Type, PathCost);
      return getBlockedNode(X, CurrentY, Z);
    }
  }
  return getBlockedNode(X, Y, Z);
}

PathType WalkNodeEvaluator::getCachedPathType(int X, int Y, int Z) {
  if (const PathType *Cached = MobTypes.find(X, Y, Z))
    return *Cached;
  const PathType Type = getPathTypeOfMob(X, Y, Z);
  MobTypes.tryEmplace(X, Y, Z, Type);
  return Type;
}

PathType WalkNodeEvaluator::getPathTypeOfMob(int X, int Y, int Z) {
  const std::uint32_t Types = getPathTypeWithinMobBB(X, Y, Z);
  if (std::popcount(Types) == 1)
    return static_cast<PathType>(std::countr_zero(Types));
  if (Types & bit(PathType::FENCE))
    return PathType::FENCE;
  if (Types & bit(PathType::UNPASSABLE_RAIL))
    return PathType::UNPASSABLE_RAIL;

  // Vanilla iterates an `EnumSet` in ordinal order, so on a tie for the
  // highest malus the later ordinal wins.
  PathType Highest = PathType::BLOCKED;
  float HighestMalus = Mob->malus(Highest);
  for (std::uint32_t Rest = Types; Rest != 0; Rest &= Rest - 1) {
    const auto Type = static_cast<PathType>(std::countr_zero(Rest));
    const float Malus = Mob->malus(Type);
    if (Malus < 0.0F)
      return Type;
    if (Malus >= HighestMalus) {
      HighestMalus = Malus;
      Highest = Type;
    }
  }

  const PathType Here = getPathType(X, Y, Z);
  if (EntityWidth > 1) {
    const bool IsCurrentNodeCheaper = Mob->malus(Here) < HighestMalus;
    const bool CapMalus =
        IsCurrentNodeCheaper &&
        Mob->malus(PathType::BIG_MOBS_CLOSE_TO_DANGER) < HighestMalus;
    return CapMalus ? PathType::BIG_MOBS_CLOSE_TO_DANGER : Highest;
  }
  return Here == PathType::OPEN && Highest != PathType::OPEN &&
                 HighestMalus == 0.0F
             ? PathType::OPEN
             : Highest;
}

std::uint32_t WalkNodeEvaluator::getPathTypeWithinMobBB(int X, int Y, int Z) {
  // Vanilla uses the mob's block position for the rail check. Use the start
  // node instead.
  const BlockPos MobPos = Call->Start;
  std::uint32_t Types = 0;
  for (int Dx = 0; Dx < EntityWidth; ++Dx) {
    for (int Dy = 0; Dy < EntityHeight; ++Dy) {
      for (int Dz = 0; Dz < EntityDepth; ++Dz) {
        PathType Type = getPathType(X + Dx, Y + Dy, Z + Dz);
        if (Type == PathType::DOOR_WOOD_CLOSED && Mob->CanOpenDoors &&
            Mob->CanPassDoors)
          Type = PathType::WALKABLE_DOOR;
        if (Type == PathType::DOOR_OPEN && !Mob->CanPassDoors)
          Type = PathType::BLOCKED;
        if (Type == PathType::RAIL &&
            getPathType(MobPos.X, MobPos.Y, MobPos.Z) != PathType::RAIL &&
            getPathType(MobPos.X, MobPos.Y - 1, MobPos.Z) != PathType::RAIL)
          Type = PathType::UNPASSABLE_RAIL;
        Types |= bit(Type);
      }
    }
  }
  return Types;
}

PathType WalkNodeEvaluator::getPathType(int X, int Y, int Z) {
  if (const PathType *Cached = StaticTypes.find(X, Y, Z))
    return *Cached;
  const PathType Type = pathTypeStatic(*Area, X, Y, Z);
  StaticTypes.tryEmplace(X, Y, Z, Type);
  return Type;
}

} // namespace maestro::search
