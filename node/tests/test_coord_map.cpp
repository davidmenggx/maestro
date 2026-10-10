#include "maestro/search/coord_map.hpp"

#include <cstdint>

#include <gtest/gtest.h>

using maestro::search::CoordMap;

TEST(CoordMap, FindsWhatWasInserted) {
  CoordMap<int> Map;
  EXPECT_EQ(Map.find(1, 2, 3), nullptr);
  const auto [Value, Inserted] = Map.tryEmplace(1, 2, 3, 7);
  EXPECT_TRUE(Inserted);
  EXPECT_EQ(*Value, 7);
  ASSERT_NE(Map.find(1, 2, 3), nullptr);
  EXPECT_EQ(*Map.find(1, 2, 3), 7);
  EXPECT_EQ(Map.find(3, 2, 1), nullptr);
}

TEST(CoordMap, ASecondInsertKeepsTheFirstValue) {
  CoordMap<int> Map;
  Map.tryEmplace(-4, 70, 9, 1);
  const auto [Value, Inserted] = Map.tryEmplace(-4, 70, 9, 2);
  EXPECT_FALSE(Inserted);
  EXPECT_EQ(*Value, 1);
}

TEST(CoordMap, NegativeAndDistantCoordinatesStayApart) {
  CoordMap<int> Map;
  const int Edge = (1 << 20) - 1;
  int Next = 0;
  for (int X : {-Edge - 1, -1, 0, 1, Edge})
    for (int Y : {-64, 0, 319})
      for (int Z : {-Edge - 1, -1, 0, Edge})
        Map.tryEmplace(X, Y, Z, Next++);
  Next = 0;
  for (int X : {-Edge - 1, -1, 0, 1, Edge})
    for (int Y : {-64, 0, 319})
      for (int Z : {-Edge - 1, -1, 0, Edge}) {
        ASSERT_NE(Map.find(X, Y, Z), nullptr);
        EXPECT_EQ(*Map.find(X, Y, Z), Next++);
      }
}

TEST(CoordMap, KeepsEverythingAcrossGrowth) {
  CoordMap<int> Map;
  for (int I = 0; I < 5000; ++I)
    Map.tryEmplace(I % 71, I / 71, -I, I);
  for (int I = 0; I < 5000; ++I) {
    ASSERT_NE(Map.find(I % 71, I / 71, -I), nullptr);
    EXPECT_EQ(*Map.find(I % 71, I / 71, -I), I);
  }
}

TEST(CoordMap, ClearingEmptiesTheMap) {
  CoordMap<int> Map;
  for (int I = 0; I < 500; ++I)
    Map.tryEmplace(I, 0, 0, I);
  Map.clear();
  for (int I = 0; I < 500; ++I)
    EXPECT_EQ(Map.find(I, 0, 0), nullptr);
  // A cleared slot takes a new value.
  const auto [Value, Inserted] = Map.tryEmplace(3, 0, 0, 99);
  EXPECT_TRUE(Inserted);
  EXPECT_EQ(*Value, 99);
}

TEST(CoordMap, StaysCorrectWhenTheGenerationWraps) {
  // Each clear advances the generation. The counter starts at 1, so this many
  // clears wrap it back to 1. The first slot written would look live again
  // unless the wrap wipes it.
  constexpr int ClearsToWrap = (1 << 16) - 1;
  CoordMap<int> Map;
  Map.tryEmplace(5, 5, 5, 1);
  for (int I = 0; I < ClearsToWrap; ++I) {
    Map.clear();
    if (I % 4096 == 0)
      Map.tryEmplace(I, 0, 0, I);
  }
  EXPECT_EQ(Map.find(5, 5, 5), nullptr);
  for (int I = 0; I < ClearsToWrap; I += 4096)
    EXPECT_EQ(Map.find(I, 0, 0), nullptr);
  Map.tryEmplace(7, 7, 7, 2);
  ASSERT_NE(Map.find(7, 7, 7), nullptr);
  EXPECT_EQ(*Map.find(7, 7, 7), 2);
}
