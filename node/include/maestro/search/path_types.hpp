#pragma once

#include <array>

#include "maestro/world/area.hpp"

namespace maestro::search {

/// Vanilla's default `PathType.getMalus` for every ordinal.
const std::array<float, world::PathTypeCount> &defaultMalus();

/// `WalkNodeEvaluator.getPathTypeStatic`: an `OPEN` cell takes its type from
/// the cell below, then from `checkNeighbourBlocks` if that is unlisted.
world::PathType pathTypeStatic(const world::Area &Area, int X, int Y, int Z);

/// `WalkNodeEvaluator.checkNeighbourBlocks`. Scans `dx`, `dy`, `dz` over -1..1
/// (skipping the centre column) and returns the first hazard found, so the
/// scan order decides cells next to several hazards. Returns `Fallback` if
/// there is none.
world::PathType checkNeighbourBlocks(const world::Area &Area, int X, int Y,
                                     int Z, world::PathType Fallback);

} // namespace maestro::search
