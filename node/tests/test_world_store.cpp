#include "maestro/world/world_store.hpp"

#include <atomic>
#include <cstdint>
#include <random>
#include <thread>
#include <vector>

#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include "control_generated.h"
#include "maestro/search/vanilla_search.hpp"
#include "test_world.hpp"

using maestro::test::DenseWorld;
using maestro::test::Fence;
using maestro::test::Frame;
using maestro::test::Stone;
using maestro::test::Water;
using namespace maestro;
using namespace maestro::world;

namespace {

/// Sequence of what `Store` holds, 0 when it holds nothing.
std::uint64_t sequenceOf(const WorldStore &Store) {
  const auto Held = Store.pin();
  return Held ? Held->sequence() : 0;
}

DenseWorld flatWorld() {
  DenseWorld World(32, 0, 24);
  World.fill(0, 0, 0, 31, 3, 31, Stone);
  return World;
}

/// Feeds `Store` a fresh assignment for `World`, then its four chunks with
/// sequences 1 to 4.
void load(WorldStore &Store, const DenseWorld &World) {
  ASSERT_EQ(Store.apply(World.assignment()), ApplyStatus::Applied);
  std::uint64_t Sequence = 1;
  for (int X = 0; X < 2; ++X)
    for (int Z = 0; Z < 2; ++Z)
      ASSERT_EQ(Store.apply(World.chunk(Sequence++, X, Z)),
                ApplyStatus::Applied);
}

/// A chunk frame whose runs come from `Runs` for every column.
Frame uniformChunk(std::uint64_t Sequence,
                   const std::vector<fbs::CellRun> &Runs,
                   int Columns = ColumnsPerChunk) {
  std::vector<std::uint16_t> Counts(Columns,
                                    static_cast<std::uint16_t>(Runs.size()));
  std::vector<fbs::CellRun> All;
  for (int I = 0; I < Columns; ++I)
    All.insert(All.end(), Runs.begin(), Runs.end());
  flatbuffers::FlatBufferBuilder Builder;
  const auto CountsVec = Builder.CreateVector(Counts);
  const auto RunsVec = Builder.CreateVectorOfStructs(All);
  const fbs::Vec2i ChunkXz(0, 0);
  fbs::ChunkSnapshotBuilder Snapshot(Builder);
  Snapshot.add_sequence(Sequence);
  Snapshot.add_chunk_xz(&ChunkXz);
  Snapshot.add_run_counts(CountsVec);
  Snapshot.add_runs(RunsVec);
  const auto Body = Snapshot.Finish();
  Builder.Finish(fbs::CreateWorldFrame(Builder, fbs::WorldMsg::ChunkSnapshot,
                                       Body.Union()));
  return {Builder.GetBufferPointer(),
          Builder.GetBufferPointer() + Builder.GetSize()};
}

Frame assignmentOf(int Size, int MinY, int Height) {
  flatbuffers::FlatBufferBuilder Builder;
  const auto Body = fbs::CreateRegionAssignment(
      Builder, 1, 0, static_cast<std::uint16_t>(Size), MinY,
      static_cast<std::uint16_t>(Height), false);
  Builder.Finish(fbs::CreateWorldFrame(Builder, fbs::WorldMsg::RegionAssignment,
                                       Body.Union()));
  return {Builder.GetBufferPointer(),
          Builder.GetBufferPointer() + Builder.GetSize()};
}

/// Applies `Bad` to a store holding `flatWorld` and checks it is refused with
/// `Expected` and leaves nothing held.
void expectRefusedAndReset(const Frame &Bad, ApplyStatus Expected) {
  WorldStore Store;
  load(Store, flatWorld());
  EXPECT_EQ(Store.apply(Bad), Expected);
  EXPECT_EQ(Store.pin(), nullptr);
}

} // namespace

TEST(WorldStore, HoldsNothingBeforeAnAssignment) {
  WorldStore Store;
  EXPECT_EQ(Store.pin(), nullptr);
  EXPECT_EQ(Store.apply(flatWorld().chunk(1, 0, 0)), ApplyStatus::Unassigned);
  const CellEdit Edit{0, 0, 0, Water};
  EXPECT_EQ(Store.apply(test::delta(1, {&Edit, 1})), ApplyStatus::Unassigned);
  EXPECT_EQ(Store.pin(), nullptr);
}

TEST(WorldStore, AnAssignmentStartsTheAreaAsAir) {
  WorldStore Store;
  ASSERT_EQ(Store.apply(flatWorld().assignment(false, 9, 4)),
            ApplyStatus::Applied);
  const auto Held = Store.pin();
  ASSERT_NE(Held, nullptr);
  EXPECT_EQ(Held->geometry(), (Geometry{32, 0, 24}));
  EXPECT_EQ(Held->at(5, 1, 5), Air);
  EXPECT_EQ(Held->run(), 9u);
  EXPECT_EQ(Held->regionId(), 4u);
  EXPECT_EQ(Held->sequence(), 0u);
}

TEST(WorldStore, EveryVersionNamesTheStreamItBelongsTo) {
  WorldStore Store;
  ASSERT_EQ(Store.apply(flatWorld().assignment(false, 9, 4)),
            ApplyStatus::Applied);
  ASSERT_EQ(Store.apply(flatWorld().chunk(3, 0, 0)), ApplyStatus::Applied);
  const CellEdit Edit{0, 10, 0, Water};
  ASSERT_EQ(Store.apply(test::delta(4, {&Edit, 1})), ApplyStatus::Applied);
  const auto Held = Store.pin();
  EXPECT_EQ(Held->run(), 9u);
  EXPECT_EQ(Held->regionId(), 4u);
  EXPECT_EQ(Held->sequence(), 4u);
}

TEST(WorldStore, AChunkReadsBackAsSent) {
  DenseWorld World = flatWorld();
  World.set(17, 4, 3, Water);
  WorldStore Store;
  load(Store, World);
  const auto Held = Store.pin();
  EXPECT_EQ(Held->sequence(), 4u);
  for (int X = 0; X < 32; ++X)
    for (int Y = 0; Y < 24; ++Y)
      for (int Z = 0; Z < 32; ++Z)
        ASSERT_EQ(Held->at(X, Y, Z), World.at(X, Y, Z))
            << X << " " << Y << " " << Z;
}

TEST(WorldStore, IdenticalColumnsInAChunkShareOneCopy) {
  DenseWorld World = flatWorld();
  World.set(0, 4, 2, Water);
  WorldStore Store;
  load(Store, World);
  const auto Held = Store.pin();
  EXPECT_EQ(Held->column(0, 0), Held->column(0, 1));
  EXPECT_NE(Held->column(0, 2), Held->column(0, 1));
}

TEST(WorldStore, TheFirstFrameAfterAnAssignmentIsTheBaseline) {
  const DenseWorld World = flatWorld();
  WorldStore Store;
  ASSERT_EQ(Store.apply(World.assignment()), ApplyStatus::Applied);
  // The controller's sequence carries on across a reassignment, so the
  // first frame may start anywhere.
  EXPECT_EQ(Store.apply(World.chunk(41, 0, 0)), ApplyStatus::Applied);
  EXPECT_EQ(sequenceOf(Store), 41u);
  EXPECT_EQ(Store.pin()->at(0, 0, 16), Air);
  EXPECT_EQ(Store.apply(World.chunk(42, 0, 1)), ApplyStatus::Applied);
  EXPECT_EQ(Store.pin()->at(0, 0, 16), Stone);
}

TEST(WorldStore, AFrameOutOfSequenceDropsWhatIsHeld) {
  const DenseWorld World = flatWorld();
  // A gap, and a frame already applied.
  expectRefusedAndReset(World.chunk(6, 0, 0), ApplyStatus::OutOfSequence);
  expectRefusedAndReset(World.chunk(4, 0, 0), ApplyStatus::OutOfSequence);
  const CellEdit Edit{0, 10, 0, Water};
  expectRefusedAndReset(test::delta(7, {&Edit, 1}), ApplyStatus::OutOfSequence);
}

TEST(WorldStore, SequenceZeroIsNeverAFrame) {
  const DenseWorld World = flatWorld();
  WorldStore Store;
  ASSERT_EQ(Store.apply(World.assignment()), ApplyStatus::Applied);
  EXPECT_EQ(Store.apply(World.chunk(0, 0, 0)), ApplyStatus::Malformed);
  EXPECT_EQ(Store.pin(), nullptr);
}

TEST(WorldStore, AClearedChunkReadsAsAir) {
  WorldStore Store;
  load(Store, flatWorld());
  ASSERT_EQ(Store.apply(test::clearedChunk(5, 1, 1)), ApplyStatus::Applied);
  EXPECT_EQ(Store.pin()->at(20, 0, 20), Air);
  EXPECT_EQ(Store.pin()->at(5, 0, 20), Stone);
}

TEST(WorldStore, ADeltaPublishesANewVersionAndLeavesPinnedOnesAlone) {
  WorldStore Store;
  load(Store, flatWorld());
  const auto Pinned = Store.pin();
  const std::vector<CellEdit> Edits = {{3, 4, 3, Water}, {3, 3, 3, Water}};
  ASSERT_EQ(Store.apply(test::delta(5, Edits)), ApplyStatus::Applied);

  const auto Current = Store.pin();
  EXPECT_EQ(Current->sequence(), 5u);
  EXPECT_EQ(Current->at(3, 4, 3), Water);
  EXPECT_EQ(Current->at(3, 3, 3), Water);
  EXPECT_EQ(Pinned->sequence(), 4u);
  EXPECT_EQ(Pinned->at(3, 4, 3), Air);
  EXPECT_EQ(Current->column(30, 30), Pinned->column(30, 30));
}

TEST(WorldStore, AnEmptyDeltaStillAdvancesTheSequence) {
  WorldStore Store;
  load(Store, flatWorld());
  EXPECT_EQ(Store.apply(test::delta(5, {})), ApplyStatus::Applied);
  EXPECT_EQ(sequenceOf(Store), 5u);
}

TEST(WorldStore, AFreshAssignmentDropsWhatIsHeld) {
  const DenseWorld World = flatWorld();
  WorldStore Store;
  load(Store, World);
  ASSERT_EQ(Store.apply(World.assignment(false, 2, 0)), ApplyStatus::Applied);
  EXPECT_EQ(Store.pin()->at(0, 0, 0), Air);
  EXPECT_EQ(sequenceOf(Store), 0u);
  EXPECT_EQ(Store.pin()->run(), 2u);
  EXPECT_EQ(Store.apply(World.chunk(90, 0, 0)), ApplyStatus::Applied);
}

TEST(WorldStore, AResumeKeepsWhatIsHeldAndContinuesTheStream) {
  const DenseWorld World = flatWorld();
  WorldStore Store;
  load(Store, World);
  const auto Held = Store.pin();
  ASSERT_EQ(Store.apply(World.assignment(true)), ApplyStatus::Applied);
  EXPECT_EQ(Store.pin(), Held);
  EXPECT_EQ(Store.apply(World.chunk(5, 0, 0)), ApplyStatus::Applied);
}

TEST(WorldStore, AResumeOfAStreamNotHeldIsRefused) {
  const DenseWorld World = flatWorld();

  WorldStore Empty;
  EXPECT_EQ(Empty.apply(World.assignment(true)), ApplyStatus::CannotResume);
  EXPECT_EQ(Empty.pin(), nullptr);

  WorldStore NoFrames;
  ASSERT_EQ(NoFrames.apply(World.assignment()), ApplyStatus::Applied);
  EXPECT_EQ(NoFrames.apply(World.assignment(true)), ApplyStatus::CannotResume);
  EXPECT_EQ(NoFrames.pin(), nullptr);

  // Another run, another region, and another shape.
  expectRefusedAndReset(World.assignment(true, /*Run=*/2),
                        ApplyStatus::CannotResume);
  expectRefusedAndReset(World.assignment(true, 1, /*RegionId=*/3),
                        ApplyStatus::CannotResume);
  expectRefusedAndReset(DenseWorld(48, 0, 24).assignment(true),
                        ApplyStatus::CannotResume);
}

TEST(WorldStore, AfterARejectionTheNextStreamStartsClean) {
  const DenseWorld World = flatWorld();
  WorldStore Store;
  load(Store, World);
  ASSERT_EQ(Store.apply(World.chunk(9, 0, 0)), ApplyStatus::OutOfSequence);
  // What the controller sends a node whose `Hello` reported nothing held.
  ASSERT_EQ(Store.apply(World.assignment(false, 1, 0)), ApplyStatus::Applied);
  ASSERT_EQ(Store.apply(World.chunk(20, 0, 0)), ApplyStatus::Applied);
  EXPECT_EQ(Store.pin()->at(0, 0, 0), Stone);
  EXPECT_EQ(Store.pin()->at(20, 0, 20), Air);
}

TEST(WorldStore, RejectsBytesThatAreNotAFrame) {
  const std::vector<std::uint8_t> Garbage = {1, 2, 3, 4, 5, 6, 7, 8};
  expectRefusedAndReset(Garbage, ApplyStatus::Malformed);
  expectRefusedAndReset({}, ApplyStatus::Malformed);
}

TEST(WorldStore, RejectsAnAreaThatDoesNotTileIntoChunks) {
  expectRefusedAndReset(assignmentOf(24, 0, 24), ApplyStatus::Malformed);
  expectRefusedAndReset(assignmentOf(0, 0, 24), ApplyStatus::Malformed);
  expectRefusedAndReset(assignmentOf(32, 0, 0), ApplyStatus::Malformed);
  // Positions on the wire are shorts, so the top of the area has to be one.
  expectRefusedAndReset(assignmentOf(32, 32700, 100), ApplyStatus::Malformed);
}

TEST(WorldStore, RejectsChunksThatBreakTheContract) {
  const auto Stone4 = fbs::CellRun(4, fbs::PathType::BLOCKED, 16);
  const auto Air20 = fbs::CellRun(20, fbs::PathType::OPEN, 0);

  // Columns that fall short of, or overrun, the height.
  expectRefusedAndReset(uniformChunk(5, {Stone4}), ApplyStatus::Malformed);
  expectRefusedAndReset(uniformChunk(5, {Stone4, Air20, Stone4}),
                        ApplyStatus::Malformed);
  // One column short.
  expectRefusedAndReset(uniformChunk(5, {Stone4, Air20}, ColumnsPerChunk - 1),
                        ApplyStatus::Malformed);
  // A zero-length run.
  expectRefusedAndReset(
      uniformChunk(5,
                   {Stone4, fbs::CellRun(0, fbs::PathType::WATER, 0), Air20}),
      ApplyStatus::Malformed);
  // A type only the node may derive.
  expectRefusedAndReset(
      uniformChunk(5, {fbs::CellRun(4, fbs::PathType::WALKABLE, 0), Air20}),
      ApplyStatus::Malformed);
  // A type no ordinal names.
  expectRefusedAndReset(
      uniformChunk(
          5, {fbs::CellRun(4, static_cast<fbs::PathType>(200), 0), Air20}),
      ApplyStatus::Malformed);

  WorldStore Store;
  load(Store, flatWorld());
  EXPECT_EQ(Store.apply(uniformChunk(5, {Stone4, Air20})),
            ApplyStatus::Applied);
}

TEST(WorldStore, RejectsAChunkOutsideTheArea) {
  expectRefusedAndReset(test::clearedChunk(5, 2, 0), ApplyStatus::Malformed);
  expectRefusedAndReset(test::clearedChunk(5, 0, -1), ApplyStatus::Malformed);
}

TEST(WorldStore, RejectsADeltaThatBreaksTheContract) {
  // The valid first edit is not applied either: the whole frame is refused.
  const std::vector<CellEdit> Outside = {{1, 1, 1, Water}, {32, 1, 1, Water}};
  expectRefusedAndReset(test::delta(5, Outside), ApplyStatus::Malformed);
  const std::vector<CellEdit> AboveTheTop = {{1, 24, 1, Water}};
  expectRefusedAndReset(test::delta(5, AboveTheTop), ApplyStatus::Malformed);
  const std::vector<CellEdit> Derived = {
      {1, 4, 1, Cell{PathType::WATER_BORDER, 0}}};
  expectRefusedAndReset(test::delta(5, Derived), ApplyStatus::Malformed);
}

TEST(WorldStore, MatchesADenseModelUnderRandomFrames) {
  // `Server` is what the controller reads chunks from; `Expected` is what the
  // node should hold after each frame.
  constexpr int Size = 48;
  constexpr int MinY = -8;
  constexpr int Height = 24;
  constexpr int PerSide = Size / ChunkSize;
  constexpr int Frames = 300;
  DenseWorld Server(Size, MinY, Height);
  DenseWorld Expected(Size, MinY, Height);
  std::mt19937 Rng(20261007);
  const auto Below = [&](int Bound) {
    return std::uniform_int_distribution<int>(0, Bound - 1)(Rng);
  };
  const std::vector<Cell> Palette = {Air,
                                     Stone,
                                     Water,
                                     Fence,
                                     Cell{PathType::LAVA, 0},
                                     Cell{PathType::TRAPDOOR, 3},
                                     Cell{PathType::LEAVES, 16},
                                     Cell{PathType::DAMAGING, 15}};
  const auto RandomCell = [&] {
    return Palette[Below(static_cast<int>(Palette.size()))];
  };

  WorldStore Store;
  ASSERT_EQ(Store.apply(Expected.assignment()), ApplyStatus::Applied);
  std::shared_ptr<const Area> Old;
  DenseWorld OldExpected = Expected;
  for (std::uint64_t Sequence = 1; Sequence <= Frames; ++Sequence) {
    const int Kind = Below(4);
    if (Kind == 0) {
      // A chunk loads with new contents.
      const int ChunkX = Below(PerSide);
      const int ChunkZ = Below(PerSide);
      for (int X = ChunkX * ChunkSize; X < (ChunkX + 1) * ChunkSize; ++X)
        for (int Z = ChunkZ * ChunkSize; Z < (ChunkZ + 1) * ChunkSize; ++Z) {
          const int Ground = MinY + Below(Height);
          for (int Y = MinY; Y < MinY + Height; ++Y) {
            const Cell Value = Y < Ground      ? Stone
                               : Below(6) == 0 ? RandomCell()
                                               : Air;
            Server.set(X, Y, Z, Value);
            Expected.set(X, Y, Z, Value);
          }
        }
      ASSERT_EQ(Store.apply(Server.chunk(Sequence, ChunkX, ChunkZ)),
                ApplyStatus::Applied);
    } else if (Kind == 1) {
      // A chunk leaves.
      const int ChunkX = Below(PerSide);
      const int ChunkZ = Below(PerSide);
      Expected.fill(ChunkX * ChunkSize, MinY, ChunkZ * ChunkSize,
                    (ChunkX + 1) * ChunkSize - 1, MinY + Height - 1,
                    (ChunkZ + 1) * ChunkSize - 1, Air);
      ASSERT_EQ(Store.apply(test::clearedChunk(Sequence, ChunkX, ChunkZ)),
                ApplyStatus::Applied);
    } else {
      // Blocks change, sometimes the same one twice in a frame.
      std::vector<CellEdit> Edits;
      const int Count = Below(12);
      for (int I = 0; I < Count; ++I) {
        CellEdit Edit{Below(Size), MinY + Below(Height), Below(Size),
                      RandomCell()};
        if (I > 0 && Below(5) == 0) {
          Edit.X = Edits.back().X;
          Edit.Y = Edits.back().Y;
          Edit.Z = Edits.back().Z;
        }
        Expected.set(Edit.X, Edit.Y, Edit.Z, Edit.Value);
        Edits.push_back(Edit);
      }
      ASSERT_EQ(Store.apply(test::delta(Sequence, Edits)),
                ApplyStatus::Applied);
    }

    const auto Held = Store.pin();
    ASSERT_EQ(Held->sequence(), Sequence);
    for (int X = 0; X < Size; ++X)
      for (int Y = MinY; Y < MinY + Height; ++Y)
        for (int Z = 0; Z < Size; ++Z)
          ASSERT_EQ(Held->at(X, Y, Z), Expected.at(X, Y, Z))
              << "frame " << Sequence << " at " << X << " " << Y << " " << Z;
    if (Sequence == Frames / 2) {
      Old = Held;
      OldExpected = Expected;
    }
  }

  // A version pinned half way through still reads as it did then.
  for (int X = 0; X < Size; ++X)
    for (int Y = MinY; Y < MinY + Height; ++Y)
      for (int Z = 0; Z < Size; ++Z)
        ASSERT_EQ(Old->at(X, Y, Z), OldExpected.at(X, Y, Z))
            << X << " " << Y << " " << Z;
}

TEST(WorldStore, ReadersSeeWholeFramesWhileFramesAreApplied) {
  // Every delta sets two cells in different rows of chunks to a value its
  // sequence decides, so a reader that saw half a frame, or a version and a
  // sequence that disagree, would notice. Searches run on the same pinned
  // versions so the shared columns are read while new versions are built.
  // Under ThreadSanitizer this is also the data race check.
  constexpr std::uint64_t LastSequence = 2000;
  const auto ValueAt = [](std::uint64_t Sequence) {
    return Sequence % 2 == 0 ? Stone : Water;
  };
  const DenseWorld World = flatWorld();
  WorldStore Store;
  load(Store, World);

  std::atomic<bool> Done = false;
  std::atomic<int> Failures = 0;
  const auto Reader = [&] {
    search::VanillaSearch Search;
    search::Result Out;
    search::Request Call;
    Call.Start = {2, 4, 2};
    Call.Target = {28, 4, 28};
    Call.MaxPathLength = 40.0F;
    Call.MaxVisitedNodes = 200;
    while (!Done.load()) {
      const auto Held = Store.pin();
      const Cell A = Held->at(1, 10, 1);
      const Cell B = Held->at(30, 10, 30);
      const bool Fresh = Held->sequence() <= 4;
      if (A != B || (Fresh ? A != Air : A != ValueAt(Held->sequence())))
        ++Failures;
      Search.find(*Held, search::Profile{}, Call, Out);
      if (Out.Nodes.empty())
        ++Failures;
    }
  };

  std::vector<std::thread> Readers;
  for (int I = 0; I < 3; ++I)
    Readers.emplace_back(Reader);
  bool AllApplied = true;
  for (std::uint64_t Sequence = 5; Sequence <= LastSequence && AllApplied;
       ++Sequence) {
    const std::vector<CellEdit> Edits = {{1, 10, 1, ValueAt(Sequence)},
                                         {30, 10, 30, ValueAt(Sequence)}};
    AllApplied =
        Store.apply(test::delta(Sequence, Edits)) == ApplyStatus::Applied;
  }
  Done = true;
  for (std::thread &Thread : Readers)
    Thread.join();

  EXPECT_TRUE(AllApplied);
  EXPECT_EQ(Failures.load(), 0);
  EXPECT_EQ(sequenceOf(Store), LastSequence);
}
