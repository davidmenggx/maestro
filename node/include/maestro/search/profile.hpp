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

  /// Bounds on the shape, which hold every land mob vanilla can make. The
  /// search's cost grows with the footprint, so a larger one is refused.
  static constexpr float WidthLimit = 64.0F;
  static constexpr float HeightLimit = 256.0F;
  static constexpr float UpStepLimit = 16.0F;

  float malus(world::PathType Type) const {
    return Malus[static_cast<std::size_t>(Type)];
  }

  /// The profile `Wire` describes, or nothing if it breaks the contract: it
  /// needs a malus for every type, and a footprint and step height within the
  /// bounds above.
  static std::optional<Profile> decode(const fbs::MobClass &Wire);
};

} // namespace maestro::search
