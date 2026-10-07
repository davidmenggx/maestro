#pragma once

#include <array>

#include "maestro/world/area.hpp"

namespace maestro::search {

/// Vanilla's `PathType.getMalus` for every ordinal: what a type costs a mob
/// that has not overridden it.
const std::array<float, world::PathTypeCount> &defaultMalus();

/// `WalkNodeEvaluator.getPathTypeStatic`: an `OPEN` cell is promoted from the
/// cell below it, and over anything solid falls through to
/// `checkNeighbourBlocks`.
world::PathType pathTypeStatic(const world::Area &Area, int X, int Y, int Z);

/// `WalkNodeEvaluator.checkNeighbourBlocks`: scans `dx`, then `dy`, then `dz`
/// over -1..1, skipping the centre column, and returns on the first hazard,
/// so the scan order decides a cell that borders more than one. `Fallback` is
/// returned when there is none.
world::PathType checkNeighbourBlocks(const world::Area &Area, int X, int Y,
                                     int Z, world::PathType Fallback);

} // namespace maestro::search
