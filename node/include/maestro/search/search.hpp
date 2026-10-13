#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

#include "maestro/search/profile.hpp"
#include "pathfinding_generated.h"

namespace maestro::search {

/// A block position in the area's frame: `X` and `Z` relative to its corner,
/// `Y` absolute.
struct BlockPos {
  int X = 0;
  int Y = 0;
  int Z = 0;

  friend bool operator==(BlockPos, BlockPos) = default;
};

/// The inputs of one `PathFinder.findPath` call that vary per call.
struct Request {
  /// The start node, resolved on the server. Also used as the mob's
  /// position, which the node is not sent.
  BlockPos Start;
  BlockPos Target;
  /// Manhattan distance from `Target` that counts as arrival.
  int ReachRange = 0;
  float MaxPathLength = 16.0F;
  int MaxVisitedNodes = 256;
  int MaxFallDistance = 3;

  /// The request `Wire` describes, or nothing if it lacks a start or target.
  static std::optional<Request> decode(const fbs::PathRequest &Wire);
};

/// One node of a path, with the type the navigation reads while following it.
struct PathPoint {
  BlockPos Pos;
  world::PathType Type = world::PathType::BLOCKED;

  friend bool operator==(PathPoint, PathPoint) = default;
};

/// What `PathFinder.findPath` returns: the nodes from the start to the node
/// nearest the target, whether that node is within reach of the target, and
/// the A* cost of reaching it.
struct Result {
  std::vector<PathPoint> Nodes;
  bool Reached = false;
  float Cost = 0.0F;
};

/// Encodes `Path` as a finished `ResponseFrame` in `Builder`, which is cleared
/// first so one builder can be reused. Returns false, with nothing encoded, if
/// a position does not fit the wire's 16 bits, which cannot happen for a
/// request the controller admitted.
[[nodiscard]] bool encodeResponse(flatbuffers::FlatBufferBuilder &Builder,
                                  std::uint64_t RequestId, const Result &Path,
                                  std::uint64_t WorldSequence);

/// Encodes the answer to a request that names a profile this node has not
/// received, as a finished `ResponseFrame` in `Builder`, which is cleared
/// first.
void encodeUnknownProfile(flatbuffers::FlatBufferBuilder &Builder,
                          std::uint64_t RequestId, std::uint16_t ClassId);

/// One pathfinding algorithm. A node picks one at startup and reports it in
/// `Hello`. An instance reuses scratch state between calls to avoid
/// allocating, so each thread needs its own.
class Search {
public:
  virtual ~Search() = default;

  virtual fbs::Pathfinder kind() const = 0;

  /// Searches `Area`, which the caller keeps pinned until the call returns,
  /// and overwrites `Out`.
  virtual void find(const world::Area &Area, const Profile &Mob,
                    const Request &Call, Result &Out) = 0;
};

/// The search named by `Kind`, or null if this build does not have it.
std::unique_ptr<Search> makeSearch(fbs::Pathfinder Kind);

} // namespace maestro::search
