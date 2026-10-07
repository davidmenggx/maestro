#pragma once

#include <cstdint>

#include "common_generated.h"

namespace maestro::world {

using PathType = fbs::PathType;

/// Number of `PathType` ordinals, and so the length of a malus table.
inline constexpr int PathTypeCount = static_cast<int>(PathType::MAX) + 1;

/// Collision height of a full cube, in sixteenths of a block.
inline constexpr std::uint8_t FullFloor = 16;

/// One block as the node holds it: the per-block `PathType` and `Floor`, the
/// top of the block's collision shape in sixteenths (0 when it has none).
struct Cell {
  PathType Type = PathType::OPEN;
  std::uint8_t Floor = 0;

  friend bool operator==(Cell, Cell) = default;
};

/// What vanilla reads for a position outside the chunks it holds.
inline constexpr Cell Air{};

/// Whether `getPathTypeFromState` can return `Type`, which is the only kind of
/// type a cell on the wire may carry.
constexpr bool isPerBlock(PathType Type) {
  switch (Type) {
  case PathType::BLOCKED:
  case PathType::OPEN:
  case PathType::TRAPDOOR:
  case PathType::POWDER_SNOW:
  case PathType::FENCE:
  case PathType::LAVA:
  case PathType::WATER:
  case PathType::RAIL:
  case PathType::FIRE:
  case PathType::DAMAGING:
  case PathType::DOOR_OPEN:
  case PathType::DOOR_WOOD_CLOSED:
  case PathType::DOOR_IRON_CLOSED:
  case PathType::LEAVES:
  case PathType::STICKY_HONEY:
  case PathType::COCOA:
  case PathType::DAMAGE_CAUTIOUS:
    return true;
  default:
    return false;
  }
}

} // namespace maestro::world
