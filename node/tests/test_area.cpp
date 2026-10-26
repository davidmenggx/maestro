#include "maestro/world/area.hpp"

#include <algorithm>
#include <memory>
#include <random>
#include <vector>

#include <gtest/gtest.h>

#include "test_world.hpp"

using maestro::test::Stone;
using maestro::test::Water;
using namespace maestro::world;

namespace {

constexpr Geometry SmallArea{32, 32, -16, 48};

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
  // The original must stay unchanged, as a search may still hold it.
  EXPECT_EQ(Ground.at(10), Air);
}

TEST(Column, AnEditMatchingANeighbourMergesWithIt) {
  const Column Ground = column({{4, Stone}, {48, Air}});
  // Raising the ground extends the stone run.
  const auto Raised = Ground.with(4, Stone);
  const std::vector<maestro::world::Run> Expected = {{5, Stone}, {48, Air}};
  EXPECT_TRUE(std::ranges::equal(Raised->runs(), Expected));

  // Clearing the top stone merges it into the air run above.
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

TEST(Column, SeveralEditsGiveWhatOneAtATimeGives) {
  std::mt19937 Rng(17);
  const std::vector<Cell> Palette = {Air, Stone, Water};
  for (int Trial = 0; Trial < 200; ++Trial) {
    auto Ground = std::make_shared<const Column>(
        std::vector<maestro::world::Run>{{48, Air}});
    for (int I = 0; I < 6; ++I)
      Ground = Ground->with(static_cast<int>(Rng() % 48), Palette[Rng() % 3]);
    std::vector<ColumnEdit> Edits;
    auto OneAtATime = Ground;
    for (int Offset = 0; Offset < 48; ++Offset)
      if (Rng() % 4 == 0) {
        Edits.push_back({Offset, Palette[Rng() % 3]});
        OneAtATime = OneAtATime->with(Offset, Edits.back().Value);
      }
    const auto Together = Ground->with(Edits);
    EXPECT_TRUE(std::ranges::equal(Together->runs(), OneAtATime->runs()))
        << Trial;
  }
}

TEST(Area, IsAirUntilAChunkArrives) {
  const Area Empty(SmallArea, 1, 0);
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
  // Only the edited column is new.
  EXPECT_NE(After->column(3, 4), Before->column(3, 4));
  EXPECT_EQ(After->column(3, 5), Before->column(3, 5));
  EXPECT_EQ(After->column(20, 20), Before->column(20, 20));
}

TEST(Area, AnEditToAnAirChunkMaterializesIt) {
  const auto Empty = std::make_shared<const Area>(SmallArea, 1, 0);
  const CellEdit Edit{17, 5, 2, Stone};
  const auto After = Empty->withCells({&Edit, 1}, 1);
  EXPECT_EQ(After->at(17, 5, 2), Stone);
  EXPECT_EQ(After->at(17, 4, 2), Air);
  EXPECT_EQ(After->at(18, 5, 2), Air);
  EXPECT_EQ(Empty->column(17, 2), nullptr);
}

TEST(Area, ANewVersionKeepsItsStreamAndTakesTheFramesSequence) {
  const auto Empty = std::make_shared<const Area>(SmallArea, 9, 4);
  const CellEdit Edit{1, 0, 1, Stone};
  const auto Edited = Empty->withCells({&Edit, 1}, 7);
  const auto Cleared = Edited->withChunk(0, 0, nullptr, 8);
  for (const auto &Version : {Edited, Cleared}) {
    EXPECT_EQ(Version->run(), 9u);
    EXPECT_EQ(Version->regionId(), 4u);
  }
  EXPECT_EQ(Edited->sequence(), 7u);
  EXPECT_EQ(Cleared->sequence(), 8u);
}

TEST(Area, AnEditSharesTheRestOfItsRowAndEveryOtherRow) {
  // Three chunks per side, so neighbours exist in the same and other rows.
  maestro::test::DenseWorld World(48, -16, 48);
  for (int X = 0; X < 48; ++X)
    for (int Z = 0; Z < 48; ++Z)
      World.fill(X, -16, Z, X, -16 + (X + Z) % 5, Z, Stone);
  const auto Before = World.load();

  const CellEdit Edit{20, 10, 20, Water};
  const auto After = Before->withCells({&Edit, 1}, Before->sequence() + 1);

  EXPECT_NE(After->column(20, 20), Before->column(20, 20));
  EXPECT_EQ(After->column(21, 20), Before->column(21, 20));
  EXPECT_EQ(After->column(20, 40), Before->column(20, 40));
  EXPECT_EQ(After->column(40, 20), Before->column(40, 20));
}

TEST(Area, ReplacingAChunkSharesEveryOtherChunk) {
  maestro::test::DenseWorld World(48, -16, 48);
  World.fill(0, -16, 0, 47, -1, 47, Stone);
  World.set(40, 0, 40, Water);
  const auto Before = World.load();
  const auto After = Before->withChunk(1, 1, nullptr, Before->sequence() + 1);
  EXPECT_EQ(After->column(20, 20), nullptr);
  EXPECT_EQ(After->column(20, 40), Before->column(20, 40));
  EXPECT_EQ(After->column(40, 40), Before->column(40, 40));
  EXPECT_EQ(After->at(40, 0, 40), Water);
}

TEST(Area, OneDeltaCanEditSeveralChunksAndRows) {
  const auto Empty = std::make_shared<const Area>(SmallArea, 1, 0);
  const std::vector<CellEdit> Edits = {{1, 0, 1, Stone},
                                       {1, 0, 20, Water},
                                       {20, 0, 1, Stone},
                                       {20, 0, 20, Water},
                                       {1, 1, 1, Water}};
  const auto After = Empty->withCells(Edits, 1);
  EXPECT_EQ(After->at(1, 0, 1), Stone);
  EXPECT_EQ(After->at(1, 1, 1), Water);
  EXPECT_EQ(After->at(1, 0, 20), Water);
  EXPECT_EQ(After->at(20, 0, 1), Stone);
  EXPECT_EQ(After->at(20, 0, 20), Water);
  EXPECT_EQ(Empty->at(20, 0, 20), Air);
}

TEST(Area, ARectangularAreaKeepsEachChunkApart) {
  for (const Geometry Geo : {Geometry{48, 16, 0, 8}, Geometry{16, 48, 0, 8}}) {
    const auto Empty = std::make_shared<const Area>(Geo, 1, 0);
    std::vector<CellEdit> Edits;
    for (int X = 0; X < Geo.SizeX; X += 16)
      for (int Z = 0; Z < Geo.SizeZ; Z += 16)
        Edits.push_back({X + 3, 2, Z + 5, Cell{PathType::FENCE, 24}});
    const auto After = Empty->withCells(Edits, 1);
    for (int X = 0; X < Geo.SizeX; X += 16)
      for (int Z = 0; Z < Geo.SizeZ; Z += 16) {
        EXPECT_EQ(After->at(X + 3, 2, Z + 5), (Cell{PathType::FENCE, 24}));
        EXPECT_EQ(After->at(X + 5, 2, Z + 3), Air);
      }
    EXPECT_EQ(After->at(Geo.SizeX, 2, 0), Air);
    EXPECT_EQ(After->at(0, 2, Geo.SizeZ), Air);

    const auto Cleared =
        After->withChunk(Geo.chunksX() - 1, Geo.chunksZ() - 1, nullptr, 2);
    EXPECT_EQ(Cleared->at(Geo.SizeX - 13, 2, Geo.SizeZ - 11), Air);
    EXPECT_EQ(Cleared->at(3, 2, 5), (Cell{PathType::FENCE, 24}));
  }
}

TEST(Area, ACropKeepsTheChunksInsideWhereTheyWereAndSharesThem) {
  const Geometry Whole{48, 48, 0, 8};
  const auto Empty = std::make_shared<const Area>(Whole, 1, 3);
  std::vector<CellEdit> Edits;
  for (int X = 0; X < Whole.SizeX; X += 16)
    for (int Z = 0; Z < Whole.SizeZ; Z += 16)
      Edits.push_back({X + 3, 2, Z + 5, Cell{PathType::FENCE, 24}});
  const auto Before = Empty->withCells(Edits, 6);

  for (const Geometry To : {Geometry{48, 16, 0, 8}, Geometry{16, 48, 0, 8},
                            Geometry{32, 32, 0, 8}}) {
    const auto After = Before->cropped(To, 9);
    EXPECT_EQ(After->geometry(), To);
    EXPECT_EQ(After->run(), 1u);
    EXPECT_EQ(After->regionId(), 9u);
    EXPECT_EQ(After->sequence(), 6u);
    for (int X = 0; X < Whole.SizeX; X += 16)
      for (int Z = 0; Z < Whole.SizeZ; Z += 16) {
        const bool Inside = X < To.SizeX && Z < To.SizeZ;
        const Cell Expected = Inside ? Cell{PathType::FENCE, 24} : Air;
        EXPECT_EQ(After->at(X + 3, 2, Z + 5), Expected);
        if (Inside)
          EXPECT_EQ(After->column(X + 3, Z + 5), Before->column(X + 3, Z + 5));
      }
    // The version cropped from is unchanged, as a search may still hold it.
    EXPECT_EQ(Before->at(35, 2, 37), (Cell{PathType::FENCE, 24}));
  }
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

TEST(Area, TheLastEditToACellWinsHoweverTheEditsInterleave) {
  // Few cells, so one delta edits most columns and cells repeatedly.
  constexpr int Size = 48;
  constexpr int MinY = -8;
  constexpr int Height = 16;
  maestro::test::DenseWorld Expected(Size, MinY, Height);
  Expected.fill(0, MinY, 0, Size - 1, -1, Size - 1, Stone);
  std::shared_ptr<const Area> Held = Expected.load();
  std::mt19937 Rng(23);
  const std::vector<Cell> Palette = {Air, Stone, Water,
                                     Cell{PathType::FENCE, 24}};
  for (int Delta = 0; Delta < 20; ++Delta) {
    std::vector<CellEdit> Edits;
    for (int I = 0; I < 400; ++I) {
      const int X = static_cast<int>(Rng() % 3) * 17 % Size;
      const int Z = static_cast<int>(Rng() % 3) * 19 % Size;
      const int Y = MinY + static_cast<int>(Rng() % 4) * 5 % Height;
      Edits.push_back({X, Y, Z, Palette[Rng() % Palette.size()]});
      Expected.set(X, Y, Z, Edits.back().Value);
    }
    const auto Before = Held;
    Held = Held->withCells(Edits, Held->sequence() + 1);
    for (int X = 0; X < Size; ++X)
      for (int Y = MinY; Y < MinY + Height; ++Y)
        for (int Z = 0; Z < Size; ++Z)
          ASSERT_EQ(Held->at(X, Y, Z), Expected.at(X, Y, Z))
              << Delta << ": " << X << " " << Y << " " << Z;
    // A column no edit touched is shared.
    EXPECT_EQ(Held->column(1, 1), Before->column(1, 1));
  }
}

TEST(Column, AnEditThatChangesNothingReturnsTheSameColumn) {
  const auto Ground = std::make_shared<const Column>(
      std::vector<maestro::world::Run>{{4, Stone}, {48, Air}});
  EXPECT_EQ(Ground->with(2, Stone), Ground);
  const std::vector<ColumnEdit> Same = {{0, Stone}, {3, Stone}, {47, Air}};
  EXPECT_EQ(Ground->with(Same), Ground);
  const std::vector<ColumnEdit> OneDiffers = {{3, Stone}, {4, Water}};
  EXPECT_NE(Ground->with(OneDiffers), Ground);
}

TEST(Area, EditsThatChangeNothingKeepEverythingShared) {
  maestro::test::DenseWorld World(32, -16, 48);
  World.fill(0, -16, 0, 15, -1, 31, Stone);
  World.set(3, 0, 4, Water);
  const auto Before = World.load();
  const std::vector<CellEdit> Edits = {// Each cell set to what it holds.
                                       {3, -1, 4, Stone},
                                       {3, 0, 4, Water},
                                       {3, 1, 4, Air},
                                       // A cell changed, then changed back.
                                       {5, 0, 5, Stone},
                                       {5, 0, 5, Air},
                                       // Air in a chunk of air.
                                       {20, 0, 20, Air}};
  // Air in a chunk that was cleared, which is not made from air for it.
  const auto Cleared = Before->withChunk(1, 0, nullptr, Before->sequence() + 1);
  const CellEdit ClearedEdit{20, 0, 3, Air};
  std::vector<CellEdit> All = Edits;
  All.push_back(ClearedEdit);

  const auto After = Cleared->withCells(All, Cleared->sequence() + 1);
  EXPECT_EQ(After->sequence(), Cleared->sequence() + 1);
  EXPECT_EQ(After->column(3, 4), Before->column(3, 4));
  EXPECT_EQ(After->column(5, 5), Before->column(5, 5));
  EXPECT_EQ(After->column(20, 20), Before->column(20, 20));
  EXPECT_EQ(After->column(20, 3), nullptr);
  EXPECT_EQ(After->at(3, 0, 4), Water);
  EXPECT_EQ(After->at(5, 0, 5), Air);
}

TEST(Area, TheLastOfSeveralEditsToOneCellWins) {
  const auto Empty = std::make_shared<const Area>(SmallArea, 1, 0);
  const std::vector<CellEdit> Edits = {{1, 0, 1, Stone},
                                       {2, 0, 1, Stone},
                                       {1, 0, 1, Water},
                                       {2, 0, 1, Air},
                                       {1, 0, 1, Stone}};
  const auto After = Empty->withCells(Edits, 1);
  EXPECT_EQ(After->at(1, 0, 1), Stone);
  EXPECT_EQ(After->at(2, 0, 1), Air);
  EXPECT_EQ(After->at(1, 1, 1), Air);
}

TEST(Area, EditsAcrossChunksEachLandInTheirOwn) {
  maestro::test::DenseWorld World(48, -16, 48);
  World.fill(0, -16, 0, 47, -1, 47, Stone);
  const auto Before = World.load();
  // Interleaved across chunks and rows, at chunk edges.
  const std::vector<CellEdit> Edits = {{15, 0, 15, Water}, {16, 0, 15, Stone},
                                       {15, 0, 16, Stone}, {47, 0, 47, Water},
                                       {16, 0, 16, Water}, {0, 0, 47, Stone}};
  const auto After = Before->withCells(Edits, Before->sequence() + 1);
  for (const CellEdit &Edit : Edits) {
    EXPECT_EQ(After->at(Edit.X, Edit.Y, Edit.Z), Edit.Value)
        << Edit.X << " " << Edit.Z;
    EXPECT_EQ(After->at(Edit.X, Edit.Y + 1, Edit.Z), Air);
    EXPECT_EQ(Before->at(Edit.X, Edit.Y, Edit.Z), Air);
  }
  // Untouched neighbours and chunks are shared.
  EXPECT_EQ(After->column(14, 15), Before->column(14, 15));
  EXPECT_EQ(After->column(17, 16), Before->column(17, 16));
  EXPECT_EQ(After->column(40, 8), Before->column(40, 8));
}
