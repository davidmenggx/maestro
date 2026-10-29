#include "test_frames.hpp"

#include <optional>

#include "maestro/search/profile.hpp"

namespace maestro::test {

namespace {

Bytes finish(flatbuffers::FlatBufferBuilder &Builder) {
  return {Builder.GetBufferPointer(),
          Builder.GetBufferPointer() + Builder.GetSize()};
}

} // namespace

Bytes joinFrame(serve::Port On, std::uint64_t Run, std::uint64_t Link) {
  flatbuffers::FlatBufferBuilder Builder;
  const auto Body = fbs::CreateJoin(Builder, Run, Link);
  if (On == serve::Port::World)
    Builder.Finish(
        fbs::CreateWorldFrame(Builder, fbs::WorldMsg::Join, Body.Union()));
  else
    Builder.Finish(
        fbs::CreateControlFrame(Builder, fbs::ControlMsg::Join, Body.Union()));
  return finish(Builder);
}

Bytes mobClassFrame(std::uint16_t ClassId) {
  const search::Profile Defaults;
  flatbuffers::FlatBufferBuilder Builder;
  const auto Malus =
      Builder.CreateVector(Defaults.Malus.data(), Defaults.Malus.size());
  const auto Body = fbs::CreateMobClass(
      Builder, ClassId, Defaults.Width, Defaults.Height, Malus,
      Defaults.MaxUpStep, Defaults.CanPassDoors, Defaults.CanOpenDoors,
      Defaults.CanFloat, Defaults.CanWalkOverFences);
  Builder.Finish(fbs::CreateControlFrame(Builder, fbs::ControlMsg::MobClass,
                                         Body.Union()));
  return finish(Builder);
}

Bytes requestFrame(const std::vector<std::uint64_t> &Ids, std::uint16_t ClassId,
                   std::uint64_t Run, std::uint32_t RegionId) {
  flatbuffers::FlatBufferBuilder Builder;
  std::vector<flatbuffers::Offset<fbs::PathRequest>> Requests;
  for (const std::uint64_t Id : Ids) {
    const fbs::Vec3s Start(2, 4, 2);
    const fbs::Vec3s Target(20, 4, 20);
    Requests.push_back(fbs::CreatePathRequest(Builder, Id, ClassId, &Start,
                                              &Target, 0, 40.0F, 400, 3));
  }
  const auto Body =
      fbs::CreatePathRequestBatch(Builder, Builder.CreateVector(Requests));
  Builder.Finish(fbs::CreateRequestFrame(
      Builder, fbs::RequestMsg::PathRequestBatch, Body.Union(), Run, RegionId));
  return finish(Builder);
}

Bytes requestFrame(const fbs::PathRequest &Wire, std::uint64_t Id,
                   std::uint64_t Run, std::uint32_t RegionId) {
  flatbuffers::FlatBufferBuilder Builder;
  const auto Request = fbs::CreatePathRequest(
      Builder, Id, Wire.class_id(), Wire.start(), Wire.target(),
      Wire.reach_range(), Wire.max_path_length(), Wire.max_visited_nodes(),
      Wire.max_fall_distance());
  const auto Body =
      fbs::CreatePathRequestBatch(Builder, Builder.CreateVector(&Request, 1));
  Builder.Finish(fbs::CreateRequestFrame(
      Builder, fbs::RequestMsg::PathRequestBatch, Body.Union(), Run, RegionId));
  return finish(Builder);
}

Bytes cancelFrame(const std::vector<std::uint64_t> &Ids, std::uint64_t Run,
                  std::uint32_t RegionId) {
  flatbuffers::FlatBufferBuilder Builder;
  const auto Body = fbs::CreatePathCancel(Builder, Builder.CreateVector(Ids));
  Builder.Finish(fbs::CreateRequestFrame(Builder, fbs::RequestMsg::PathCancel,
                                         Body.Union(), Run, RegionId));
  return finish(Builder);
}

Bytes probeFrame(std::uint64_t Id, std::uint64_t Run) {
  flatbuffers::FlatBufferBuilder Builder;
  const auto Body = fbs::CreatePathProbe(Builder, Id);
  Builder.Finish(fbs::CreateRequestFrame(Builder, fbs::RequestMsg::PathProbe,
                                         Body.Union(), Run, 0));
  return finish(Builder);
}

std::size_t serveWaiting(serve::NodeCore &Core, serve::Scratch &With,
                         std::size_t Count) {
  std::size_t Ran = 0;
  while (Count == 0 || Ran < Count) {
    const std::optional<serve::Job> Next = Core.queue().tryTake();
    if (!Next)
      break;
    const serve::Outcome Done = Core.run(*Next, With);
    if (Done.Expired)
      continue;
    ++Ran;
    if (Done.Result)
      Core.deliver(*Next, Done.Result);
  }
  return Ran;
}

} // namespace maestro::test
