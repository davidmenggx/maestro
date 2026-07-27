#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include "control_generated.h"

namespace fbs = maestro::fbs;

TEST(Schema, PingRoundTrip) {
  flatbuffers::FlatBufferBuilder Fbb;
  const auto PingOffset =
      fbs::CreatePing(Fbb, /*sequence=*/42, /*send_micros=*/1234567);
  Fbb.Finish(PingOffset);

  const auto *Got = flatbuffers::GetRoot<fbs::Ping>(Fbb.GetBufferPointer());
  EXPECT_EQ(Got->sequence(), 42u);
  EXPECT_EQ(Got->send_micros(), 1234567u);
}

TEST(Schema, WorldFrameCarriesWorldDelta) {
  flatbuffers::FlatBufferBuilder Fbb;

  // Cells carry per-block types only, so no `WALKABLE` or `*_IN_NEIGHBOR` here.
  const fbs::CellDelta Cells[] = {
      fbs::CellDelta(fbs::Vec3i(1, 2, 3), fbs::PathType::OPEN),
      fbs::CellDelta(fbs::Vec3i(4, 5, 6), fbs::PathType::WATER),
  };
  const auto CellsVec = Fbb.CreateVectorOfStructs(Cells, 2);

  fbs::WorldDeltaBuilder Builder(Fbb);
  Builder.add_sequence(100);
  Builder.add_cells(CellsVec);
  const auto Delta = Builder.Finish();

  const auto Frame =
      fbs::CreateWorldFrame(Fbb, fbs::WorldMsg::WorldDelta, Delta.Union());
  Fbb.Finish(Frame);

  const auto *Got = fbs::GetWorldFrame(Fbb.GetBufferPointer());
  ASSERT_EQ(Got->body_type(), fbs::WorldMsg::WorldDelta);

  const auto *Read = Got->body_as_WorldDelta();
  ASSERT_NE(Read, nullptr);
  EXPECT_EQ(Read->sequence(), 100u);

  ASSERT_NE(Read->cells(), nullptr);
  ASSERT_EQ(Read->cells()->size(), 2u);
  EXPECT_EQ(Read->cells()->Get(0)->node_type(), fbs::PathType::OPEN);
  EXPECT_EQ(Read->cells()->Get(0)->pos().x(), 1);
  EXPECT_EQ(Read->cells()->Get(1)->pos().z(), 6);
}

TEST(Schema, WorldFrameCarriesRunLengthEncodedSnapshot) {
  constexpr int Width = 2;
  constexpr int Height = 384;

  flatbuffers::FlatBufferBuilder Fbb;

  // A 2x2 region of superflat columns, indexed `x * Width + z`. The column at
  // (1, 0) has a water cell on the surface, so run counts differ per column and
  // the reader cannot assume a fixed stride into `runs`.
  const uint16_t RunCounts[] = {2, 2, 3, 2};
  const auto RunCountsVec = Fbb.CreateVector(RunCounts, 4);

  const fbs::CellRun Runs[] = {
      fbs::CellRun(4, fbs::PathType::BLOCKED),
      fbs::CellRun(380, fbs::PathType::OPEN),

      fbs::CellRun(4, fbs::PathType::BLOCKED),
      fbs::CellRun(380, fbs::PathType::OPEN),

      fbs::CellRun(4, fbs::PathType::BLOCKED),
      fbs::CellRun(1, fbs::PathType::WATER),
      fbs::CellRun(379, fbs::PathType::OPEN),

      fbs::CellRun(4, fbs::PathType::BLOCKED),
      fbs::CellRun(380, fbs::PathType::OPEN),
  };
  const auto RunsVec = Fbb.CreateVectorOfStructs(Runs, 9);

  fbs::WorldSnapshotBuilder SnapshotBuilder(Fbb);
  SnapshotBuilder.add_sequence(7);
  SnapshotBuilder.add_base_y(-64);
  SnapshotBuilder.add_run_counts(RunCountsVec);
  SnapshotBuilder.add_runs(RunsVec);
  const auto Snapshot = SnapshotBuilder.Finish();

  const auto Frame = fbs::CreateWorldFrame(Fbb, fbs::WorldMsg::WorldSnapshot,
                                           Snapshot.Union());
  Fbb.Finish(Frame);

  const auto *Got = fbs::GetWorldFrame(Fbb.GetBufferPointer());
  ASSERT_EQ(Got->body_type(), fbs::WorldMsg::WorldSnapshot);

  const auto *Read = Got->body_as_WorldSnapshot();
  ASSERT_NE(Read, nullptr);
  EXPECT_EQ(Read->sequence(), 7u);
  EXPECT_EQ(Read->base_y(), -64);

  ASSERT_NE(Read->run_counts(), nullptr);
  ASSERT_NE(Read->runs(), nullptr);
  ASSERT_EQ(Read->run_counts()->size(), Width * Width);
  ASSERT_EQ(Read->runs()->size(), 9u);

  // Walk the prefix sum the way a node would, checking every column tiles the
  // region height and that the offsets land on the right runs.
  unsigned Offset = 0;
  for (unsigned Column = 0; Column < Read->run_counts()->size(); ++Column) {
    const unsigned Count = Read->run_counts()->Get(Column);
    int Total = 0;
    for (unsigned I = 0; I < Count; ++I) {
      Total += Read->runs()->Get(Offset + I)->length();
    }
    EXPECT_EQ(Total, Height) << "column " << Column;
    Offset += Count;
  }
  EXPECT_EQ(Offset, Read->runs()->size());

  // Column (1, 0) is the odd one out: index 2, three runs, water in the middle.
  const unsigned WaterColumn = 1 * Width + 0;
  unsigned WaterOffset = 0;
  for (unsigned Column = 0; Column < WaterColumn; ++Column) {
    WaterOffset += Read->run_counts()->Get(Column);
  }
  EXPECT_EQ(Read->run_counts()->Get(WaterColumn), 3u);
  EXPECT_EQ(Read->runs()->Get(WaterOffset + 1)->node_type(),
            fbs::PathType::WATER);
  EXPECT_EQ(Read->runs()->Get(WaterOffset + 1)->length(), 1u);
}
