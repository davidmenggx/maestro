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
  /// Failed verification or broke the frame contract.
  Malformed,
  /// A state frame arrived before any assignment.
  Unassigned,
  /// Not the frame after the last one applied.
  OutOfSequence,
  /// Asked to resume a stream this node does not hold.
  CannotResume,
};

/// The node's view of its assigned area, built from the ordered world stream.
/// Each applied frame publishes a new immutable `Area`. Readers pin a version,
/// so they never block on or see a half-applied frame.
///
/// `apply` belongs to the one thread that consumes the world stream. `pin` may
/// be called from any thread.
class WorldStore {
public:
  /// Applies one encoded `WorldFrame`. Any result but `Applied` drops all held
  /// state, because the node and controller no longer agree on the stream.
  /// The caller then reconnects, its `Hello` reports nothing held, and the
  /// controller sends the whole area again.
  ApplyStatus apply(std::span<const std::uint8_t> Frame);

  /// The current version, or null if nothing is held. `Hello` reports `run`,
  /// `regionId` and `sequence` from one pinned version, so they always
  /// describe the same stream position.
  std::shared_ptr<const Area> pin() const { return Current.load(); }

private:
  ApplyStatus applyFrame(std::span<const std::uint8_t> Frame);
  ApplyStatus assign(const fbs::RegionAssignment &Assignment);
  ApplyStatus applyChunk(const fbs::ChunkSnapshot &Snapshot);
  ApplyStatus applyDelta(const fbs::WorldDelta &Delta);

  /// Whether `Sequence` may follow what is held.
  ApplyStatus admit(std::uint64_t Sequence) const;

  std::atomic<std::shared_ptr<const Area>> Current;
};

} // namespace maestro::world
