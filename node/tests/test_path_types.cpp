#include "maestro/search/path_types.hpp"

#include <gtest/gtest.h>

#include "test_world.hpp"

using maestro::search::checkNeighbourBlocks;
using maestro::search::defaultMalus;
using maestro::search::pathTypeStatic;
using maestro::test::DenseWorld;
using maestro::world::Air;
using maestro::world::Cell;
using maestro::world::PathType;
using namespace maestro::test;

namespace {

/// Ground at y = 0 everywhere, so y = 1 is where a mob stands.
DenseWorld ground() {
  DenseWorld World(16, 0, 8);
  World.fill(0, 0, 0, 15, 0, 15, Stone);
  return World;
}

PathType staticAt(const DenseWorld &World, int X, int Y, int Z) {
  return pathTypeStatic(*World.load(), X, Y, Z);
}

PathType standingOn(Cell Floor) {
  DenseWorld World = ground();
  World.set(8, 0, 8, Floor);
  return staticAt(World, 8, 1, 8);
}

} // namespace

TEST(PathTypes, DefaultMalusMatchesVanilla) {
  const auto &Malus = defaultMalus();
  ASSERT_EQ(Malus.size(), 27u);
  EXPECT_EQ(Malus[static_cast<int>(PathType::BLOCKED)], -1.0F);
  EXPECT_EQ(Malus[static_cast<int>(PathType::OPEN)], 0.0F);
  EXPECT_EQ(Malus[static_cast<int>(PathType::WATER)], 8.0F);
  EXPECT_EQ(Malus[static_cast<int>(PathType::FIRE)], 16.0F);
  EXPECT_EQ(Malus[static_cast<int>(PathType::DAMAGING)], -1.0F);
  EXPECT_EQ(Malus[static_cast<int>(PathType::BREACH)], 4.0F);
  EXPECT_EQ(Malus[static_cast<int>(PathType::STICKY_HONEY)], 8.0F);
  EXPECT_EQ(Malus[static_cast<int>(PathType::BIG_MOBS_CLOSE_TO_DANGER)], 4.0F);
}

TEST(PathTypes, OpenIsPromotedByTheCellBelow) {
  EXPECT_EQ(standingOn(Stone), PathType::WALKABLE);
  EXPECT_EQ(standingOn(Air), PathType::OPEN);
  EXPECT_EQ(standingOn(Water), PathType::OPEN);
  EXPECT_EQ(standingOn(Lava), PathType::OPEN);
  EXPECT_EQ(standingOn(Fire), PathType::FIRE);
  EXPECT_EQ(standingOn(Cactus), PathType::DAMAGING);
  EXPECT_EQ(standingOn(Cell{PathType::STICKY_HONEY, 15}),
            PathType::STICKY_HONEY);
  EXPECT_EQ(standingOn(Cell{PathType::POWDER_SNOW, 0}),
            PathType::ON_TOP_OF_POWDER_SNOW);
  EXPECT_EQ(standingOn(Cell{PathType::DAMAGE_CAUTIOUS, 16}),
            PathType::DAMAGE_CAUTIOUS);
  EXPECT_EQ(standingOn(Cell{PathType::TRAPDOOR, 3}),
            PathType::ON_TOP_OF_TRAPDOOR);
  // Any other cell under an open cell makes it walkable, unless a neighbour
  // is a hazard.
  EXPECT_EQ(standingOn(Fence), PathType::WALKABLE);
  EXPECT_EQ(standingOn(Cell{PathType::LEAVES, 16}), PathType::WALKABLE);
}

TEST(PathTypes, AnythingButOpenIsItsOwnType) {
  DenseWorld World = ground();
  World.set(4, 1, 4, Water);
  World.set(5, 1, 5, Fence);
  EXPECT_EQ(staticAt(World, 4, 1, 4), PathType::WATER);
  EXPECT_EQ(staticAt(World, 5, 1, 5), PathType::FENCE);
  EXPECT_EQ(staticAt(World, 6, 0, 6), PathType::BLOCKED);
}

TEST(PathTypes, NothingIsPromotedAtTheBottomOfTheWorld) {
  const DenseWorld World(16, -8, 8);
  EXPECT_EQ(staticAt(World, 3, -8, 3), PathType::OPEN);
}

TEST(PathTypes, AHazardBesideAWalkableCellMarksIt) {
  const auto Beside = [](Cell Hazard) {
    DenseWorld World = ground();
    World.set(9, 1, 8, Hazard);
    return staticAt(World, 8, 1, 8);
  };
  EXPECT_EQ(Beside(Water), PathType::WATER_BORDER);
  EXPECT_EQ(Beside(Lava), PathType::FIRE_IN_NEIGHBOR);
  EXPECT_EQ(Beside(Fire), PathType::FIRE_IN_NEIGHBOR);
  EXPECT_EQ(Beside(Cactus), PathType::DAMAGING_IN_NEIGHBOR);
  EXPECT_EQ(Beside(Cell{PathType::DAMAGE_CAUTIOUS, 16}),
            PathType::DAMAGE_CAUTIOUS);
  EXPECT_EQ(Beside(Fence), PathType::WALKABLE);
}

TEST(PathTypes, TheScanCoversTheLayersAboveAndBelow) {
  DenseWorld Below = ground();
  Below.set(9, 0, 9, Water);
  EXPECT_EQ(staticAt(Below, 8, 1, 8), PathType::WATER_BORDER);

  DenseWorld Above = ground();
  Above.set(7, 2, 9, Cactus);
  EXPECT_EQ(staticAt(Above, 8, 1, 8), PathType::DAMAGING_IN_NEIGHBOR);
}

TEST(PathTypes, TheScanSkipsTheCentreColumn) {
  DenseWorld World = ground();
  World.set(8, 2, 8, Cactus);
  EXPECT_EQ(staticAt(World, 8, 1, 8), PathType::WALKABLE);
}

TEST(PathTypes, TheFirstHazardInScanOrderDecides) {
  // `dx` is the outer loop, so the west neighbour is read before the east one.
  DenseWorld WestWater = ground();
  WestWater.set(7, 1, 8, Water);
  WestWater.set(9, 1, 8, Cactus);
  EXPECT_EQ(staticAt(WestWater, 8, 1, 8), PathType::WATER_BORDER);

  DenseWorld WestCactus = ground();
  WestCactus.set(7, 1, 8, Cactus);
  WestCactus.set(9, 1, 8, Water);
  EXPECT_EQ(staticAt(WestCactus, 8, 1, 8), PathType::DAMAGING_IN_NEIGHBOR);

  // A western hazard in the layer above still comes before an eastern one in
  // the cell's own layer.
  DenseWorld WestAbove = ground();
  WestAbove.set(7, 2, 8, Water);
  WestAbove.set(9, 1, 8, Cactus);
  EXPECT_EQ(staticAt(WestAbove, 8, 1, 8), PathType::WATER_BORDER);

  // Within one `dx`, the layer below comes before the cell's own layer.
  DenseWorld LowerFirst = ground();
  LowerFirst.set(8, 0, 9, Cactus);
  LowerFirst.set(8, 1, 7, Water);
  EXPECT_EQ(staticAt(LowerFirst, 8, 1, 8), PathType::DAMAGING_IN_NEIGHBOR);

  // Within one `dx` and `dy`, north comes before south.
  DenseWorld NorthFirst = ground();
  NorthFirst.set(9, 1, 7, Lava);
  NorthFirst.set(9, 1, 9, Water);
  EXPECT_EQ(staticAt(NorthFirst, 8, 1, 8), PathType::FIRE_IN_NEIGHBOR);
}

TEST(PathTypes, CheckNeighbourBlocksFallsBackWithoutAHazard) {
  const DenseWorld World = ground();
  EXPECT_EQ(checkNeighbourBlocks(*World.load(), 8, 1, 8, PathType::OPEN),
            PathType::OPEN);
}
