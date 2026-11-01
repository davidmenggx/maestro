#include <array>
#include <cmath>
#include <cstdlib>
#include <random>
#include <string>
#include <vector>

#include <gtest/gtest.h>

#include "maestro/search/vanilla_search.hpp"
#include "test_world.hpp"

using namespace maestro;
using namespace maestro::search;
using namespace maestro::test;
using world::Cell;
using world::PathType;

namespace {

/// Blocks past its start a search may read along either horizontal axis, as
/// the controller's `SearchReach.blocks` bounds it. A request goes only to a
/// node whose area holds all of them, since the node reads past it as air.
int reach(float MaxPathLength, float Width) {
  return static_cast<int>(std::ceil(MaxPathLength)) +
         static_cast<int>(std::floor(Width + 1.0F)) + 1;
}

constexpr int Size = 96;
constexpr int MinY = 0;
constexpr int Height = 24;
constexpr int Centre = Size / 2;

/// Mild hills with cliffs, and walls, water and most per-block types a search
/// treats differently scattered over them.
DenseWorld terrain(std::mt19937 &Rng) {
  const auto Below = [&](int Bound) {
    return std::uniform_int_distribution<int>(0, Bound - 1)(Rng);
  };
  // Ground heights on a coarse grid, so neighbours mostly step by a block and
  // some fall further.
  constexpr int Grid = 4;
  std::array<std::array<int, Size / Grid + 1>, Size / Grid + 1> Corners{};
  for (auto &Row : Corners)
    for (int &Corner : Row)
      Corner = 4 + Below(5);

  const std::array<Cell, 14> Hazards = {Stone,
                                        Fence,
                                        Fire,
                                        Cactus,
                                        WoodDoor,
                                        IronDoor,
                                        OpenDoor,
                                        Rail,
                                        Cell{PathType::TRAPDOOR, 3},
                                        Cell{PathType::LEAVES, 16},
                                        Cell{PathType::POWDER_SNOW, 0},
                                        Cell{PathType::STICKY_HONEY, 15},
                                        Cell{PathType::DAMAGE_CAUTIOUS, 0},
                                        Cell{PathType::COCOA, 9}};
  DenseWorld World(Size, MinY, Height);
  for (int X = 0; X < Size; ++X)
    for (int Z = 0; Z < Size; ++Z) {
      const int Ground = Corners[X / Grid][Z / Grid];
      World.fill(X, MinY, Z, X, Ground - 1, Z, Stone);
      const int Roll = Below(100);
      if (Roll < 4) {
        World.set(X, Ground - 1, Z, Water);
      } else if (Roll < 5) {
        World.set(X, Ground - 1, Z, Lava);
      } else if (Roll < 25) {
        const Cell Hazard = Hazards[Below(static_cast<int>(Hazards.size()))];
        const int Tall = Hazard == Stone || Hazard == WoodDoor ||
                                 Hazard == IronDoor || Hazard == OpenDoor
                             ? 1 + Below(2)
                             : 1;
        World.fill(X, Ground, Z, X, Ground + Tall - 1, Z, Hazard);
      }
    }
  return World;
}

/// `World` with every column further than `Radius` from `Start` along either
/// axis replaced by `Column`.
DenseWorld cut(DenseWorld World, BlockPos Start, int Radius,
               const DenseWorld &Column) {
  for (int X = 0; X < Size; ++X)
    for (int Z = 0; Z < Size; ++Z)
      if (std::abs(X - Start.X) > Radius || std::abs(Z - Start.Z) > Radius)
        for (int Y = MinY; Y < MinY + Height; ++Y)
          World.set(X, Y, Z, Column.at(X, Y, Z));
  return World;
}

/// One search, with a target far enough away that the path length, not the
/// target, ends it.
struct Case {
  Profile Mob;
  Request Call;

  std::string describe() const {
    return "width " + std::to_string(Mob.Width) + ", height " +
           std::to_string(Mob.Height) + ", step " +
           std::to_string(Mob.MaxUpStep) + ", path length " +
           std::to_string(Call.MaxPathLength) + ", target " +
           std::to_string(Call.Target.X) + "," + std::to_string(Call.Target.Z);
  }
};

std::vector<Case> cases(std::mt19937 &Rng, const DenseWorld &World) {
  const auto Below = [&](int Bound) {
    return std::uniform_int_distribution<int>(0, Bound - 1)(Rng);
  };
  const std::array<float, 7> Widths = {0.3F,  0.6F, 0.9F, 1.4F,
                                       1.95F, 2.9F, 3.6F};
  const std::array<float, 4> Heights = {0.5F, 0.9F, 1.8F, 2.7F};
  const std::array<float, 6> Lengths = {4.0F, 7.5F, 12.0F, 16.0F, 19.3F, 24.0F};
  // Far past the bound, in each direction and between them.
  const std::array<std::array<int, 2>, 8> Directions = {
      {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}}};
  std::vector<Case> Out;
  for (int I = 0; I < 48; ++I) {
    Case One;
    One.Mob.Width = Widths[Below(static_cast<int>(Widths.size()))];
    One.Mob.Height = Heights[Below(static_cast<int>(Heights.size()))];
    One.Mob.MaxUpStep = Below(3) == 0 ? 1.0F : 0.6F;
    One.Mob.CanOpenDoors = Below(2) == 0;
    One.Mob.CanPassDoors = Below(4) != 0;
    One.Mob.CanFloat = Below(2) == 0;
    One.Mob.CanWalkOverFences = Below(4) == 0;
    if (Below(3) == 0)
      One.Mob.Malus[static_cast<std::size_t>(PathType::WATER)] = 0.0F;
    if (Below(3) == 0)
      One.Mob.Malus[static_cast<std::size_t>(PathType::FIRE_IN_NEIGHBOR)] =
          0.0F;

    const auto Direction = Directions[I % Directions.size()];
    // Starts on the ground near the centre.
    One.Call.Start.X = Centre - 2 + Below(5);
    One.Call.Start.Z = Centre - 2 + Below(5);
    int Ground = MinY;
    while (Ground < MinY + Height &&
           World.at(One.Call.Start.X, Ground, One.Call.Start.Z) != world::Air)
      ++Ground;
    One.Call.Start.Y = Ground;
    One.Call.Target = {One.Call.Start.X + Direction[0] * 40, Ground,
                       One.Call.Start.Z + Direction[1] * 40};
    One.Call.MaxPathLength = Lengths[Below(static_cast<int>(Lengths.size()))];
    One.Call.MaxVisitedNodes = 1 << 20;
    One.Call.MaxFallDistance = Below(2) == 0 ? 3 : 20;
    Out.push_back(One);
  }
  return Out;
}

Result find(const DenseWorld &World, const Case &One) {
  const auto Area = World.load();
  VanillaSearch Search;
  Result Out;
  Search.find(*Area, One.Mob, One.Call, Out);
  return Out;
}

bool same(const Result &A, const Result &B) {
  return A.Nodes == B.Nodes && A.Reached == B.Reached && A.Cost == B.Cost;
}

/// A world of `Value` alone.
DenseWorld filled(Cell Value) {
  DenseWorld World(Size, MinY, Height);
  World.fill(0, MinY, 0, Size - 1, MinY + Height - 1, Size - 1, Value);
  return World;
}

} // namespace

TEST(SearchReach, TheBoundIsTheControllers) {
  // The values `SearchReachTest` checks on the controller.
  EXPECT_EQ(reach(16.0F, 0.6F), 16 + 1 + 1);
  EXPECT_EQ(reach(16.0F, 1.4F), 16 + 2 + 1);
  EXPECT_EQ(reach(35.5F, 0.6F), 36 + 1 + 1);
}

// The controller sends a request only if its search cannot read past the
// node's area, which the node reads as air. So nothing past the bound may
// change an answer, whatever stands there. Fire is the most telling: any cell
// whose type scan reaches it borders fire and costs more.
TEST(SearchReach, NothingPastTheBoundChangesAnAnswer) {
  std::mt19937 Rng(20261030);
  const DenseWorld Air(Size, MinY, Height);
  const DenseWorld Wall = filled(Stone);
  const DenseWorld Burning = filled(Fire);
  for (int World = 0; World < 4; ++World) {
    const DenseWorld Full = terrain(Rng);
    const DenseWorld Other = terrain(Rng);
    for (const Case &One : cases(Rng, Full)) {
      SCOPED_TRACE("world " + std::to_string(World) + ": " + One.describe());
      const int Bound = reach(One.Call.MaxPathLength, One.Mob.Width);
      ASSERT_GE(One.Call.Start.X - Bound, 0);
      ASSERT_LT(One.Call.Start.X + Bound, Size);
      ASSERT_GE(One.Call.Start.Z - Bound, 0);
      ASSERT_LT(One.Call.Start.Z + Bound, Size);
      const Result Expected = find(Full, One);
      for (const DenseWorld *Past :
           std::array<const DenseWorld *, 4>{&Air, &Wall, &Burning, &Other})
        EXPECT_TRUE(
            same(Expected, find(cut(Full, One.Call.Start, Bound, *Past), One)));
    }
  }
}

// The bound also covers the cells around neighbours a search reads but never
// takes, so an answer depends on cells at most two blocks inside it. The cases
// reach that far, or the test above would pass whatever the search read.
TEST(SearchReach, TheCasesReachAsFarAsAnAnswerCanDepend) {
  std::mt19937 Rng(20261030);
  const DenseWorld Burning = filled(Fire);
  int Changed = 0;
  for (int World = 0; World < 4; ++World) {
    const DenseWorld Full = terrain(Rng);
    terrain(Rng);
    for (const Case &One : cases(Rng, Full)) {
      const int Inside = reach(One.Call.MaxPathLength, One.Mob.Width) - 3;
      Changed += same(find(Full, One),
                      find(cut(Full, One.Call.Start, Inside, Burning), One))
                     ? 0
                     : 1;
    }
  }
  EXPECT_GT(Changed, 0);
}
