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

  const fbs::CellDelta Cells[] = {
      fbs::CellDelta(fbs::Vec3i(1, 2, 3), fbs::PathType::WALKABLE),
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
  EXPECT_EQ(Read->cells()->Get(0)->node_type(), fbs::PathType::WALKABLE);
  EXPECT_EQ(Read->cells()->Get(0)->pos().x(), 1);
  EXPECT_EQ(Read->cells()->Get(1)->pos().z(), 6);
}
