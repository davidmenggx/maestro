#include <cstdint>
#include <iterator>
#include <vector>

#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include "control_generated.h"

namespace fbs = maestro::fbs;

TEST(Schema, WorldFrameCarriesWorldDelta) {
  flatbuffers::FlatBufferBuilder Fbb;

  // Cells carry per-block types only, so no `WALKABLE` or `*_IN_NEIGHBOR` here.
  const fbs::CellDelta Cells[] = {
      fbs::CellDelta(fbs::Vec3s(1, 2, 3), fbs::PathType::OPEN, /*floor=*/0),
      fbs::CellDelta(fbs::Vec3s(4, 5, 6), fbs::PathType::BLOCKED,
                     /*floor=*/8),
  };
  const auto CellsVec = Fbb.CreateVectorOfStructs(Cells, std::size(Cells));

  fbs::WorldDeltaBuilder Builder(Fbb);
  Builder.add_sequence(100);
  Builder.add_cells(CellsVec);
  const auto Delta = Builder.Finish();

  const auto FrameOffset =
      fbs::CreateWorldFrame(Fbb, fbs::WorldMsg::WorldDelta, Delta.Union());
  Fbb.Finish(FrameOffset);

  const auto *Frame = fbs::GetWorldFrame(Fbb.GetBufferPointer());
  ASSERT_EQ(Frame->body_type(), fbs::WorldMsg::WorldDelta);

  const auto *Decoded = Frame->body_as_WorldDelta();
  ASSERT_NE(Decoded, nullptr);
  EXPECT_EQ(Decoded->sequence(), 100u);

  ASSERT_NE(Decoded->cells(), nullptr);
  ASSERT_EQ(Decoded->cells()->size(), std::size(Cells));
  EXPECT_EQ(Decoded->cells()->Get(0)->node_type(), fbs::PathType::OPEN);
  EXPECT_EQ(Decoded->cells()->Get(0)->pos().x(), 1);
  EXPECT_EQ(Decoded->cells()->Get(1)->pos().z(), 6);
  EXPECT_EQ(Decoded->cells()->Get(1)->floor(), 8u);
}

TEST(Schema, WorldFrameCarriesRunLengthEncodedChunk) {
  constexpr int Width = 16;
  constexpr int Columns = Width * Width;
  constexpr int Height = 384;

  // Every column is superflat except two: column 1 is topped by a slab, whose
  // run shares a type with the run below it and differs only in `floor`, and
  // column 2 has water on the surface. Run counts therefore differ per column
  // and a reader cannot assume a fixed stride into `runs`.
  std::vector<std::uint16_t> RunCounts(Columns, 2);
  RunCounts[1] = 3;
  RunCounts[2] = 3;

  std::vector<fbs::CellRun> Runs;
  for (int Column = 0; Column < Columns; ++Column) {
    if (Column == 1) {
      Runs.emplace_back(3, fbs::PathType::BLOCKED, 16);
      Runs.emplace_back(1, fbs::PathType::BLOCKED, 8);
      Runs.emplace_back(Height - 4, fbs::PathType::OPEN, 0);
    } else if (Column == 2) {
      Runs.emplace_back(4, fbs::PathType::BLOCKED, 16);
      Runs.emplace_back(1, fbs::PathType::WATER, 0);
      Runs.emplace_back(Height - 5, fbs::PathType::OPEN, 0);
    } else {
      Runs.emplace_back(4, fbs::PathType::BLOCKED, 16);
      Runs.emplace_back(Height - 4, fbs::PathType::OPEN, 0);
    }
  }

  flatbuffers::FlatBufferBuilder Fbb;
  const auto RunCountsVec = Fbb.CreateVector(RunCounts);
  const auto RunsVec = Fbb.CreateVectorOfStructs(Runs.data(), Runs.size());
  const fbs::Vec2i ChunkXz(3, -2);

  fbs::ChunkSnapshotBuilder SnapshotBuilder(Fbb);
  SnapshotBuilder.add_sequence(7);
  SnapshotBuilder.add_chunk_xz(&ChunkXz);
  SnapshotBuilder.add_run_counts(RunCountsVec);
  SnapshotBuilder.add_runs(RunsVec);
  const auto Snapshot = SnapshotBuilder.Finish();

  const auto FrameOffset = fbs::CreateWorldFrame(
      Fbb, fbs::WorldMsg::ChunkSnapshot, Snapshot.Union());
  Fbb.Finish(FrameOffset);

  const auto *Frame = fbs::GetWorldFrame(Fbb.GetBufferPointer());
  ASSERT_EQ(Frame->body_type(), fbs::WorldMsg::ChunkSnapshot);

  const auto *Decoded = Frame->body_as_ChunkSnapshot();
  ASSERT_NE(Decoded, nullptr);
  EXPECT_EQ(Decoded->sequence(), 7u);
  EXPECT_EQ(Decoded->chunk_xz()->x(), 3);
  EXPECT_EQ(Decoded->chunk_xz()->z(), -2);

  ASSERT_NE(Decoded->run_counts(), nullptr);
  ASSERT_NE(Decoded->runs(), nullptr);
  ASSERT_EQ(Decoded->run_counts()->size(), Columns);
  ASSERT_EQ(Decoded->runs()->size(), Runs.size());

  // Walk the prefix sum the way a node would, checking every column tiles the
  // region height and that the offsets land on the right runs.
  unsigned Offset = 0;
  for (unsigned Column = 0; Column < Decoded->run_counts()->size(); ++Column) {
    const unsigned Count = Decoded->run_counts()->Get(Column);
    int Total = 0;
    for (unsigned I = 0; I < Count; ++I)
      Total += Decoded->runs()->Get(Offset + I)->length();
    EXPECT_EQ(Total, Height) << "column " << Column;
    Offset += Count;
  }
  EXPECT_EQ(Offset, Decoded->runs()->size());

  // The slab run has the same type as the run below it and a different floor.
  const unsigned SlabOffset = Decoded->run_counts()->Get(0);
  EXPECT_EQ(Decoded->runs()->Get(SlabOffset)->node_type(),
            Decoded->runs()->Get(SlabOffset + 1)->node_type());
  EXPECT_EQ(Decoded->runs()->Get(SlabOffset)->floor(), 16u);
  EXPECT_EQ(Decoded->runs()->Get(SlabOffset + 1)->floor(), 8u);
}

TEST(Schema, AChunkWithNoColumnsIsNotResident) {
  flatbuffers::FlatBufferBuilder Fbb;
  const fbs::Vec2i ChunkXz(0, 0);

  fbs::ChunkSnapshotBuilder SnapshotBuilder(Fbb);
  SnapshotBuilder.add_sequence(9);
  SnapshotBuilder.add_chunk_xz(&ChunkXz);
  const auto Snapshot = SnapshotBuilder.Finish();
  Fbb.Finish(fbs::CreateWorldFrame(Fbb, fbs::WorldMsg::ChunkSnapshot,
                                   Snapshot.Union()));

  const auto *Decoded =
      fbs::GetWorldFrame(Fbb.GetBufferPointer())->body_as_ChunkSnapshot();
  ASSERT_NE(Decoded, nullptr);
  // Absent rather than empty: the reader has to tell "no columns" from a
  // zero-length vector it could otherwise index into.
  EXPECT_EQ(Decoded->run_counts(), nullptr);
  EXPECT_EQ(Decoded->runs(), nullptr);
}
