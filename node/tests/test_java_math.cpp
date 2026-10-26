#include "search/java_math.hpp"

#include <cmath>
#include <limits>

#include <gtest/gtest.h>

using namespace maestro::search;

namespace {

constexpr int IntMax = std::numeric_limits<int>::max();
constexpr int IntMin = std::numeric_limits<int>::min();
constexpr double Inf = std::numeric_limits<double>::infinity();
constexpr float FloatNaN = std::numeric_limits<float>::quiet_NaN();

bool negativeZero(float Value) { return Value == 0.0F && std::signbit(Value); }

bool positiveZero(float Value) { return Value == 0.0F && !std::signbit(Value); }

} // namespace

// JLS 5.1.3: NaN narrows to 0, values past the `int` range to its nearest
// end, and the rest round toward zero.
TEST(JavaMath, ToIntNarrowsAsJavaDoes) {
  EXPECT_EQ(java::toInt(std::numeric_limits<double>::quiet_NaN()), 0);
  EXPECT_EQ(java::toInt(Inf), IntMax);
  EXPECT_EQ(java::toInt(-Inf), IntMin);
  EXPECT_EQ(java::toInt(3e9), IntMax);
  EXPECT_EQ(java::toInt(-3e9), IntMin);
  EXPECT_EQ(java::toInt(2147483647.0), IntMax);
  EXPECT_EQ(java::toInt(2147483646.5), 2147483646);
  EXPECT_EQ(java::toInt(-2147483648.0), IntMin);
  EXPECT_EQ(java::toInt(-2147483648.5), IntMin);
  EXPECT_EQ(java::toInt(2.9), 2);
  EXPECT_EQ(java::toInt(-2.9), -2);
  EXPECT_EQ(java::toInt(-0.5), 0);
}

// `Mth.floor` and `Mth.ceil` are `(int) Math.floor` and `(int) Math.ceil`.
TEST(JavaMath, FloorAndCeilRoundThenNarrow) {
  EXPECT_EQ(java::floor(-0.5), -1);
  EXPECT_EQ(java::floor(-1.0), -1);
  EXPECT_EQ(java::floor(-1e-300), -1);
  EXPECT_EQ(java::floor(1.9999999), 1);
  EXPECT_EQ(java::floor(3e9), IntMax);
  EXPECT_EQ(java::floor(-3e9), IntMin);
  EXPECT_EQ(java::floor(-Inf), IntMin);
  EXPECT_EQ(java::floor(std::numeric_limits<double>::quiet_NaN()), 0);

  EXPECT_EQ(java::ceil(-0.5), 0);
  EXPECT_EQ(java::ceil(-1.5), -1);
  EXPECT_EQ(java::ceil(1e-7), 1);
  EXPECT_EQ(java::ceil(3e9), IntMax);
  EXPECT_EQ(java::ceil(-3e9), IntMin);
  EXPECT_EQ(java::ceil(Inf), IntMax);
  EXPECT_EQ(java::ceil(std::numeric_limits<double>::quiet_NaN()), 0);
}

// `Math.max(float, float)`: NaN if either argument is, and -0.0 below 0.0.
TEST(JavaMath, MaxFollowsMathMax) {
  EXPECT_TRUE(std::isnan(java::max(FloatNaN, 1.0F)));
  EXPECT_TRUE(std::isnan(java::max(1.0F, FloatNaN)));
  EXPECT_TRUE(std::isnan(java::max(FloatNaN, FloatNaN)));
  EXPECT_TRUE(
      std::isnan(java::max(-std::numeric_limits<float>::infinity(), FloatNaN)));

  EXPECT_TRUE(positiveZero(java::max(-0.0F, 0.0F)));
  EXPECT_TRUE(positiveZero(java::max(0.0F, -0.0F)));
  EXPECT_TRUE(negativeZero(java::max(-0.0F, -0.0F)));
  EXPECT_TRUE(positiveZero(java::max(0.0F, 0.0F)));

  EXPECT_EQ(java::max(-1.0F, 8.0F), 8.0F);
  EXPECT_EQ(java::max(8.0F, -1.0F), 8.0F);
  EXPECT_TRUE(negativeZero(java::max(-1.0F, -0.0F)));
}
