#include "maestro/serve/node_core.hpp"

#include <iostream>
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
  if (!Refused)
    return;
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
  Answer Cached;
  const Admission Admitted = Queue.admit({.RequestId = Wire.request_id(),
                                          .Mob = Found->second,
                                          .Call = *Call,
                                          .ReplyTo = From,
                                          .Run = Run,
                                          .RegionId = RegionId},
                                         At, Cached);
  if (Admitted == Admission::Answered)
    Out.sendTo(From, *Cached);
}

void NodeCore::heartbeat() {
  if (!Live || !joined(Port::Control))
    return;
  Builder.Clear();
  const auto Body =
      fbs::CreateHeartbeat(Builder, static_cast<std::uint32_t>(Queue.depth()));
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
  if (const Clock::time_point Taken = Now();
      Taken - Next.Admitted > Opts.MaxWait) {
    Queue.settle(Next, nullptr, Taken);
    return {};
  }
  const auto Held = Store.pin();
  std::uint64_t Sequence = 0;
  if (Held && Held->run() == Next.Run && Held->regionId() == Next.RegionId) {
    With.Search->find(*Held, *Next.Mob, Next.Call, With.Path);
    Sequence = Held->sequence();
  } else {
    // Sequence 0 reads as a search that ran too early.
    With.Path.Nodes.clear();
    With.Path.Reached = false;
    With.Path.Cost = 0.0F;
  }
  Answer Result;
  if (search::encodeResponse(With.Builder, Next.Run, Next.RequestId, With.Path,
                             Sequence)) {
    Result =
        answerOf({With.Builder.GetBufferPointer(), With.Builder.GetSize()});
    if (!Result)
      // One write, so lines from several workers do not interleave.
      std::clog << "maestro-node: the answer to request " +
                       std::to_string(Next.RequestId) + " is " +
                       std::to_string(With.Builder.GetSize()) +
                       " bytes, too large for a datagram, so it was dropped\n";
  }
  if (!Queue.settle(Next, Result, Now()))
    return {.Searched = true};
  return {.Searched = true, .Result = std::move(Result)};
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
