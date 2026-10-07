#include "maestro/search/search.hpp"

#include <cassert>
#include <limits>

#include "control_generated.h"
#include "maestro/search/vanilla_search.hpp"

namespace maestro::search {

namespace {

std::int16_t toWire(int Coordinate) {
  assert(Coordinate >= std::numeric_limits<std::int16_t>::min() &&
         Coordinate <= std::numeric_limits<std::int16_t>::max() &&
         "position does not fit the wire");
  return static_cast<std::int16_t>(Coordinate);
}

} // namespace

std::optional<Request> Request::decode(const fbs::PathRequest &Wire) {
  if (!Wire.start() || !Wire.target())
    return std::nullopt;
  Request Out;
  Out.Start = {Wire.start()->x(), Wire.start()->y(), Wire.start()->z()};
  Out.Target = {Wire.target()->x(), Wire.target()->y(), Wire.target()->z()};
  Out.ReachRange = Wire.reach_range();
  Out.MaxPathLength = Wire.max_path_length();
  Out.MaxVisitedNodes = Wire.max_visited_nodes();
  Out.MaxFallDistance = Wire.max_fall_distance();
  return Out;
}

void encodeResponse(flatbuffers::FlatBufferBuilder &Builder,
                    std::uint64_t RequestId, const Result &Path,
                    std::uint64_t WorldSequence) {
  Builder.Clear();
  fbs::PathNode *Nodes = nullptr;
  const auto NodesVec =
      Builder.CreateUninitializedVectorOfStructs(Path.Nodes.size(), &Nodes);
  for (const PathPoint &Point : Path.Nodes)
    *Nodes++ =
        fbs::PathNode(fbs::Vec3s(toWire(Point.Pos.X), toWire(Point.Pos.Y),
                                 toWire(Point.Pos.Z)),
                      Point.Type);
  const auto Response = fbs::CreatePathResponse(
      Builder, RequestId, Path.Reached, NodesVec, Path.Cost, WorldSequence);
  Builder.Finish(fbs::CreateResponseFrame(
      Builder, fbs::ResponseMsg::PathResponse, Response.Union()));
}

void encodeUnknownProfile(flatbuffers::FlatBufferBuilder &Builder,
                          std::uint64_t RequestId, std::uint16_t ClassId) {
  Builder.Clear();
  const auto Body = fbs::CreateUnknownProfile(Builder, RequestId, ClassId);
  Builder.Finish(fbs::CreateResponseFrame(
      Builder, fbs::ResponseMsg::UnknownProfile, Body.Union()));
}

std::unique_ptr<Search> makeSearch(fbs::Pathfinder Kind) {
  switch (Kind) {
  case fbs::Pathfinder::VANILLA:
    return std::make_unique<VanillaSearch>();
  }
  return nullptr;
}

} // namespace maestro::search
