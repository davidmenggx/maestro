#include "maestro/serve/node_core.hpp"

#include <algorithm>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "control_generated.h"

namespace maestro::serve {

namespace {

/// Why the store refused a frame.
const char *describe(world::ApplyStatus Status) {
  switch (Status) {
  case world::ApplyStatus::Malformed:
    return "malformed";
  case world::ApplyStatus::Unassigned:
    return "before any assignment";
  case world::ApplyStatus::OutOfSequence:
    return "out of sequence";
  case world::ApplyStatus::CannotResume:
    return "resume of a stream not held";
  case world::ApplyStatus::CannotKeep:
    return "a kept area of a stream not held";
  case world::ApplyStatus::Behind:
    return "a fresh assignment behind the stream held";
  default:
    return "refused";
  }
}

template <typename T> const T *verified(std::span<const std::uint8_t> Frame) {
  flatbuffers::Verifier Verifier(Frame.data(), Frame.size());
  if (!Verifier.VerifyBuffer<T>(nullptr))
    return nullptr;
  return flatbuffers::GetRoot<T>(Frame.data());
}

/// The `Join` that `Frame` holds, if it is a `Root` holding one.
template <typename Root>
const fbs::Join *joinIn(std::span<const std::uint8_t> Frame) {
  const Root *Verified = verified<Root>(Frame);
  return Verified ? Verified->body_as_Join() : nullptr;
}

/// `Span` in whole microseconds, clamped to what a `uint` field holds.
std::uint32_t wireMicros(Clock::duration Span) {
  const auto Micros =
      std::chrono::duration_cast<std::chrono::microseconds>(Span).count();
  return static_cast<std::uint32_t>(std::clamp<std::int64_t>(
      Micros, 0, std::numeric_limits<std::uint32_t>::max()));
}

std::unique_ptr<search::Search> checkedSearch(fbs::Pathfinder Kind) {
  auto Made = search::makeSearch(Kind);
  if (!Made)
    throw std::invalid_argument("this build has no such search");
  return Made;
}

} // namespace

Scratch::Scratch(fbs::Pathfinder Kind) : Search(checkedSearch(Kind)) {}

Answer answerOf(std::span<const std::uint8_t> Frame) {
  if (Frame.size() > MaxAnswerSize)
    return nullptr;
  return std::make_shared<const std::vector<std::uint8_t>>(Frame.begin(),
                                                           Frame.end());
}

NodeCore::NodeCore(const NodeOptions &Opts, TimeSource Now, Output &Out)
    : Opts(Opts), Now(std::move(Now)), Out(Out), Queue(Opts.Retention) {
  // Each worker makes its own search, so a missing one is reported here.
  checkedSearch(Opts.Search);
}

void NodeCore::connected(Port On, ConnectionId Id) {
  Waiting.insert_or_assign(Id, Pending{On, Now()});
}

void NodeCore::received(ConnectionId From,
                        std::span<const std::uint8_t> Frame) {
  if (const auto Found = Waiting.find(From); Found != Waiting.end()) {
    const Port On = Found->second.On;
    Waiting.erase(Found);
    const fbs::Join *Body = On == Port::World
                                ? joinIn<fbs::WorldFrame>(Frame)
                                : joinIn<fbs::ControlFrame>(Frame);
    if (!Body || Body->run() == 0 || Body->link() == 0)
      return Out.close(From);
    return join(On, From, Token{Body->run(), Body->link()});
  }
  if (From == joined(Port::World))
    return onWorldFrame(Frame);
  if (From == joined(Port::Control))
    return onControlFrame(Frame);
}

void NodeCore::disconnected(ConnectionId Id) {
  if (Waiting.erase(Id) > 0)
    return;
  for (const Port On : {Port::World, Port::Control}) {
    if (Id != joined(On))
      continue;
    joined(On).reset();
    return dropLink(On == Port::World ? "the world connection closed"
                                      : "the control connection closed");
  }
}

void NodeCore::join(Port On, ConnectionId Id, Token Named) {
  if (Named.Run != Current.Run || Named.Link > Current.Link) {
    if (Live)
      dropLink("a later link replaced it");
    Current = Named;
    Live = true;
  } else if (Named != Current || !Live || joined(On)) {
    return Out.close(Id);
  }
  joined(On) = Id;
  if (On == Port::Control)
    sendHello(Id);
}

void NodeCore::onWorldFrame(std::span<const std::uint8_t> Frame) {
  const auto *Root = verified<fbs::WorldFrame>(Frame);
  // A `Join` after the first frame is not part of the stream.
  if (Root && Root->body_type() == fbs::WorldMsg::Join)
    return;
  const char *Refused = nullptr;
  if (!Root) {
    Refused = describe(world::ApplyStatus::Malformed);
  } else if (const auto *Assignment = Root->body_as_RegionAssignment();
             Assignment && Assignment->run() != Current.Run) {
    Refused = "an assignment of another run than the link's";
  } else if (const world::ApplyStatus Status = Store.apply(*Root);
             Status != world::ApplyStatus::Applied) {
    Refused = describe(Status);
  }
  if (!Refused) {
    if (const auto *Assigned = Root->body_as_RegionAssignment()) {
      const std::string Region = std::to_string(Assigned->region_id());
      const std::string Sequence = std::to_string(Assigned->sequence());
      if (Assigned->from_region_id() != 0)
        std::clog << "maestro-node: kept region " +
                         std::to_string(Assigned->from_region_id()) +
                         " as region " + Region + " at sequence " + Sequence +
                         "\n";
      else if (Assigned->resume())
        std::clog << "maestro-node: resumed region " + Region +
                         " at sequence " + Sequence + "\n";
      else
        std::clog << "maestro-node: took region " + Region +
                         " afresh at sequence " + Sequence + "\n";
    }
    return;
  }
  Store.clear();
  dropLink(std::string("refused a world frame: ") + Refused +
           ", so all world state was dropped");
}

void NodeCore::onControlFrame(std::span<const std::uint8_t> Frame) {
  const auto *Root = verified<fbs::ControlFrame>(Frame);
  if (!Root)
    return dropLink("a malformed control frame");
  switch (Root->body_type()) {
  case fbs::ControlMsg::MobClass: {
    const auto *Body = Root->body_as_MobClass();
    std::optional<search::Profile> Profile =
        Body ? search::Profile::decode(*Body) : std::nullopt;
    if (!Profile)
      return dropLink("a profile that breaks the contract");
    const std::uint16_t ClassId = Profile->ClassId;
    Profiles.insert_or_assign(
        ClassId, std::make_shared<const search::Profile>(*Profile));
    return;
  }
  case fbs::ControlMsg::Join:
    // Only a connection's first frame joins it.
    return;
  default:
    // A message this build does not know, which a newer controller may send.
    return;
  }
}

void NodeCore::datagram(std::span<const std::uint8_t> Frame,
                        const asio::ip::udp::endpoint &From) {
  const auto *Root = verified<fbs::RequestFrame>(Frame);
  if (!Root || !Live || Root->run() != Current.Run)
    return;
  const Clock::time_point At = Now();
  switch (Root->body_type()) {
  case fbs::RequestMsg::PathRequestBatch:
    if (const auto *Batch = Root->body_as_PathRequestBatch();
        Batch && Batch->requests())
      for (const fbs::PathRequest *Wire : *Batch->requests())
        admit(*Wire, From, Root->run(), Root->region_id(), At);
    return;
  case fbs::RequestMsg::PathCancel:
    if (const auto *Cancel = Root->body_as_PathCancel();
        Cancel && Cancel->request_ids())
      for (const std::uint64_t RequestId : *Cancel->request_ids())
        Queue.cancel(RequestId, At);
    return;
  case fbs::RequestMsg::PathProbe:
    // No profile and region 0, so a worker answers it with no path.
    if (const auto *Probe = Root->body_as_PathProbe())
      enqueue({.RequestId = Probe->request_id(),
               .Mob = nullptr,
               .Call = {},
               .ReplyTo = From,
               .Run = Root->run(),
               .RegionId = 0},
              At);
    return;
  default:
    return;
  }
}

void NodeCore::admit(const fbs::PathRequest &Wire,
                     const asio::ip::udp::endpoint &From, std::uint64_t Run,
                     std::uint32_t RegionId, Clock::time_point At) {
  const auto Found = Profiles.find(Wire.class_id());
  if (Found == Profiles.end()) {
    search::encodeUnknownProfile(Builder, Run, Wire.request_id(),
                                 Wire.class_id());
    Out.sendTo(From, {Builder.GetBufferPointer(), Builder.GetSize()});
    return;
  }
  std::optional<search::Request> Call = search::Request::decode(Wire);
  if (!Call)
    return;
  enqueue({.RequestId = Wire.request_id(),
           .Mob = Found->second,
           .Call = *Call,
           .ReplyTo = From,
           .Run = Run,
           .RegionId = RegionId},
          At);
}

void NodeCore::enqueue(Job Next, Clock::time_point At) {
  const asio::ip::udp::endpoint ReplyTo = Next.ReplyTo;
  Answer Cached;
  if (Queue.admit(std::move(Next), At, Cached) == Admission::Answered)
    Out.sendTo(ReplyTo, *Cached);
}

void NodeCore::heartbeat() {
  // Taken even with no link to report to, so a beat covers one interval.
  const std::uint64_t Finished = BeatSearches.exchange(0);
  const std::uint64_t Micros = BeatMicros.exchange(0);
  if (!Live || !joined(Port::Control))
    return;
  constexpr std::uint64_t MaxSearches =
      std::numeric_limits<std::uint32_t>::max();
  constexpr unsigned MaxWorkers = std::numeric_limits<std::uint16_t>::max();
  const auto Held = Store.pin();
  const bool Holds = Held && Held->run() == Current.Run;
  Builder.Clear();
  const auto Body = fbs::CreateHeartbeat(
      Builder, static_cast<std::uint32_t>(Queue.depth()),
      static_cast<std::uint32_t>(std::min(Finished, MaxSearches)), Micros,
      static_cast<std::uint16_t>(std::min(Opts.Workers, MaxWorkers)),
      Holds ? Held->regionId() : 0, Holds ? Held->sequence() : 0);
  Builder.Finish(fbs::CreateControlReplyFrame(
      Builder, fbs::ControlReplyMsg::Heartbeat, Body.Union()));
  Out.send(*joined(Port::Control),
           {Builder.GetBufferPointer(), Builder.GetSize()});
}

void NodeCore::expirePending() {
  const Clock::time_point At = Now();
  for (auto It = Waiting.begin(); It != Waiting.end();) {
    if (At - It->second.Since < Opts.JoinTimeout) {
      ++It;
      continue;
    }
    const ConnectionId Silent = It->first;
    It = Waiting.erase(It);
    Out.close(Silent);
  }
}

Outcome NodeCore::run(const Job &Next, Scratch &With) {
  const Clock::time_point Taken = Now();
  if (Taken - Next.Admitted > Opts.MaxWait) {
    Queue.settle(Next, nullptr, Taken);
    return {.Expired = true};
  }
  const auto Held = Store.pin();
  std::uint64_t Sequence = 0;
  // A probe has no profile, so it never searches.
  const bool Searched = Next.Mob && Held && Held->run() == Next.Run &&
                        Held->regionId() == Next.RegionId;
  if (Searched) {
    With.Search->find(*Held, *Next.Mob, Next.Call, With.Path);
    Sequence = Held->sequence();
    ++Searches;
  } else {
    // Sequence 0 reads as a search that ran too early.
    With.Path.Nodes.clear();
    With.Path.Reached = false;
    With.Path.Cost = 0.0F;
  }
  const search::Timing Took{.WaitMicros = wireMicros(Taken - Next.Admitted),
                            .SearchMicros =
                                Searched ? wireMicros(Now() - Taken) : 0};
  Answer Result;
  if (search::encodeResponse(With.Builder, Next.Run, Next.RequestId, With.Path,
                             Sequence, Took)) {
    Result =
        answerOf({With.Builder.GetBufferPointer(), With.Builder.GetSize()});
    if (!Result)
      // One write, so lines from several workers do not interleave.
      std::clog << "maestro-node: the answer to request " +
                       std::to_string(Next.RequestId) + " is " +
                       std::to_string(With.Builder.GetSize()) +
                       " bytes, too large for a datagram, so it was dropped\n";
  }
  const Clock::time_point Settled = Now();
  const bool Deliver = Queue.settle(Next, Result, Settled);
  if (Searched) {
    const auto Worked =
        std::chrono::duration_cast<std::chrono::microseconds>(Settled - Taken);
    BeatMicros +=
        static_cast<std::uint64_t>(std::max<std::int64_t>(0, Worked.count()));
    ++BeatSearches;
  }
  if (!Deliver)
    return {};
  return {.Result = std::move(Result)};
}

void NodeCore::deliver(const Job &Finished, const Answer &Result) {
  if (Queue.current(Finished))
    Out.sendTo(Finished.ReplyTo, *Result);
}

void NodeCore::sendHello(ConnectionId To) {
  // Sequence 0 means nothing held, whatever area the node was assigned.
  const auto Held = Store.pin();
  const bool Holds = Held && Held->sequence() > 0;
  Builder.Clear();
  const auto Body = fbs::CreateHello(Builder, Holds ? Held->run() : 0,
                                     Holds ? Held->regionId() : 0,
                                     Holds ? Held->sequence() : 0, Opts.Search);
  Builder.Finish(fbs::CreateControlReplyFrame(
      Builder, fbs::ControlReplyMsg::Hello, Body.Union()));
  Out.send(To, {Builder.GetBufferPointer(), Builder.GetSize()});
}

void NodeCore::dropLink(std::string_view Reason) {
  for (std::optional<ConnectionId> &Connection : Joined)
    if (Connection) {
      Out.close(*Connection);
      Connection.reset();
    }
  Live = false;
  Profiles.clear();
  Queue.clear();
  Out.linkDropped(Reason);
}

} // namespace maestro::serve
