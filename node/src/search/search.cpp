#include "maestro/search/search.hpp"

#include "control_generated.h"
#include "maestro/search/vanilla_search.hpp"

namespace maestro::search {

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
  std::vector<fbs::PathNode> Nodes;
  Nodes.reserve(Path.Nodes.size());
  for (const PathPoint &Point : Path.Nodes)
    Nodes.emplace_back(fbs::Vec3s(static_cast<std::int16_t>(Point.Pos.X),
                                  static_cast<std::int16_t>(Point.Pos.Y),
                                  static_cast<std::int16_t>(Point.Pos.Z)),
                       Point.Type);
  const auto NodesVec = Builder.CreateVectorOfStructs(Nodes);
  const auto Response = fbs::CreatePathResponse(
      Builder, RequestId, Path.Reached, NodesVec, Path.Cost, WorldSequence);
  Builder.Finish(fbs::CreateResponseFrame(
      Builder, fbs::ResponseMsg::PathResponse, Response.Union()));
}

std::unique_ptr<Search> makeSearch(fbs::Pathfinder Kind) {
  switch (Kind) {
  case fbs::Pathfinder::VANILLA:
    return std::make_unique<VanillaSearch>();
  }
  return nullptr;
}

} // namespace maestro::search
