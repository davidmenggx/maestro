#include "maestro/search/profile.hpp"

#include "mob_generated.h"

namespace maestro::search {

std::optional<Profile> Profile::decode(const fbs::MobClass &Wire) {
  const auto *Malus = Wire.malus();
  if (!Malus || Malus->size() != world::PathTypeCount)
    return std::nullopt;
  // Written so that NaN fails every comparison.
  if (!(Wire.width() > 0.0F && Wire.width() <= WidthLimit) ||
      !(Wire.height() > 0.0F && Wire.height() <= HeightLimit) ||
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
