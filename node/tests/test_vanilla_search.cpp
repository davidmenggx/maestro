#include "maestro/search/vanilla_search.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include "control_generated.h"
#include "maestro/search/walk_node_evaluator.hpp"
#include "test_world.hpp"

using namespace maestro;
using namespace maestro::search;
using namespace maestro::test;
using world::PathType;

namespace {

/// Stone at y = 0 everywhere, so mobs stand at y = 1.
DenseWorld flat() {
  DenseWorld World(32, 0, 16);
  World.fill(0, 0, 0, 31, 0, 31, Stone);
  return World;
}

Request walk(BlockPos From, BlockPos To) {
  Request Call;
  Call.Start = From;
  Call.Target = To;
  Call.MaxPathLength = 32.0F;
  Call.MaxVisitedNodes = 512;
  return Call;
}

Result find(const DenseWorld &World, const Profile &Mob, const Request &Call) {
  const auto Area = World.load();
  VanillaSearch Search;
  Result Out;
  Search.find(*Area, Mob, Call, Out);
  return Out;
}

std::vector<BlockPos> positions(const Result &Path) {
  std::vector<BlockPos> Out;
  for (const PathPoint &Point : Path.Nodes)
    Out.push_back(Point.Pos);
  return Out;
}

/// `Mth.sqrt(2.0F)`.
const float Diagonal = static_cast<float>(std::sqrt(2.0));

} // namespace

TEST(VanillaSearch, WalksAStraightLine) {
  const Result Path = find(flat(), Profile{}, walk({2, 1, 2}, {8, 1, 2}));
  EXPECT_TRUE(Path.Reached);
  const std::vector<BlockPos> Expected = {{2, 1, 2}, {3, 1, 2}, {4, 1, 2},
                                          {5, 1, 2}, {6, 1, 2}, {7, 1, 2},
                                          {8, 1, 2}};
  EXPECT_EQ(positions(Path), Expected);
  for (const PathPoint &Point : Path.Nodes)
    EXPECT_EQ(Point.Type, PathType::WALKABLE);
  EXPECT_EQ(Path.Cost, 6.0F);
}

TEST(VanillaSearch, CutsCornersOnOpenGround) {
  const Result Path = find(flat(), Profile{}, walk({2, 1, 2}, {5, 1, 5}));
  EXPECT_TRUE(Path.Reached);
  const std::vector<BlockPos> Expected = {
      {2, 1, 2}, {3, 1, 3}, {4, 1, 4}, {5, 1, 5}};
  EXPECT_EQ(positions(Path), Expected);
  float Cost = 0.0F;
  for (int I = 0; I < 3; ++I)
    Cost = Cost + Diagonal + 0.0F;
  EXPECT_EQ(Path.Cost, Cost);
}

TEST(VanillaSearch, StepsUpOneBlock) {
  DenseWorld World = flat();
  World.fill(5, 1, 0, 31, 1, 31, Stone);
  const Result Path = find(World, Profile{}, walk({2, 1, 2}, {8, 2, 2}));
  EXPECT_TRUE(Path.Reached);
  const std::vector<BlockPos> Expected = {{2, 1, 2}, {3, 1, 2}, {4, 1, 2},
                                          {5, 2, 2}, {6, 2, 2}, {7, 2, 2},
                                          {8, 2, 2}};
  EXPECT_EQ(positions(Path), Expected);
  const float Cost = 0.0F + 1.0F + 1.0F + Diagonal + 1.0F + 1.0F + 1.0F;
  EXPECT_EQ(Path.Cost, Cost);
}

TEST(VanillaSearch, StopsAtTheNearestNodeBeforeAWallItCannotClimb) {
  DenseWorld World = flat();
  World.fill(5, 1, 0, 5, 2, 31, Stone);
  const Result Path = find(World, Profile{}, walk({2, 1, 2}, {8, 1, 2}));
  EXPECT_FALSE(Path.Reached);
  const std::vector<BlockPos> Expected = {{2, 1, 2}, {3, 1, 2}, {4, 1, 2}};
  EXPECT_EQ(positions(Path), Expected);
  EXPECT_EQ(Path.Cost, 2.0F);
}

TEST(VanillaSearch, AFenceCannotBeJumped) {
  DenseWorld World = flat();
  World.fill(5, 1, 0, 5, 1, 31, Fence);
  const Result Path = find(World, Profile{}, walk({2, 1, 2}, {8, 1, 2}));
  EXPECT_FALSE(Path.Reached);
  EXPECT_EQ(Path.Nodes.back().Pos, (BlockPos{4, 1, 2}));
}

TEST(VanillaSearch, FallsNoFurtherThanTheMobAccepts) {
  // A plateau standing four blocks above the ground beyond it.
  DenseWorld World = flat();
  World.fill(0, 1, 0, 4, 4, 31, Stone);
  Request Call = walk({2, 5, 2}, {8, 1, 2});

  Call.MaxFallDistance = 3;
  const Result Calm = find(World, Profile{}, Call);
  EXPECT_FALSE(Calm.Reached);
  EXPECT_EQ(Calm.Nodes.back().Pos, (BlockPos{4, 5, 2}));
  for (const PathPoint &Point : Calm.Nodes)
    EXPECT_EQ(Point.Pos.Y, 5);

  Call.MaxFallDistance = 4;
  const Result Hunting = find(World, Profile{}, Call);
  EXPECT_TRUE(Hunting.Reached);
  EXPECT_EQ(Hunting.Nodes.back().Pos, (BlockPos{8, 1, 2}));
}

TEST(VanillaSearch, ChargesTheMalusOfEveryNodeAfterTheStart) {
  Profile Mob;
  Mob.Malus[static_cast<int>(PathType::WALKABLE)] = 2.0F;
  const Result Path = find(flat(), Mob, walk({2, 1, 2}, {8, 1, 2}));
  EXPECT_TRUE(Path.Reached);
  EXPECT_EQ(Path.Nodes.size(), 7u);
  EXPECT_EQ(Path.Cost, 18.0F);
}

TEST(VanillaSearch, ArrivesWithinReachRange) {
  Request Call = walk({2, 1, 2}, {8, 1, 2});
  Call.ReachRange = 2;
  const Result Path = find(flat(), Profile{}, Call);
  EXPECT_TRUE(Path.Reached);
  EXPECT_EQ(Path.Nodes.back().Pos, (BlockPos{6, 1, 2}));
  EXPECT_EQ(Path.Cost, 4.0F);
}

TEST(VanillaSearch, StopsWhenTheVisitBudgetRunsOut) {
  Request Call = walk({2, 1, 2}, {20, 1, 2});
  Call.MaxVisitedNodes = 3;
  const Result Path = find(flat(), Profile{}, Call);
  EXPECT_FALSE(Path.Reached);
  // Two nodes are expanded, and the path ends at the nearest node found.
  const std::vector<BlockPos> Expected = {{2, 1, 2}, {3, 1, 2}, {4, 1, 2}};
  EXPECT_EQ(positions(Path), Expected);
}

TEST(VanillaSearch, ABudgetOfOneVisitsNothingEvenAtTheTarget) {
  Request Call = walk({2, 1, 2}, {2, 1, 2});
  Call.MaxVisitedNodes = 1;
  const Result Path = find(flat(), Profile{}, Call);
  EXPECT_FALSE(Path.Reached);
  ASSERT_EQ(Path.Nodes.size(), 1u);
  EXPECT_EQ(Path.Nodes[0].Pos, (BlockPos{2, 1, 2}));
}

TEST(VanillaSearch, StartingAtTheTargetArrivesAtOnce) {
  const Result Path = find(flat(), Profile{}, walk({2, 1, 2}, {2, 1, 2}));
  EXPECT_TRUE(Path.Reached);
  ASSERT_EQ(Path.Nodes.size(), 1u);
  EXPECT_EQ(Path.Cost, 0.0F);
}

TEST(VanillaSearch, StaysWithinThePathLength) {
  Request Call = walk({2, 1, 2}, {8, 1, 2});
  Call.MaxPathLength = 3.0F;
  const Result Path = find(flat(), Profile{}, Call);
  EXPECT_FALSE(Path.Reached);
  const std::vector<BlockPos> Expected = {{2, 1, 2}, {3, 1, 2}, {4, 1, 2}};
  EXPECT_EQ(positions(Path), Expected);
}

TEST(VanillaSearch, OpensWoodenDoorsOnlyForMobsThatCan) {
  DenseWorld World = flat();
  World.fill(5, 1, 0, 5, 3, 31, Stone);
  World.fill(5, 1, 2, 5, 2, 2, WoodDoor);
  const Request Call = walk({2, 1, 2}, {8, 1, 2});

  Profile Villager;
  Villager.CanOpenDoors = true;
  const Result Through = find(World, Villager, Call);
  EXPECT_TRUE(Through.Reached);
  ASSERT_EQ(Through.Nodes.size(), 7u);
  EXPECT_EQ(Through.Nodes[3].Pos, (BlockPos{5, 1, 2}));
  EXPECT_EQ(Through.Nodes[3].Type, PathType::WALKABLE_DOOR);

  EXPECT_FALSE(find(World, Profile{}, Call).Reached);

  Villager.CanPassDoors = false;
  EXPECT_FALSE(find(World, Villager, Call).Reached);
}

TEST(VanillaSearch, IronDoorsStayShut) {
  DenseWorld World = flat();
  World.fill(5, 1, 0, 5, 3, 31, Stone);
  World.fill(5, 1, 2, 5, 2, 2, IronDoor);
  Profile Villager;
  Villager.CanOpenDoors = true;
  EXPECT_FALSE(find(World, Villager, walk({2, 1, 2}, {8, 1, 2})).Reached);
}

TEST(VanillaSearch, OpenDoorsPassOnlyMobsThatPassDoors) {
  DenseWorld World = flat();
  World.fill(5, 1, 0, 5, 3, 31, Stone);
  World.fill(5, 1, 2, 5, 2, 2, OpenDoor);
  const Request Call = walk({2, 1, 2}, {8, 1, 2});
  EXPECT_TRUE(find(World, Profile{}, Call).Reached);
  Profile Kept;
  Kept.CanPassDoors = false;
  EXPECT_FALSE(find(World, Kept, Call).Reached);
}

TEST(VanillaSearch, TheHeuristicIsWeighted) {
  // A slightly costly cell straight ahead. Vanilla weights the distance to the
  // target by 1.5, which favours this cell over the diagonal. Unweighted, the
  // diagonal would leave the heap first.
  DenseWorld World = flat();
  World.set(3, 0, 2, world::Cell{PathType::STICKY_HONEY, 15});
  Profile Mob;
  Mob.Malus[static_cast<int>(PathType::STICKY_HONEY)] = 0.54F;
  Request Call = walk({2, 1, 2}, {8, 1, 2});
  // Expand the start and one more node, so the path shows which came first.
  Call.MaxVisitedNodes = 3;
  const Result Path = find(World, Mob, Call);
  const std::vector<BlockPos> Expected = {{2, 1, 2}, {3, 1, 2}, {4, 1, 2}};
  EXPECT_EQ(positions(Path), Expected);
}

TEST(VanillaSearch, ReusedScratchStateDoesNotLeakBetweenSearches) {
  DenseWorld World = flat();
  World.fill(5, 1, 0, 5, 1, 20, Stone);
  const auto Area = World.load();
  const Request First = walk({2, 1, 2}, {9, 1, 4});
  const Request Second = walk({20, 1, 20}, {3, 1, 25});

  VanillaSearch Search;
  Result Before;
  Search.find(*Area, Profile{}, First, Before);
  Result Between;
  Search.find(*Area, Profile{}, Second, Between);
  Result After;
  Search.find(*Area, Profile{}, First, After);

  EXPECT_EQ(After.Nodes, Before.Nodes);
  EXPECT_EQ(After.Reached, Before.Reached);
  EXPECT_EQ(After.Cost, Before.Cost);
}

TEST(WalkNodeEvaluator, ABigMobBesideDangerCapsItsMalus) {
  DenseWorld World = flat();
  World.set(10, 1, 8, Cactus);
  const auto Area = World.load();
  const Request Call = walk({2, 1, 2}, {3, 1, 2});

  Profile Big;
  Big.Width = 1.4F;
  WalkNodeEvaluator Evaluator;
  Evaluator.prepare(*Area, Big, Call);
  // The footprint covers the cells beside the cactus, but the node itself is
  // cheaper, so the cost is capped at the node's.
  EXPECT_EQ(Evaluator.getPathTypeOfMob(8, 1, 8),
            PathType::BIG_MOBS_CLOSE_TO_DANGER);

  const Profile Small;
  Evaluator.prepare(*Area, Small, Call);
  EXPECT_EQ(Evaluator.getPathTypeOfMob(8, 1, 8), PathType::WALKABLE);
  EXPECT_EQ(Evaluator.getPathTypeOfMob(9, 1, 8),
            PathType::DAMAGING_IN_NEIGHBOR);
}

TEST(WalkNodeEvaluator, ARailIsOnlyPassableFromARail) {
  DenseWorld World = flat();
  World.set(8, 1, 8, Rail);
  const auto Area = World.load();
  const Profile Mob;
  WalkNodeEvaluator Evaluator;

  // The start node is used as the mob's position.
  const Request OffTheRail = walk({2, 1, 2}, {8, 1, 8});
  Evaluator.prepare(*Area, Mob, OffTheRail);
  EXPECT_EQ(Evaluator.getPathTypeOfMob(8, 1, 8), PathType::UNPASSABLE_RAIL);

  const Request OnTheRail = walk({8, 1, 8}, {2, 1, 2});
  Evaluator.prepare(*Area, Mob, OnTheRail);
  EXPECT_EQ(Evaluator.getPathTypeOfMob(8, 1, 8), PathType::RAIL);
}

namespace {

/// The neighbours `Evaluator` offers from the node at `From`.
std::vector<Node> neighborsOf(WalkNodeEvaluator &Evaluator, BlockPos From) {
  const NodeId Start = Evaluator.getStart();
  EXPECT_EQ(Evaluator.node(Start).X, From.X);
  std::array<NodeId, 32> Ids{};
  const int Count = Evaluator.getNeighbors(Ids, Start);
  std::vector<Node> Out;
  for (int I = 0; I < Count; ++I)
    Out.push_back(Evaluator.node(Ids[I]));
  return Out;
}

bool offers(const std::vector<Node> &Neighbors, BlockPos Pos) {
  return std::ranges::any_of(Neighbors, [&](const Node &N) {
    return N.X == Pos.X && N.Y == Pos.Y && N.Z == Pos.Z;
  });
}

} // namespace

TEST(WalkNodeEvaluator, ANodeCostIsOnlyEverRaised) {
  // A mob that avoids water, standing on honey so it cannot jump. The water
  // beside it is checked for a floor, and each water node found gets the
  // larger of its current cost and the water malus. A new node's cost is 0,
  // so the -1 malus is never applied.
  DenseWorld World(32, -4, 16);
  World.fill(0, -4, 0, 31, 0, 31, Stone);
  World.set(2, 0, 2, world::Cell{PathType::STICKY_HONEY, 15});
  World.fill(3, -1, 2, 3, 1, 2, Water);
  const auto Area = World.load();
  Profile Mob;
  Mob.Malus[static_cast<int>(PathType::WATER)] = -1.0F;
  const Request Call = walk({2, 1, 2}, {8, 1, 2});

  WalkNodeEvaluator Evaluator;
  Evaluator.prepare(*Area, Mob, Call);
  const std::vector<Node> Neighbors = neighborsOf(Evaluator, Call.Start);
  const auto Deepest = std::ranges::find_if(Neighbors, [](const Node &N) {
    return N.X == 3 && N.Y == -1 && N.Z == 2;
  });
  ASSERT_NE(Deepest, Neighbors.end());
  EXPECT_EQ(Deepest->Type, PathType::WATER);
  EXPECT_EQ(Deepest->CostMalus, 0.0F);
}

TEST(WalkNodeEvaluator, LeavingAFenceSweepsTheMobsBox) {
  // A wide mob whose footprint includes a fence and which tolerates fences,
  // so its node is a fence node. A step away clears the fence; a step towards
  // it sweeps the mob's box through the post.
  DenseWorld World = flat();
  World.set(6, 1, 2, Fence);
  const auto Area = World.load();
  Profile Wide;
  Wide.Width = 1.2F;
  Wide.Malus[static_cast<int>(PathType::FENCE)] = 0.0F;
  const Request Call = walk({5, 1, 2}, {8, 1, 2});

  WalkNodeEvaluator Evaluator;
  Evaluator.prepare(*Area, Wide, Call);
  EXPECT_EQ(Evaluator.getCachedPathType(5, 1, 2), PathType::FENCE);
  const std::vector<Node> Neighbors = neighborsOf(Evaluator, Call.Start);
  EXPECT_TRUE(offers(Neighbors, {4, 1, 2}));
  EXPECT_FALSE(offers(Neighbors, {6, 1, 2}));
}

TEST(WalkNodeEvaluator, AJumpNeedsHeadroomOverTheTakeOff) {
  DenseWorld World = flat();
  World.fill(5, 1, 0, 31, 1, 31, Stone);
  const Request Call = walk({4, 1, 2}, {8, 2, 2});
  const Profile Mob;
  WalkNodeEvaluator Evaluator;

  const auto Open = World.load();
  Evaluator.prepare(*Open, Mob, Call);
  EXPECT_TRUE(offers(neighborsOf(Evaluator, Call.Start), {5, 2, 2}));

  // A carpet at head height is passable, so the mob still tries, but its
  // collision box blocks the jump.
  World.set(4, 3, 2, world::Cell{PathType::OPEN, 1});
  const auto Covered = World.load();
  Evaluator.prepare(*Covered, Mob, Call);
  EXPECT_FALSE(offers(neighborsOf(Evaluator, Call.Start), {5, 2, 2}));
}

TEST(WalkNodeEvaluator, NoCornerIsCutPastAStepUp) {
  DenseWorld World = flat();
  World.set(3, 1, 2, Stone);
  const auto Area = World.load();
  const Request Call = walk({2, 1, 2}, {8, 1, 2});
  const Profile Mob;
  WalkNodeEvaluator Evaluator;
  Evaluator.prepare(*Area, Mob, Call);
  const std::vector<Node> Neighbors = neighborsOf(Evaluator, Call.Start);
  EXPECT_TRUE(offers(Neighbors, {3, 2, 2}));
  EXPECT_FALSE(offers(Neighbors, {3, 1, 1}));
  EXPECT_FALSE(offers(Neighbors, {3, 1, 3}));
  EXPECT_TRUE(offers(Neighbors, {1, 1, 1}));
}

TEST(WalkNodeEvaluator, AWideMobCutsNoCornerBesideACostlyNode) {
  // Honey under one corner of the start. The wide footprint puts it in the
  // north and east neighbours, so only the south-west corner can be cut.
  DenseWorld World = flat();
  World.set(3, 0, 2, world::Cell{PathType::STICKY_HONEY, 15});
  const auto Area = World.load();
  Profile Wide;
  Wide.Width = 1.2F;
  const Request Call = walk({2, 1, 2}, {8, 1, 2});
  WalkNodeEvaluator Evaluator;
  Evaluator.prepare(*Area, Wide, Call);
  const std::vector<Node> Neighbors = neighborsOf(Evaluator, Call.Start);
  EXPECT_TRUE(offers(Neighbors, {1, 1, 3}));
  EXPECT_FALSE(offers(Neighbors, {3, 1, 1}));
  EXPECT_FALSE(offers(Neighbors, {3, 1, 3}));
  EXPECT_FALSE(offers(Neighbors, {1, 1, 1}));
}

TEST(WalkNodeEvaluator, ASmallMobInTheOpenIgnoresFreeTypesAboveIt) {
  DenseWorld World = flat();
  World.set(8, 3, 8, world::Cell{PathType::COCOA, 12});
  const auto Area = World.load();
  const Profile Mob;
  WalkNodeEvaluator Evaluator;
  const Request Call = walk({2, 1, 2}, {3, 1, 2});
  Evaluator.prepare(*Area, Mob, Call);
  // The footprint at y = 2 contains open air and the cocoa, both free. The
  // node itself is open, so it stays open.
  EXPECT_EQ(Evaluator.getPathTypeOfMob(8, 2, 8), PathType::OPEN);
}

TEST(WalkNodeEvaluator, FloorLevelFollowsTheCollisionHeightBelow) {
  DenseWorld World = flat();
  World.set(8, 0, 9, world::Cell{PathType::BLOCKED, 8});
  World.set(8, 0, 10, Fence);
  World.set(8, 1, 8, Water);
  const auto Area = World.load();
  const Request Call = walk({2, 1, 2}, {3, 1, 2});
  WalkNodeEvaluator Evaluator;

  Profile Sinks;
  Evaluator.prepare(*Area, Sinks, Call);
  EXPECT_EQ(Evaluator.getFloorLevel(8, 1, 8), 1.0);
  EXPECT_EQ(Evaluator.getFloorLevel(8, 1, 9), 0.5);
  EXPECT_EQ(Evaluator.getFloorLevel(8, 1, 10), 1.5);
  EXPECT_EQ(Evaluator.getFloorLevel(8, 1, 11), 1.0);

  // A mob that floats stands halfway up a water cell.
  Profile Floats;
  Floats.CanFloat = true;
  Evaluator.prepare(*Area, Floats, Call);
  EXPECT_EQ(Evaluator.getFloorLevel(8, 1, 8), 1.5);
}

TEST(Search, TheVanillaSearchIsAvailableByName) {
  const auto Made = makeSearch(fbs::Pathfinder::VANILLA);
  EXPECT_NE(dynamic_cast<VanillaSearch *>(Made.get()), nullptr);
}

TEST(Search, ARequestDecodesEveryField) {
  flatbuffers::FlatBufferBuilder Builder;
  const fbs::Vec3s Start(1, 64, -1);
  const fbs::Vec3s Target(-30, 70, 12);
  Builder.Finish(fbs::CreatePathRequest(Builder, 42, 3, &Start, &Target, 6,
                                        40.5F, 640, 16));
  const auto *Wire =
      flatbuffers::GetRoot<fbs::PathRequest>(Builder.GetBufferPointer());
  const auto Call = Request::decode(*Wire);
  ASSERT_TRUE(Call.has_value());
  EXPECT_EQ(Call->Start, (BlockPos{1, 64, -1}));
  EXPECT_EQ(Call->Target, (BlockPos{-30, 70, 12}));
  EXPECT_EQ(Call->ReachRange, 6);
  EXPECT_EQ(Call->MaxPathLength, 40.5F);
  EXPECT_EQ(Call->MaxVisitedNodes, 640);
  EXPECT_EQ(Call->MaxFallDistance, 16);
}

TEST(Search, ARequestWithoutPositionsIsRefused) {
  flatbuffers::FlatBufferBuilder Builder;
  const fbs::Vec3s Start(1, 64, -1);
  Builder.Finish(fbs::CreatePathRequest(Builder, 42, 3, &Start, nullptr));
  const auto *Wire =
      flatbuffers::GetRoot<fbs::PathRequest>(Builder.GetBufferPointer());
  EXPECT_FALSE(Request::decode(*Wire).has_value());
}

TEST(Search, AResultEncodesAsAResponseFrame) {
  Result Path;
  Path.Nodes = {{{0, 1, 0}, PathType::WALKABLE},
                {{1, 1, 0}, PathType::WALKABLE_DOOR}};
  Path.Reached = true;
  Path.Cost = 1.5F;
  flatbuffers::FlatBufferBuilder Builder;
  ASSERT_TRUE(encodeResponse(Builder, 5, 7, Path, 99));

  flatbuffers::Verifier Verifier(Builder.GetBufferPointer(), Builder.GetSize());
  ASSERT_TRUE(Verifier.VerifyBuffer<fbs::ResponseFrame>(nullptr));
  const auto *Frame =
      flatbuffers::GetRoot<fbs::ResponseFrame>(Builder.GetBufferPointer());
  EXPECT_EQ(Frame->run(), 5u);
  const auto *Response = Frame->body_as_PathResponse();
  ASSERT_NE(Response, nullptr);
  EXPECT_EQ(Response->request_id(), 7u);
  EXPECT_TRUE(Response->reached());
  EXPECT_EQ(Response->cost(), 1.5F);
  EXPECT_EQ(Response->world_sequence(), 99u);
  ASSERT_EQ(Response->nodes()->size(), 2u);
  EXPECT_EQ(Response->nodes()->Get(1)->pos().x(), 1);
  EXPECT_EQ(Response->nodes()->Get(1)->type(), PathType::WALKABLE_DOOR);
}

TEST(Search, APathPastTheWireRangeIsNotEncoded) {
  // Truncating a position would send a path through the wrong blocks.
  Result Path;
  Path.Nodes = {{{0, 1, 0}, PathType::WALKABLE},
                {{40000, 1, 0}, PathType::WALKABLE}};
  flatbuffers::FlatBufferBuilder Builder;
  EXPECT_FALSE(encodeResponse(Builder, 5, 7, Path, 99));
  EXPECT_EQ(Builder.GetSize(), 0u);
}

TEST(Search, OneBuilderServesEveryResponse) {
  // A reused builder is cleared each time, so the second frame holds only its
  // own response.
  Result Long;
  for (int X = 0; X < 40; ++X)
    Long.Nodes.push_back({{X, 1, -X}, PathType::WALKABLE});
  Result Short;
  Short.Nodes = {{{-5, -64, 5}, PathType::OPEN}};
  flatbuffers::FlatBufferBuilder Builder;
  ASSERT_TRUE(encodeResponse(Builder, 5, 1, Long, 3));
  ASSERT_TRUE(encodeResponse(Builder, 5, 2, Short, 4));

  flatbuffers::Verifier Verifier(Builder.GetBufferPointer(), Builder.GetSize());
  ASSERT_TRUE(Verifier.VerifyBuffer<fbs::ResponseFrame>(nullptr));
  const auto *Response =
      flatbuffers::GetRoot<fbs::ResponseFrame>(Builder.GetBufferPointer())
          ->body_as_PathResponse();
  ASSERT_NE(Response, nullptr);
  EXPECT_EQ(Response->request_id(), 2u);
  ASSERT_EQ(Response->nodes()->size(), 1u);
  EXPECT_EQ(Response->nodes()->Get(0)->pos().x(), -5);
  EXPECT_EQ(Response->nodes()->Get(0)->pos().y(), -64);
  EXPECT_EQ(Response->nodes()->Get(0)->pos().z(), 5);
  EXPECT_EQ(Response->nodes()->Get(0)->type(), PathType::OPEN);

  ASSERT_TRUE(encodeResponse(Builder, 5, 1, Long, 3));
  const auto *Again =
      flatbuffers::GetRoot<fbs::ResponseFrame>(Builder.GetBufferPointer())
          ->body_as_PathResponse();
  ASSERT_EQ(Again->nodes()->size(), Long.Nodes.size());
  for (std::size_t I = 0; I < Long.Nodes.size(); ++I) {
    EXPECT_EQ(Again->nodes()->Get(I)->pos().x(), Long.Nodes[I].Pos.X);
    EXPECT_EQ(Again->nodes()->Get(I)->pos().z(), Long.Nodes[I].Pos.Z);
  }
}

TEST(Search, AnUnknownProfileEncodesAsAResponseFrame) {
  flatbuffers::FlatBufferBuilder Builder;
  ASSERT_TRUE(encodeResponse(Builder, 5, 1, Result{}, 3));
  encodeUnknownProfile(Builder, 6, 8, 41);

  flatbuffers::Verifier Verifier(Builder.GetBufferPointer(), Builder.GetSize());
  ASSERT_TRUE(Verifier.VerifyBuffer<fbs::ResponseFrame>(nullptr));
  const auto *Frame =
      flatbuffers::GetRoot<fbs::ResponseFrame>(Builder.GetBufferPointer());
  EXPECT_EQ(Frame->run(), 6u);
  EXPECT_EQ(Frame->body_as_PathResponse(), nullptr);
  ASSERT_NE(Frame->body_as_UnknownProfile(), nullptr);
  EXPECT_EQ(Frame->body_as_UnknownProfile()->request_id(), 8u);
  EXPECT_EQ(Frame->body_as_UnknownProfile()->class_id(), 41u);
}

TEST(Profile, DecodesAMobClass) {
  flatbuffers::FlatBufferBuilder Builder;
  std::vector<float> Malus(world::PathTypeCount, 0.0F);
  Malus[static_cast<int>(PathType::WATER)] = -1.0F;
  Builder.Finish(fbs::CreateMobClass(Builder, 5, 0.6F, 1.95F,
                                     Builder.CreateVector(Malus), 1.0F, true,
                                     true, false, true));
  const auto Mob = Profile::decode(
      *flatbuffers::GetRoot<fbs::MobClass>(Builder.GetBufferPointer()));
  ASSERT_TRUE(Mob.has_value());
  EXPECT_EQ(Mob->ClassId, 5);
  EXPECT_EQ(Mob->Height, 1.95F);
  EXPECT_EQ(Mob->MaxUpStep, 1.0F);
  EXPECT_EQ(Mob->malus(PathType::WATER), -1.0F);
  EXPECT_TRUE(Mob->CanOpenDoors);
  EXPECT_FALSE(Mob->CanFloat);
  EXPECT_TRUE(Mob->CanWalkOverFences);
}

TEST(Profile, RefusesAMobClassThatBreaksTheContract) {
  const auto Decode = [](std::size_t MalusCount, float Width, float Height,
                         float Step) {
    flatbuffers::FlatBufferBuilder Builder;
    const std::vector<float> Malus(MalusCount, 0.0F);
    Builder.Finish(fbs::CreateMobClass(Builder, 1, Width, Height,
                                       Builder.CreateVector(Malus), Step));
    return Profile::decode(
        *flatbuffers::GetRoot<fbs::MobClass>(Builder.GetBufferPointer()));
  };
  EXPECT_TRUE(Decode(world::PathTypeCount, 0.6F, 1.8F, 0.6F).has_value());
  EXPECT_FALSE(Decode(world::PathTypeCount - 1, 0.6F, 1.8F, 0.6F).has_value());
  EXPECT_FALSE(Decode(world::PathTypeCount, 0.0F, 1.8F, 0.6F).has_value());
  EXPECT_FALSE(Decode(world::PathTypeCount, 0.6F, NAN, 0.6F).has_value());
  EXPECT_FALSE(Decode(world::PathTypeCount, 0.6F, 1.8F, -1.0F).has_value());
  // A footprint past vanilla's largest would make every search crawl.
  EXPECT_TRUE(Decode(world::PathTypeCount, Profile::WidthLimit,
                     Profile::HeightLimit, Profile::UpStepLimit)
                  .has_value());
  EXPECT_FALSE(Decode(world::PathTypeCount, 2000.0F, 1.8F, 0.6F).has_value());
  EXPECT_FALSE(Decode(world::PathTypeCount, 0.6F, 300.0F, 0.6F).has_value());
  EXPECT_FALSE(Decode(world::PathTypeCount, 0.6F, 1.8F, 20.0F).has_value());
  EXPECT_FALSE(Decode(world::PathTypeCount, INFINITY, 1.8F, 0.6F).has_value());
  EXPECT_FALSE(Decode(world::PathTypeCount, 0.6F, 1.8F, NAN).has_value());
}
