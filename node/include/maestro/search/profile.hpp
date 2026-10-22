#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>

#include "maestro/search/path_types.hpp"

namespace maestro::fbs {
struct MobClass;
} // namespace maestro::fbs

namespace maestro::search {

/// The `WalkNodeEvaluator` inputs of one `MobClass`. A default-constructed
/// profile has vanilla's default malus table and door handling.
struct Profile {
  std::uint16_t ClassId = 0;
  float Width = 0.6F;
  float Height = 1.8F;
  float MaxUpStep = 0.6F;
  std::array<float, world::PathTypeCount> Malus = defaultMalus();
  bool CanPassDoors = true;
  bool CanOpenDoors = false;
  bool CanFloat = false;
  bool CanWalkOverFences = false;

  /// Most cells the evaluator's footprint may hold.
  static constexpr std::int64_t FootprintLimit = 512;
  /// Highest step-up height, past that of every land mob vanilla can make.
  static constexpr float UpStepLimit = 16.0F;

  float malus(world::PathType Type) const {
    return Malus[static_cast<std::size_t>(Type)];
  }

  /// The profile `Wire` describes, or nothing if it breaks the contract: it
  /// needs a malus for every type, a finite, positive shape whose footprint,
  /// as `NodeEvaluator.prepare` sizes it, is within `FootprintLimit`, and a
  /// step height within `UpStepLimit`.
  static std::optional<Profile> decode(const fbs::MobClass &Wire);
};

} // namespace maestro::search
