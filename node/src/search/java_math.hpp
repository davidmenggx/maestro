#pragma once

#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>

namespace maestro::search::java {

/// `(int) Value`: truncates, saturates at the `int` range and maps NaN to 0,
/// where a plain C++ cast of an out-of-range value is undefined.
inline int toInt(double Value) {
  if (std::isnan(Value))
    return 0;
  if (Value >= static_cast<double>(std::numeric_limits<int>::max()))
    return std::numeric_limits<int>::max();
  if (Value <= static_cast<double>(std::numeric_limits<int>::min()))
    return std::numeric_limits<int>::min();
  return static_cast<int>(Value);
}

/// `Mth.floor`, for both its float and double overloads.
inline int floor(double Value) { return toInt(std::floor(Value)); }

/// `Mth.ceil`.
inline int ceil(double Value) { return toInt(std::ceil(Value)); }

/// `Math.max(float, float)`: NaN wins, and -0.0 orders below 0.0.
inline float max(float A, float B) {
  if (std::isnan(A))
    return A;
  if (A == 0.0F && B == 0.0F &&
      std::bit_cast<std::uint32_t>(A) == std::bit_cast<std::uint32_t>(-0.0F))
    return B;
  return A >= B ? A : B;
}

} // namespace maestro::search::java
