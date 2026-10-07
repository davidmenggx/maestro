#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <span>

#include "maestro/world/area.hpp"

namespace maestro::fbs {
struct ChunkSnapshot;
struct RegionAssignment;
struct WorldDelta;
} // namespace maestro::fbs

namespace maestro::world {

enum class ApplyStatus {
  Applied,
  /// Failed verification, or broke the frame contract.
  Malformed,
  /// A state frame arrived before any assignment.
  Unassigned,
  /// Not the frame after the last one applied.
  OutOfSequence,
  /// Told to resume a stream this node does not hold.
  CannotResume,
};

/// The node's view of its assigned area, built from the ordered world stream.
/// Each applied frame publishes a new immutable `Area`, so readers pin a
/// version and are never blocked by, or exposed to, a frame being applied.
///
/// `apply` and the accessors other than `pin` belong to the one thread that
/// consumes the world stream. `pin` may be called from any thread.
class WorldStore {
public:
  /// Applies one encoded `WorldFrame`. Anything but `Applied` leaves the held
  /// state unchanged.
  ApplyStatus apply(std::span<const std::uint8_t> Frame);

  /// The current version, or null before the first assignment.
  std::shared_ptr<const Area> pin() const { return Current.load(); }

  /// Controller run the held area belongs to, as `Hello` reports it.
  std::uint64_t run() const { return Run; }

  std::uint32_t regionId() const { return RegionId; }

  /// Sequence of the last frame applied, 0 when nothing is held.
  std::uint64_t lastSequence() const;

private:
  ApplyStatus assign(const fbs::RegionAssignment &Assignment);
  ApplyStatus applyChunk(const fbs::ChunkSnapshot &Snapshot);
  ApplyStatus applyDelta(const fbs::WorldDelta &Delta);

  /// Whether `Sequence` may follow what is held.
  ApplyStatus admit(std::uint64_t Sequence) const;

  std::atomic<std::shared_ptr<const Area>> Current;
  std::uint64_t Run = 0;
  std::uint32_t RegionId = 0;
  /// Set by a fresh assignment: the next frame's sequence is the baseline.
  bool AwaitingBaseline = false;
};

} // namespace maestro::world
