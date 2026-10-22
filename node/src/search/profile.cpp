#include "maestro/search/profile.hpp"

#include <cmath>

#include "java_math.hpp"
#include "mob_generated.h"

namespace maestro::search {

namespace {

/// Whether the footprint, sized as `NodeEvaluator.prepare` does with `float`
/// addition, is within `Profile::FootprintLimit`.
bool footprintFits(float Width, float Height) {
  const std::int64_t Side = java::floor(Width + 1.0F);
  const std::int64_t Tall = java::floor(Height + 1.0F);
  // Each factor is at least 1, so checking them first avoids overflow.
  return Side <= Profile::FootprintLimit && Tall <= Profile::FootprintLimit &&
         Side * Tall * Side <= Profile::FootprintLimit;
}

} // namespace

std::optional<Profile> Profile::decode(const fbs::MobClass &Wire) {
  const auto *Malus = Wire.malus();
  if (!Malus || Malus->size() != world::PathTypeCount)
    return std::nullopt;
  // Written so that NaN fails every comparison.
  if (!(std::isfinite(Wire.width()) && Wire.width() > 0.0F) ||
      !(std::isfinite(Wire.height()) && Wire.height() > 0.0F) ||
      !footprintFits(Wire.width(), Wire.height()) ||
      !(Wire.max_up_step() >= 0.0F && Wire.max_up_step() <= UpStepLimit))
    return std::nullopt;

  Profile Out;
  Out.ClassId = Wire.class_id();
  Out.Width = Wire.width();
  Out.Height = Wire.height();
  Out.MaxUpStep = Wire.max_up_step();
  for (std::size_t I = 0; I < Out.Malus.size(); ++I)
    Out.Malus[I] = Malus->Get(static_cast<flatbuffers::uoffset_t>(I));
  Out.CanPassDoors = Wire.can_pass_doors();
  Out.CanOpenDoors = Wire.can_open_doors();
  Out.CanFloat = Wire.can_float();
  Out.CanWalkOverFences = Wire.can_walk_over_fences();
  return Out;
}

} // namespace maestro::search
