#include "maestro/world/world_store.hpp"

#include <cstdint>
#include <vector>

#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include "control_generated.h"
#include "test_world.hpp"

using maestro::test::DenseWorld;
using maestro::test::Frame;
using maestro::test::Stone;
using maestro::test::Water;
using namespace maestro;
using namespace maestro::world;

namespace {

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
  const fbs::Vec2i MinXz(0, 0);
  const auto Body = fbs::CreateRegionAssignment(
      Builder, 1, 0, &MinXz, static_cast<std::uint16_t>(Size), MinY,
      static_cast<std::uint16_t>(Height), false);
  Builder.Finish(fbs::CreateWorldFrame(Builder, fbs::WorldMsg::RegionAssignment,
                                       Body.Union()));
  return {Builder.GetBufferPointer(),
          Builder.GetBufferPointer() + Builder.GetSize()};
}

} // namespace

TEST(WorldStore, HoldsNothingBeforeAnAssignment) {
  WorldStore Store;
  EXPECT_EQ(Store.pin(), nullptr);
  EXPECT_EQ(Store.lastSequence(), 0u);
  EXPECT_EQ(Store.apply(flatWorld().chunk(1, 0, 0)), ApplyStatus::Unassigned);
  const CellEdit Edit{0, 0, 0, Water};
  EXPECT_EQ(Store.apply(test::delta(1, {&Edit, 1})), ApplyStatus::Unassigned);
}

TEST(WorldStore, AnAssignmentStartsTheAreaAsAir) {
  WorldStore Store;
  ASSERT_EQ(Store.apply(flatWorld().assignment(false, 9, 4)),
            ApplyStatus::Applied);
  const auto Held = Store.pin();
  ASSERT_NE(Held, nullptr);
  EXPECT_EQ(Held->geometry(), (Geometry{32, 0, 24}));
  EXPECT_EQ(Held->at(5, 1, 5), Air);
  EXPECT_EQ(Store.run(), 9u);
  EXPECT_EQ(Store.regionId(), 4u);
  EXPECT_EQ(Store.lastSequence(), 0u);
}

TEST(WorldStore, AChunkReadsBackAsSent) {
  DenseWorld World = flatWorld();
  World.set(17, 4, 3, Water);
  WorldStore Store;
  load(Store, World);
  const auto Held = Store.pin();
  EXPECT_EQ(Store.lastSequence(), 4u);
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
  EXPECT_EQ(Store.lastSequence(), 41u);
  EXPECT_EQ(Store.apply(World.chunk(43, 0, 1)), ApplyStatus::OutOfSequence);
  EXPECT_EQ(Store.apply(World.chunk(41, 0, 1)), ApplyStatus::OutOfSequence);
  EXPECT_EQ(Store.pin()->at(0, 0, 16), Air);
  EXPECT_EQ(Store.apply(World.chunk(42, 0, 1)), ApplyStatus::Applied);
  EXPECT_EQ(Store.pin()->at(0, 0, 16), Stone);
}

TEST(WorldStore, SequenceZeroIsNeverAFrame) {
  const DenseWorld World = flatWorld();
  WorldStore Store;
  ASSERT_EQ(Store.apply(World.assignment()), ApplyStatus::Applied);
  EXPECT_EQ(Store.apply(World.chunk(0, 0, 0)), ApplyStatus::Malformed);
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
  EXPECT_EQ(Store.lastSequence(), 5u);
}

TEST(WorldStore, AFreshAssignmentDropsWhatIsHeld) {
  const DenseWorld World = flatWorld();
  WorldStore Store;
  load(Store, World);
  ASSERT_EQ(Store.apply(World.assignment(false, 2, 0)), ApplyStatus::Applied);
  EXPECT_EQ(Store.pin()->at(0, 0, 0), Air);
  EXPECT_EQ(Store.lastSequence(), 0u);
  EXPECT_EQ(Store.run(), 2u);
  EXPECT_EQ(Store.apply(World.chunk(90, 0, 0)), ApplyStatus::Applied);
}

TEST(WorldStore, AResumeKeepsWhatIsHeldAndContinuesTheStream) {
  const DenseWorld World = flatWorld();
  WorldStore Store;
  load(Store, World);
  const auto Held = Store.pin();
  ASSERT_EQ(Store.apply(World.assignment(true)), ApplyStatus::Applied);
  EXPECT_EQ(Store.pin(), Held);
  EXPECT_EQ(Store.apply(World.chunk(6, 0, 0)), ApplyStatus::OutOfSequence);
  EXPECT_EQ(Store.apply(World.chunk(5, 0, 0)), ApplyStatus::Applied);
}

TEST(WorldStore, AResumeOfAStreamNotHeldIsRefused) {
  const DenseWorld World = flatWorld();

  WorldStore Empty;
  EXPECT_EQ(Empty.apply(World.assignment(true)), ApplyStatus::CannotResume);

  WorldStore NoFrames;
  ASSERT_EQ(NoFrames.apply(World.assignment()), ApplyStatus::Applied);
  EXPECT_EQ(NoFrames.apply(World.assignment(true)), ApplyStatus::CannotResume);

  WorldStore Store;
  load(Store, World);
  EXPECT_EQ(Store.apply(World.assignment(true, /*Run=*/2)),
            ApplyStatus::CannotResume);
  EXPECT_EQ(Store.apply(World.assignment(true, 1, /*RegionId=*/3)),
            ApplyStatus::CannotResume);
  EXPECT_EQ(Store.apply(DenseWorld(48, 0, 24).assignment(true)),
            ApplyStatus::CannotResume);
  // A refused resume leaves the stream where it was.
  EXPECT_EQ(Store.lastSequence(), 4u);
  EXPECT_EQ(Store.pin()->at(0, 0, 0), Stone);
}

TEST(WorldStore, RejectsBytesThatAreNotAFrame) {
  WorldStore Store;
  const std::vector<std::uint8_t> Garbage = {1, 2, 3, 4, 5, 6, 7, 8};
  EXPECT_EQ(Store.apply(Garbage), ApplyStatus::Malformed);
  EXPECT_EQ(Store.apply({}), ApplyStatus::Malformed);
}

TEST(WorldStore, RejectsAnAreaThatDoesNotTileIntoChunks) {
  WorldStore Store;
  EXPECT_EQ(Store.apply(assignmentOf(24, 0, 24)), ApplyStatus::Malformed);
  EXPECT_EQ(Store.apply(assignmentOf(0, 0, 24)), ApplyStatus::Malformed);
  EXPECT_EQ(Store.apply(assignmentOf(32, 0, 0)), ApplyStatus::Malformed);
  // Positions on the wire are shorts, so the top of the area has to be one.
  EXPECT_EQ(Store.apply(assignmentOf(32, 32700, 100)), ApplyStatus::Malformed);
  EXPECT_EQ(Store.pin(), nullptr);
}

TEST(WorldStore, RejectsChunksThatBreakTheContract) {
  WorldStore Store;
  ASSERT_EQ(Store.apply(flatWorld().assignment()), ApplyStatus::Applied);
  const auto Stone4 = fbs::CellRun(4, fbs::PathType::BLOCKED, 16);
  const auto Air20 = fbs::CellRun(20, fbs::PathType::OPEN, 0);

  // Columns that fall short of, or overrun, the height.
  EXPECT_EQ(Store.apply(uniformChunk(1, {Stone4})), ApplyStatus::Malformed);
  EXPECT_EQ(Store.apply(uniformChunk(1, {Stone4, Air20, Stone4})),
            ApplyStatus::Malformed);
  // One column short.
  EXPECT_EQ(Store.apply(uniformChunk(1, {Stone4, Air20}, ColumnsPerChunk - 1)),
            ApplyStatus::Malformed);
  // A zero-length run.
  EXPECT_EQ(Store.apply(uniformChunk(
                1, {Stone4, fbs::CellRun(0, fbs::PathType::WATER, 0), Air20})),
            ApplyStatus::Malformed);
  // A type only the node may derive.
  EXPECT_EQ(Store.apply(uniformChunk(
                1, {fbs::CellRun(4, fbs::PathType::WALKABLE, 0), Air20})),
            ApplyStatus::Malformed);
  // A type no ordinal names.
  EXPECT_EQ(
      Store.apply(uniformChunk(
          1, {fbs::CellRun(4, static_cast<fbs::PathType>(200), 0), Air20})),
      ApplyStatus::Malformed);

  EXPECT_EQ(Store.lastSequence(), 0u);
  EXPECT_EQ(Store.apply(uniformChunk(1, {Stone4, Air20})),
            ApplyStatus::Applied);
}

TEST(WorldStore, RejectsAChunkOutsideTheArea) {
  WorldStore Store;
  const DenseWorld World = flatWorld();
  ASSERT_EQ(Store.apply(World.assignment()), ApplyStatus::Applied);
  EXPECT_EQ(Store.apply(test::clearedChunk(1, 2, 0)), ApplyStatus::Malformed);
  EXPECT_EQ(Store.apply(test::clearedChunk(1, 0, -1)), ApplyStatus::Malformed);
}

TEST(WorldStore, RejectsADeltaThatBreaksTheContract) {
  WorldStore Store;
  load(Store, flatWorld());
  const std::vector<CellEdit> Outside = {{1, 1, 1, Water}, {32, 1, 1, Water}};
  EXPECT_EQ(Store.apply(test::delta(5, Outside)), ApplyStatus::Malformed);
  const std::vector<CellEdit> AboveTheTop = {{1, 24, 1, Water}};
  EXPECT_EQ(Store.apply(test::delta(5, AboveTheTop)), ApplyStatus::Malformed);
  const std::vector<CellEdit> Derived = {
      {1, 4, 1, Cell{PathType::WATER_BORDER, 0}}};
  EXPECT_EQ(Store.apply(test::delta(5, Derived)), ApplyStatus::Malformed);
  // Nothing from a rejected frame is applied, even the valid part.
  EXPECT_EQ(Store.pin()->at(1, 1, 1), Stone);
  EXPECT_EQ(Store.lastSequence(), 4u);
}
