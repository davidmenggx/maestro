#pragma once

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <limits>

#include "maestro/world/cell.hpp"

namespace maestro::search {

/// Index of a node in one search's node table.
using NodeId = std::uint32_t;
inline constexpr NodeId NoNode = std::numeric_limits<NodeId>::max();

/// Vanilla's `Node`: a block position the search has looked at, and its A*
/// state. Fields keep vanilla's types, so costs round the way they do in Java.
struct Node {
  int X = 0;
  int Y = 0;
  int Z = 0;
  int HeapIdx = -1;
  float G = 0.0F;
  float H = 0.0F;
  float F = 0.0F;
  NodeId CameFrom = NoNode;
  bool Closed = false;
  float WalkedDistance = 0.0F;
  float CostMalus = 0.0F;
  world::PathType Type = world::PathType::BLOCKED;

  bool inOpenSet() const { return HeapIdx >= 0; }
};

/// `Node.distanceTo`: single precision, with the square root taken in double
/// as `Mth.sqrt` does.
inline float distanceTo(const Node &From, const Node &To) {
  const auto Dx = static_cast<float>(To.X - From.X);
  const auto Dy = static_cast<float>(To.Y - From.Y);
  const auto Dz = static_cast<float>(To.Z - From.Z);
  return static_cast<float>(
      std::sqrt(static_cast<double>(Dx * Dx + Dy * Dy + Dz * Dz)));
}

/// `Node.distanceManhattan`.
inline float distanceManhattan(const Node &From, const Node &To) {
  const auto Dx = static_cast<float>(std::abs(To.X - From.X));
  const auto Dy = static_cast<float>(std::abs(To.Y - From.Y));
  const auto Dz = static_cast<float>(std::abs(To.Z - From.Z));
  return Dx + Dy + Dz;
}

} // namespace maestro::search
