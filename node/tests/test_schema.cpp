#include <iterator>

#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include "control_generated.h"

namespace fbs = maestro::fbs;

TEST(Schema, PingRoundTrip) {
  flatbuffers::FlatBufferBuilder Fbb;
  const auto PingOffset =
      fbs::CreatePing(Fbb, /*sequence=*/42, /*send_micros=*/1234567);
  Fbb.Finish(PingOffset);

  const auto *Ping = flatbuffers::GetRoot<fbs::Ping>(Fbb.GetBufferPointer());
  EXPECT_EQ(Ping->sequence(), 42u);
  EXPECT_EQ(Ping->send_micros(), 1234567u);
}

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

TEST(Schema, WorldFrameCarriesRunLengthEncodedSnapshot) {
  constexpr int Width = 2;
  constexpr int Columns = Width * Width;
  constexpr int Height = 384;

  flatbuffers::FlatBufferBuilder Fbb;

  // A 2x2 region of superflat columns, indexed `x * Width + z`. Column (0, 1)
  // is topped by a slab, which is the same type as the blocks below it and
  // still has to be its own run. Column (1, 0) has a water cell on the surface.
  // Run counts therefore differ per column and the reader cannot assume a
  // fixed stride into `runs`.
  const uint16_t RunCounts[] = {2, 3, 3, 2};
  static_assert(std::size(RunCounts) == Columns);
  const auto RunCountsVec = Fbb.CreateVector(RunCounts, std::size(RunCounts));

  const fbs::CellRun Runs[] = {
      fbs::CellRun(4, fbs::PathType::BLOCKED, 16),
      fbs::CellRun(380, fbs::PathType::OPEN, 0),

      fbs::CellRun(3, fbs::PathType::BLOCKED, 16),
      fbs::CellRun(1, fbs::PathType::BLOCKED, 8),
      fbs::CellRun(380, fbs::PathType::OPEN, 0),

      fbs::CellRun(4, fbs::PathType::BLOCKED, 16),
      fbs::CellRun(1, fbs::PathType::WATER, 0),
      fbs::CellRun(379, fbs::PathType::OPEN, 0),

      fbs::CellRun(4, fbs::PathType::BLOCKED, 16),
      fbs::CellRun(380, fbs::PathType::OPEN, 0),
  };
  const auto RunsVec = Fbb.CreateVectorOfStructs(Runs, std::size(Runs));

  fbs::WorldSnapshotBuilder SnapshotBuilder(Fbb);
  SnapshotBuilder.add_sequence(7);
  SnapshotBuilder.add_base_y(-64);
  SnapshotBuilder.add_run_counts(RunCountsVec);
  SnapshotBuilder.add_runs(RunsVec);
  const auto Snapshot = SnapshotBuilder.Finish();

  const auto FrameOffset = fbs::CreateWorldFrame(
      Fbb, fbs::WorldMsg::WorldSnapshot, Snapshot.Union());
  Fbb.Finish(FrameOffset);

  const auto *Frame = fbs::GetWorldFrame(Fbb.GetBufferPointer());
  ASSERT_EQ(Frame->body_type(), fbs::WorldMsg::WorldSnapshot);

  const auto *Decoded = Frame->body_as_WorldSnapshot();
  ASSERT_NE(Decoded, nullptr);
  EXPECT_EQ(Decoded->sequence(), 7u);
  EXPECT_EQ(Decoded->base_y(), -64);

  ASSERT_NE(Decoded->run_counts(), nullptr);
  ASSERT_NE(Decoded->runs(), nullptr);
  ASSERT_EQ(Decoded->run_counts()->size(), Columns);
  ASSERT_EQ(Decoded->runs()->size(), std::size(Runs));

  // Walk the prefix sum the way a node would, checking every column tiles the
  // region height and that the offsets land on the right runs.
  unsigned Offset = 0;
  for (unsigned Column = 0; Column < Decoded->run_counts()->size(); ++Column) {
    const unsigned Count = Decoded->run_counts()->Get(Column);
    int Total = 0;
    for (unsigned I = 0; I < Count; ++I) {
      Total += Decoded->runs()->Get(Offset + I)->length();
    }
    EXPECT_EQ(Total, Height) << "column " << Column;
    Offset += Count;
  }
  EXPECT_EQ(Offset, Decoded->runs()->size());

  // Column (0, 1) is index 1: the slab run has the same type as the run below
  // and a different floor.
  const unsigned SlabColumn = 0 * Width + 1;
  unsigned SlabOffset = Decoded->run_counts()->Get(0);
  EXPECT_EQ(Decoded->run_counts()->Get(SlabColumn), 3u);
  EXPECT_EQ(Decoded->runs()->Get(SlabOffset)->node_type(),
            Decoded->runs()->Get(SlabOffset + 1)->node_type());
  EXPECT_EQ(Decoded->runs()->Get(SlabOffset)->floor(), 16u);
  EXPECT_EQ(Decoded->runs()->Get(SlabOffset + 1)->floor(), 8u);

  // Column (1, 0) is index 2: water in the middle.
  const unsigned WaterColumn = 1 * Width + 0;
  unsigned WaterOffset = 0;
  for (unsigned Column = 0; Column < WaterColumn; ++Column) {
    WaterOffset += Decoded->run_counts()->Get(Column);
  }
  EXPECT_EQ(Decoded->run_counts()->Get(WaterColumn), 3u);
  EXPECT_EQ(Decoded->runs()->Get(WaterOffset + 1)->node_type(),
            fbs::PathType::WATER);
  EXPECT_EQ(Decoded->runs()->Get(WaterOffset + 1)->length(), 1u);
}
