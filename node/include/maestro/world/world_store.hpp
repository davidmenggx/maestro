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
/// `apply` belongs to the one thread that consumes the world stream. `pin` may
/// be called from any thread.
class WorldStore {
public:
  /// Applies one encoded `WorldFrame`. Anything but `Applied` drops everything
  /// held: the controller sent a frame this node cannot apply, so the two no
  /// longer agree on the stream, and starting over is the only state both can
  /// trust. The caller then reconnects, its `Hello` reports nothing held, and
  /// the controller sends the whole area again.
  ApplyStatus apply(std::span<const std::uint8_t> Frame);

  /// The current version, or null when nothing is held. What `Hello` reports
  /// (`run`, `regionId` and `sequence`) comes from one pinned version, so the
  /// three always describe the same stream position.
  std::shared_ptr<const Area> pin() const { return Current.load(); }

private:
  ApplyStatus applyFrame(std::span<const std::uint8_t> Frame);
  ApplyStatus assign(const fbs::RegionAssignment &Assignment);
  ApplyStatus applyChunk(const fbs::ChunkSnapshot &Snapshot);
  ApplyStatus applyDelta(const fbs::WorldDelta &Delta);

  /// Whether `Sequence` may follow what is held.
  ApplyStatus admit(std::uint64_t Sequence) const;

  std::atomic<std::shared_ptr<const Area>> Current;
  /// Set by a fresh assignment: the next frame's sequence is the baseline.
  bool AwaitingBaseline = false;
};

} // namespace maestro::world
