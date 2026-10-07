#include "maestro/world/area.hpp"

#include <algorithm>
#include <memory>
#include <vector>

#include <gtest/gtest.h>

#include "test_world.hpp"

using maestro::test::Stone;
using maestro::test::Water;
using namespace maestro::world;

namespace {

constexpr Geometry SmallArea{32, -16, 48};

Column column(std::vector<Run> Runs) { return Column(std::move(Runs)); }

} // namespace

TEST(Column, ReadsTheRunHoldingEachOffset) {
  const Column Ground = column({{4, Stone}, {5, Water}, {48, Air}});
  EXPECT_EQ(Ground.at(0), Stone);
  EXPECT_EQ(Ground.at(3), Stone);
  EXPECT_EQ(Ground.at(4), Water);
  EXPECT_EQ(Ground.at(5), Air);
  EXPECT_EQ(Ground.at(47), Air);
}

TEST(Column, AnEditInsideARunSplitsIt) {
  const Column Ground = column({{4, Stone}, {48, Air}});
  const auto Edited = Ground.with(10, Water);
  const std::vector<maestro::world::Run> Expected = {
      {4, Stone}, {10, Air}, {11, Water}, {48, Air}};
  EXPECT_TRUE(std::ranges::equal(Edited->runs(), Expected));
  // The original is a version someone may still hold.
  EXPECT_EQ(Ground.at(10), Air);
}

TEST(Column, AnEditMatchingANeighbourMergesWithIt) {
  const Column Ground = column({{4, Stone}, {48, Air}});
  // Raising the ground by one block extends the stone run instead of adding
  // one.
  const auto Raised = Ground.with(4, Stone);
  const std::vector<maestro::world::Run> Expected = {{5, Stone}, {48, Air}};
  EXPECT_TRUE(std::ranges::equal(Raised->runs(), Expected));

  // Clearing the top stone block merges it into the air above.
  const auto Lowered = Ground.with(3, Air);
  const std::vector<maestro::world::Run> LoweredExpected = {{3, Stone},
                                                            {48, Air}};
  EXPECT_TRUE(std::ranges::equal(Lowered->runs(), LoweredExpected));
}

TEST(Column, EditsAtTheEndsKeepTheHeight) {
  const Column Ground = column({{48, Air}});
  const auto Bottom = Ground.with(0, Stone);
  const auto Top = Bottom->with(47, Stone);
  const std::vector<maestro::world::Run> Expected = {
      {1, Stone}, {47, Air}, {48, Stone}};
  EXPECT_TRUE(std::ranges::equal(Top->runs(), Expected));
}

TEST(Area, IsAirUntilAChunkArrives) {
  const Area Empty(SmallArea, 0);
  EXPECT_EQ(Empty.sequence(), 0u);
  EXPECT_EQ(Empty.at(0, -16, 0), Air);
  EXPECT_EQ(Empty.at(31, 31, 31), Air);
  EXPECT_EQ(Empty.column(5, 5), nullptr);
}

TEST(Area, ReadsOutsideItselfAsAir) {
  maestro::test::DenseWorld World(32, -16, 48);
  World.fill(0, -16, 0, 31, 31, 31, Stone);
  const auto Solid = World.load();
  EXPECT_EQ(Solid->at(0, -16, 0), Stone);
  EXPECT_EQ(Solid->at(-1, 0, 0), Air);
  EXPECT_EQ(Solid->at(0, 0, 32), Air);
  EXPECT_EQ(Solid->at(0, -17, 0), Air);
  EXPECT_EQ(Solid->at(0, 32, 0), Air);
}

TEST(Area, ANewVersionSharesWhatItDidNotChange) {
  maestro::test::DenseWorld World(32, -16, 48);
  World.fill(0, -16, 0, 31, -1, 31, Stone);
  const auto Before = World.load();

  const CellEdit Edit{3, 0, 4, Water};
  const auto After = Before->withCells({&Edit, 1}, Before->sequence() + 1);

  EXPECT_EQ(After->sequence(), Before->sequence() + 1);
  EXPECT_EQ(After->at(3, 0, 4), Water);
  EXPECT_EQ(Before->at(3, 0, 4), Air);
  // The edited column is new; its neighbour in the same chunk and every
  // column of the other chunks are shared.
  EXPECT_NE(After->column(3, 4), Before->column(3, 4));
  EXPECT_EQ(After->column(3, 5), Before->column(3, 5));
  EXPECT_EQ(After->column(20, 20), Before->column(20, 20));
}

TEST(Area, AnEditToAnAirChunkMaterializesIt) {
  const auto Empty = std::make_shared<const Area>(SmallArea, 0);
  const CellEdit Edit{17, 5, 2, Stone};
  const auto After = Empty->withCells({&Edit, 1}, 1);
  EXPECT_EQ(After->at(17, 5, 2), Stone);
  EXPECT_EQ(After->at(17, 4, 2), Air);
  EXPECT_EQ(After->at(18, 5, 2), Air);
  EXPECT_EQ(Empty->column(17, 2), nullptr);
}

TEST(Area, ClearingAChunkLeavesTheOthers) {
  maestro::test::DenseWorld World(32, -16, 48);
  World.fill(0, -16, 0, 31, -1, 31, Stone);
  const auto Before = World.load();
  const auto After = Before->withChunk(1, 0, nullptr, Before->sequence() + 1);
  EXPECT_EQ(After->at(16, -1, 0), Air);
  EXPECT_EQ(After->at(15, -1, 0), Stone);
  EXPECT_EQ(Before->at(16, -1, 0), Stone);
}
