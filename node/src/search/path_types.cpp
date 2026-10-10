#include "maestro/search/path_types.hpp"

namespace maestro::search {

using world::PathType;

const std::array<float, world::PathTypeCount> &defaultMalus() {
  static constexpr std::array<float, world::PathTypeCount> Table = {
      -1.0F, // BLOCKED
      0.0F,  // OPEN
      0.0F,  // WALKABLE
      0.0F,  // WALKABLE_DOOR
      0.0F,  // TRAPDOOR
      -1.0F, // POWDER_SNOW
      0.0F,  // ON_TOP_OF_POWDER_SNOW
      -1.0F, // FENCE
      -1.0F, // LAVA
      8.0F,  // WATER
      8.0F,  // WATER_BORDER
      0.0F,  // RAIL
      -1.0F, // UNPASSABLE_RAIL
      8.0F,  // FIRE_IN_NEIGHBOR
      16.0F, // FIRE
      8.0F,  // DAMAGING_IN_NEIGHBOR
      -1.0F, // DAMAGING
      0.0F,  // DOOR_OPEN
      -1.0F, // DOOR_WOOD_CLOSED
      -1.0F, // DOOR_IRON_CLOSED
      4.0F,  // BREACH
      -1.0F, // LEAVES
      8.0F,  // STICKY_HONEY
      0.0F,  // COCOA
      0.0F,  // DAMAGE_CAUTIOUS
      0.0F,  // ON_TOP_OF_TRAPDOOR
      4.0F,  // BIG_MOBS_CLOSE_TO_DANGER
  };
  return Table;
}

PathType pathTypeStatic(const world::Area &Area, int X, int Y, int Z) {
  const PathType Block = Area.at(X, Y, Z).Type;
  if (Block != PathType::OPEN || Y < Area.geometry().MinY + 1)
    return Block;

  switch (Area.at(X, Y - 1, Z).Type) {
  case PathType::OPEN:
  case PathType::WATER:
  case PathType::LAVA:
  case PathType::WALKABLE:
    return PathType::OPEN;
  case PathType::FIRE:
    return PathType::FIRE;
  case PathType::DAMAGING:
    return PathType::DAMAGING;
  case PathType::STICKY_HONEY:
    return PathType::STICKY_HONEY;
  case PathType::POWDER_SNOW:
    return PathType::ON_TOP_OF_POWDER_SNOW;
  case PathType::DAMAGE_CAUTIOUS:
    return PathType::DAMAGE_CAUTIOUS;
  case PathType::TRAPDOOR:
    return PathType::ON_TOP_OF_TRAPDOOR;
  default:
    return checkNeighbourBlocks(Area, X, Y, Z, PathType::WALKABLE);
  }
}

PathType checkNeighbourBlocks(const world::Area &Area, int X, int Y, int Z,
                              PathType Fallback) {
  for (int Dx = -1; Dx <= 1; ++Dx) {
    for (int Dy = -1; Dy <= 1; ++Dy) {
      for (int Dz = -1; Dz <= 1; ++Dz) {
        if (Dx == 0 && Dz == 0)
          continue;
        switch (Area.at(X + Dx, Y + Dy, Z + Dz).Type) {
        case PathType::DAMAGING:
          return PathType::DAMAGING_IN_NEIGHBOR;
        case PathType::FIRE:
        case PathType::LAVA:
          return PathType::FIRE_IN_NEIGHBOR;
        case PathType::WATER:
          return PathType::WATER_BORDER;
        case PathType::DAMAGE_CAUTIOUS:
          return PathType::DAMAGE_CAUTIOUS;
        default:
          break;
        }
      }
    }
  }
  return Fallback;
}

} // namespace maestro::search
