#pragma once

#include <atomic>
#include <cstdint>
#include <memory>

#include "maestro/world/area.hpp"

namespace maestro::fbs {
struct ChunkSnapshot;
struct RegionAssignment;
struct WorldDelta;
struct WorldFrame;
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
  /// Asked to keep an area of a stream this node does not hold, or a larger
  /// one.
  CannotKeep,
  /// A fresh assignment of the stream held, behind the last frame applied.
  Behind,
};

/// The node's view of its assigned area, built from the ordered world stream.
/// Each applied frame publishes a new immutable `Area`. Readers pin a version,
/// so they never block on or see a half-applied frame.
///
/// `apply` and `clear` belong to the one thread that consumes the world
/// stream. `pin` may be called from any thread.
class WorldStore {
public:
  /// Applies `Frame`, which has passed verification. Any result but `Applied`
  /// drops all held state, since the node and controller disagree on the
  /// stream.
  ApplyStatus apply(const fbs::WorldFrame &Frame);

  /// Drops all held state.
  void clear() { Current.store(nullptr); }

  /// The current version, or null if nothing is held.
  std::shared_ptr<const Area> pin() const { return Current.load(); }

private:
  ApplyStatus applyFrame(const fbs::WorldFrame &Frame);
  ApplyStatus assign(const fbs::RegionAssignment &Assignment);
  ApplyStatus applyChunk(const fbs::ChunkSnapshot &Snapshot);
  ApplyStatus applyDelta(const fbs::WorldDelta &Delta);

  std::atomic<std::shared_ptr<const Area>> Current;
};

} // namespace maestro::world
