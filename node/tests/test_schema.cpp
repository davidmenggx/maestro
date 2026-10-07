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

TEST(Schema, PathTypeOrdinalsArePinned) {
  // The server sends vanilla ordinals as is, so the first, the last, and one
  // from the middle are pinned against the vanilla enum.
  static_assert(static_cast<int>(fbs::PathType::BLOCKED) == 0);
  static_assert(static_cast<int>(fbs::PathType::WATER) == 9);
  static_assert(static_cast<int>(fbs::PathType::BIG_MOBS_CLOSE_TO_DANGER) ==
                26);
  EXPECT_EQ(static_cast<int>(fbs::PathType::MAX), 26);
}

TEST(Schema, RequestFrameCarriesABatch) {
  flatbuffers::FlatBufferBuilder Fbb;
  const fbs::Vec3s Start(1, 64, -1);
  const fbs::Vec3s Target(-30, 70, 12);
  const auto Request = fbs::CreatePathRequest(
      Fbb, /*request_id=*/42, /*class_id=*/3, &Start, &Target,
      /*reach_range=*/1, /*max_path_length=*/32.5f,
      /*max_visited_nodes=*/560, /*max_fall_distance=*/16);
  const auto Batch =
      fbs::CreatePathRequestBatch(Fbb, Fbb.CreateVector(std::vector{Request}));
  Fbb.Finish(fbs::CreateRequestFrame(Fbb, fbs::RequestMsg::PathRequestBatch,
                                     Batch.Union()));

  flatbuffers::Verifier Verifier(Fbb.GetBufferPointer(), Fbb.GetSize());
  ASSERT_TRUE(Verifier.VerifyBuffer<fbs::RequestFrame>(nullptr));

  const auto *Decoded =
      flatbuffers::GetRoot<fbs::RequestFrame>(Fbb.GetBufferPointer())
          ->body_as_PathRequestBatch();
  ASSERT_NE(Decoded, nullptr);
  ASSERT_EQ(Decoded->requests()->size(), 1u);
  const auto *Req = Decoded->requests()->Get(0);
  EXPECT_EQ(Req->request_id(), 42u);
  EXPECT_EQ(Req->class_id(), 3u);
  EXPECT_EQ(Req->start()->y(), 64);
  EXPECT_EQ(Req->target()->x(), -30);
  EXPECT_EQ(Req->reach_range(), 1u);
  EXPECT_FLOAT_EQ(Req->max_path_length(), 32.5f);
  EXPECT_EQ(Req->max_visited_nodes(), 560u);
  EXPECT_EQ(Req->max_fall_distance(), 16u);
}

TEST(Schema, ResponseFrameCarriesResolvedNodes) {
  flatbuffers::FlatBufferBuilder Fbb;
  // Path nodes carry resolved types, which a cell never can.
  const fbs::PathNode Nodes[] = {
      fbs::PathNode(fbs::Vec3s(0, 64, 0), fbs::PathType::WALKABLE),
      fbs::PathNode(fbs::Vec3s(1, 64, 0), fbs::PathType::WALKABLE_DOOR),
  };
  const auto NodesVec = Fbb.CreateVectorOfStructs(Nodes, std::size(Nodes));
  const auto Response = fbs::CreatePathResponse(
      Fbb, /*request_id=*/7, /*reached=*/true, NodesVec, /*cost=*/2.f,
      /*world_sequence=*/99);
  Fbb.Finish(fbs::CreateResponseFrame(Fbb, fbs::ResponseMsg::PathResponse,
                                      Response.Union()));

  const auto *Decoded =
      flatbuffers::GetRoot<fbs::ResponseFrame>(Fbb.GetBufferPointer())
          ->body_as_PathResponse();
  ASSERT_NE(Decoded, nullptr);
  EXPECT_EQ(Decoded->request_id(), 7u);
  EXPECT_TRUE(Decoded->reached());
  EXPECT_FLOAT_EQ(Decoded->cost(), 2.f);
  EXPECT_EQ(Decoded->world_sequence(), 99u);
  ASSERT_EQ(Decoded->nodes()->size(), std::size(Nodes));
  EXPECT_EQ(Decoded->nodes()->Get(1)->type(), fbs::PathType::WALKABLE_DOOR);
  EXPECT_EQ(Decoded->nodes()->Get(1)->pos().x(), 1);
}

TEST(Schema, ControlFrameCarriesAFullMalusTable) {
  flatbuffers::FlatBufferBuilder Fbb;
  std::vector<float> Malus(static_cast<int>(fbs::PathType::MAX) + 1, 0.f);
  Malus[static_cast<int>(fbs::PathType::WATER)] = 8.f;
  Malus[static_cast<int>(fbs::PathType::LAVA)] = -1.f;
  const auto Profile = fbs::CreateMobClass(
      Fbb, /*class_id=*/5, /*width=*/0.6f, /*height=*/1.95f,
      Fbb.CreateVector(Malus), /*max_up_step=*/0.6f, /*can_pass_doors=*/true,
      /*can_open_doors=*/false, /*can_float=*/true,
      /*can_walk_over_fences=*/false);
  Fbb.Finish(
      fbs::CreateControlFrame(Fbb, fbs::ControlMsg::MobClass, Profile.Union()));

  const auto *Decoded =
      flatbuffers::GetRoot<fbs::ControlFrame>(Fbb.GetBufferPointer())
          ->body_as_MobClass();
  ASSERT_NE(Decoded, nullptr);
  EXPECT_EQ(Decoded->class_id(), 5u);
  EXPECT_FLOAT_EQ(Decoded->height(), 1.95f);
  EXPECT_FLOAT_EQ(Decoded->max_up_step(), 0.6f);
  EXPECT_TRUE(Decoded->can_pass_doors());
  EXPECT_FALSE(Decoded->can_open_doors());
  EXPECT_TRUE(Decoded->can_float());
  EXPECT_FALSE(Decoded->can_walk_over_fences());
  ASSERT_EQ(Decoded->malus()->size(), Malus.size());
  EXPECT_FLOAT_EQ(Decoded->malus()->Get(static_cast<int>(fbs::PathType::WATER)),
                  8.f);
  EXPECT_FLOAT_EQ(Decoded->malus()->Get(static_cast<int>(fbs::PathType::LAVA)),
                  -1.f);
}

TEST(Schema, HelloDefaultsToTheVanillaPathfinder) {
  flatbuffers::FlatBufferBuilder Fbb;
  fbs::HelloBuilder Builder(Fbb);
  Builder.add_run(11);
  Builder.add_region_id(4);
  Builder.add_last_sequence(250);
  const auto Hello = Builder.Finish();
  Fbb.Finish(fbs::CreateControlReplyFrame(Fbb, fbs::ControlReplyMsg::Hello,
                                          Hello.Union()));

  const auto *Decoded =
      flatbuffers::GetRoot<fbs::ControlReplyFrame>(Fbb.GetBufferPointer())
          ->body_as_Hello();
  ASSERT_NE(Decoded, nullptr);
  EXPECT_EQ(Decoded->run(), 11u);
  EXPECT_EQ(Decoded->region_id(), 4u);
  EXPECT_EQ(Decoded->last_sequence(), 250u);
  EXPECT_EQ(Decoded->pathfinder(), fbs::Pathfinder::VANILLA);
}

TEST(Schema, HeartbeatCarriesQueueDepth) {
  flatbuffers::FlatBufferBuilder Fbb;
  const auto Beat = fbs::CreateHeartbeat(Fbb, /*queue_depth=*/17);
  Fbb.Finish(fbs::CreateControlReplyFrame(Fbb, fbs::ControlReplyMsg::Heartbeat,
                                          Beat.Union()));

  const auto *Frame =
      flatbuffers::GetRoot<fbs::ControlReplyFrame>(Fbb.GetBufferPointer());
  EXPECT_EQ(Frame->body_as_Hello(), nullptr);
  ASSERT_NE(Frame->body_as_Heartbeat(), nullptr);
  EXPECT_EQ(Frame->body_as_Heartbeat()->queue_depth(), 17u);
}

TEST(Schema, WorldFrameCarriesRegionAssignment) {
  flatbuffers::FlatBufferBuilder Fbb;
  const fbs::Vec2i MinXz(-256, 512);
  const auto Assignment = fbs::CreateRegionAssignment(
      Fbb, /*run=*/11, /*region_id=*/4, &MinXz, /*size=*/256, /*min_y=*/-64,
      /*height=*/384, /*resume=*/true);
  Fbb.Finish(fbs::CreateWorldFrame(Fbb, fbs::WorldMsg::RegionAssignment,
                                   Assignment.Union()));

  const auto *Decoded =
      fbs::GetWorldFrame(Fbb.GetBufferPointer())->body_as_RegionAssignment();
  ASSERT_NE(Decoded, nullptr);
  EXPECT_EQ(Decoded->run(), 11u);
  EXPECT_EQ(Decoded->region_id(), 4u);
  EXPECT_EQ(Decoded->min_xz()->x(), -256);
  EXPECT_EQ(Decoded->min_xz()->z(), 512);
  EXPECT_EQ(Decoded->size(), 256u);
  EXPECT_EQ(Decoded->min_y(), -64);
  EXPECT_EQ(Decoded->height(), 384u);
  EXPECT_TRUE(Decoded->resume());
}
