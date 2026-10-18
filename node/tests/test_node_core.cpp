#include "maestro/serve/node_core.hpp"

#include <chrono>
#include <cstdint>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include <gtest/gtest.h>

#include "control_generated.h"
#include "test_frames.hpp"
#include "test_world.hpp"

using namespace maestro;
using namespace maestro::test;
using serve::ConnectionId;
using serve::Port;
using std::chrono::milliseconds;

namespace {

/// Everything the node did, in order.
class Recorder final : public serve::NodeCore::Output {
public:
  struct Event {
    enum Kind { Send, Close, SendTo, Drop } What;
    ConnectionId Connection = 0;
    Bytes Frame;
  };

  void send(ConnectionId To, std::span<const std::uint8_t> Frame) override {
    Events.push_back({Event::Send, To, Bytes(Frame.begin(), Frame.end())});
  }
  void close(ConnectionId Which) override {
    Events.push_back({Event::Close, Which, {}});
  }
  void sendTo(const asio::ip::udp::endpoint &,
              std::span<const std::uint8_t> Datagram) override {
    Events.push_back(
        {Event::SendTo, 0, Bytes(Datagram.begin(), Datagram.end())});
  }
  void linkDropped(std::string_view) override {
    Events.push_back({Event::Drop, 0, {}});
  }

  /// What happened since the last call.
  std::vector<Event> take() { return std::exchange(Events, {}); }

private:
  std::vector<Event> Events;
};

using Event = Recorder::Event;

const fbs::Hello *helloIn(const Event &Sent) {
  const auto *Reply = root<fbs::ControlReplyFrame>(Sent.Frame);
  return Reply ? Reply->body_as_Hello() : nullptr;
}

const fbs::Heartbeat *heartbeatIn(const Event &Sent) {
  const auto *Reply = root<fbs::ControlReplyFrame>(Sent.Frame);
  return Reply ? Reply->body_as_Heartbeat() : nullptr;
}

const fbs::ResponseFrame *responseIn(const Event &Sent) {
  return root<fbs::ResponseFrame>(Sent.Frame);
}

/// A node with no workers, driven by hand at times the test sets.
class NodeCoreTest : public testing::Test {
protected:
  NodeCoreTest() : Core(serve::NodeOptions{}, [this] { return Time; }, Out) {}

  /// Opens `Id` on `On` with `First` as its first frame, and returns what the
  /// node did.
  std::vector<Event> connect(Port On, ConnectionId Id, const Bytes &First) {
    Core.connected(On, Id);
    Core.received(Id, First);
    return Out.take();
  }

  std::vector<Event> join(Port On, ConnectionId Id, std::uint64_t Link = 1,
                          std::uint64_t Run = TestRun) {
    return connect(On, Id, joinFrame(On, Run, Link));
  }

  std::vector<Event> receive(ConnectionId From, const Bytes &Frame) {
    Core.received(From, Frame);
    return Out.take();
  }

  std::vector<Event> datagram(const Bytes &Frame) {
    Core.datagram(Frame, {});
    return Out.take();
  }

  std::vector<Event> serve(std::size_t Count = 0) {
    serveWaiting(Core, With, Count);
    return Out.take();
  }

  std::vector<Event> heartbeat() {
    Core.heartbeat();
    return Out.take();
  }

  /// Joins link 1 of `TestRun` on world connection 1 and control connection
  /// 2, registers the test profile, and loads the flat world.
  void startLink() {
    ASSERT_TRUE(join(Port::World, WorldId).empty());
    ASSERT_EQ(join(Port::Control, ControlId).size(), 1u);
    ASSERT_TRUE(receive(ControlId, mobClassFrame()).empty());
    DenseWorld Flat(32, 0, 24);
    Flat.fill(0, 0, 0, 31, 3, 31, Stone);
    ASSERT_TRUE(
        receive(WorldId, Flat.assignment(false, TestRun, TestRegion)).empty());
    std::uint64_t Sequence = 1;
    for (int X = 0; X < 2; ++X)
      for (int Z = 0; Z < 2; ++Z)
        ASSERT_TRUE(receive(WorldId, Flat.chunk(Sequence++, X, Z)).empty());
  }

  /// Asks `Frame` and runs it, returning the one answer it gets.
  const fbs::PathResponse *answer(const Bytes &Frame) {
    EXPECT_TRUE(datagram(Frame).empty());
    Answers = serve();
    EXPECT_EQ(Answers.size(), 1u);
    if (Answers.size() != 1 || Answers[0].What != Event::SendTo)
      return nullptr;
    const auto *Response = responseIn(Answers[0]);
    EXPECT_NE(Response, nullptr);
    return Response ? Response->body_as_PathResponse() : nullptr;
  }

  static constexpr ConnectionId WorldId = 1;
  static constexpr ConnectionId ControlId = 2;

  serve::Clock::time_point Time{};
  Recorder Out;
  serve::NodeCore Core;
  serve::Scratch With{fbs::Pathfinder::VANILLA};
  std::vector<Event> Answers;
};

bool closes(const std::vector<Event> &Events, ConnectionId Id) {
  for (const Event &Done : Events)
    if (Done.What == Event::Close && Done.Connection == Id)
      return true;
  return false;
}

bool drops(const std::vector<Event> &Events) {
  for (const Event &Done : Events)
    if (Done.What == Event::Drop)
      return true;
  return false;
}

} // namespace

TEST_F(NodeCoreTest, AConnectionIsPendingUntilItsFirstFrame) {
  Core.connected(Port::Control, ControlId);
  Core.heartbeat();
  EXPECT_TRUE(Out.take().empty());

  const auto Done = receive(ControlId, joinFrame(Port::Control, TestRun, 1));
  ASSERT_EQ(Done.size(), 1u);
  EXPECT_EQ(Done[0].Connection, ControlId);
  ASSERT_NE(helloIn(Done[0]), nullptr);
  EXPECT_EQ(helloIn(Done[0])->last_sequence(), 0u);
}

TEST_F(NodeCoreTest, OnlyTheControlJoinIsGreeted) {
  EXPECT_TRUE(join(Port::World, WorldId).empty());
  const auto Done = join(Port::Control, ControlId);
  ASSERT_EQ(Done.size(), 1u);
  EXPECT_NE(helloIn(Done[0]), nullptr);
}

TEST_F(NodeCoreTest, AFirstFrameThatIsNotAJoinIsRefused) {
  startLink();
  DenseWorld Flat(32, 0, 24);
  const std::vector<std::pair<Port, Bytes>> Refused = {
      {Port::World, Flat.assignment(false, TestRun, TestRegion)},
      {Port::Control, mobClassFrame()},
      {Port::World, joinFrame(Port::Control, TestRun, 1)},
      {Port::Control, joinFrame(Port::World, TestRun, 1)},
      {Port::World, joinFrame(Port::World, 0, 1)},
      {Port::Control, joinFrame(Port::Control, TestRun, 0)},
      {Port::World, Bytes{1, 2, 3}},
  };
  ConnectionId Id = 10;
  for (const auto &[On, First] : Refused) {
    const auto Done = connect(On, Id, First);
    ASSERT_EQ(Done.size(), 1u) << Id;
    EXPECT_EQ(Done[0].What, Event::Close) << Id;
    EXPECT_EQ(Done[0].Connection, Id) << Id;
    ++Id;
  }
  // The live link is untouched.
  EXPECT_EQ(answer(requestFrame({1}))->world_sequence(), 4u);
  const auto Beat = heartbeat();
  ASSERT_EQ(Beat.size(), 1u);
  EXPECT_EQ(Beat[0].Connection, ControlId);
}

TEST_F(NodeCoreTest, AnEarlierLinkIsRefused) {
  ASSERT_EQ(join(Port::Control, ControlId, 2).size(), 1u);
  for (const Port On : {Port::World, Port::Control}) {
    const auto Done = join(On, 10, 1);
    ASSERT_EQ(Done.size(), 1u);
    EXPECT_TRUE(closes(Done, 10));
  }
  EXPECT_EQ(heartbeat().size(), 1u);
}

TEST_F(NodeCoreTest, ALaterLinkReplacesTheLinkAndForgetsItsProfiles) {
  startLink();
  ASSERT_TRUE(datagram(requestFrame({1})).empty());

  const auto Done = join(Port::Control, 10, 2);
  ASSERT_EQ(Done.size(), 4u);
  EXPECT_TRUE(closes(Done, WorldId));
  EXPECT_TRUE(closes(Done, ControlId));
  EXPECT_EQ(Done[2].What, Event::Drop);
  EXPECT_EQ(Done[3].Connection, 10u);
  ASSERT_NE(helloIn(Done[3]), nullptr);
  // The world stays.
  EXPECT_EQ(helloIn(Done[3])->last_sequence(), 4u);

  // The waiting request and the profile went with the old link.
  EXPECT_TRUE(serve().empty());
  const auto Refused = datagram(requestFrame({1}));
  ASSERT_EQ(Refused.size(), 1u);
  EXPECT_NE(responseIn(Refused[0])->body_as_UnknownProfile(), nullptr);
}

TEST_F(NodeCoreTest, AnotherRunReplacesTheLinkWhateverItsLink) {
  ASSERT_EQ(join(Port::Control, ControlId, 5).size(), 1u);
  const auto Done = join(Port::World, 10, 1, TestRun + 1);
  ASSERT_EQ(Done.size(), 2u);
  EXPECT_TRUE(closes(Done, ControlId));
  EXPECT_EQ(Done[1].What, Event::Drop);
  // The old run's requests are dropped, the new run's taken.
  EXPECT_TRUE(datagram(requestFrame({1})).empty());
  EXPECT_EQ(datagram(requestFrame({1}, TestClass, TestRun + 1)).size(), 1u);
}

TEST_F(NodeCoreTest, TheLinksTokenJoinsOncePerPort) {
  ASSERT_EQ(join(Port::Control, ControlId).size(), 1u);
  EXPECT_TRUE(join(Port::World, WorldId).empty());
  for (const Port On : {Port::World, Port::Control}) {
    const auto Done = join(On, 10);
    ASSERT_EQ(Done.size(), 1u);
    EXPECT_TRUE(closes(Done, 10));
  }
}

TEST_F(NodeCoreTest, ADroppedLinksTokenIsRefused) {
  ASSERT_TRUE(join(Port::World, WorldId).empty());
  ASSERT_EQ(join(Port::Control, ControlId).size(), 1u);
  Core.disconnected(WorldId);
  const auto Dropped = Out.take();
  ASSERT_EQ(Dropped.size(), 2u);
  EXPECT_TRUE(closes(Dropped, ControlId));
  EXPECT_EQ(Dropped[1].What, Event::Drop);

  const auto Late = join(Port::World, 10);
  ASSERT_EQ(Late.size(), 1u);
  EXPECT_TRUE(closes(Late, 10));
  EXPECT_TRUE(heartbeat().empty());
  EXPECT_TRUE(datagram(requestFrame({1})).empty());

  // The controller's next attempt joins.
  ASSERT_EQ(join(Port::Control, 11, 2).size(), 1u);
}

TEST_F(NodeCoreTest, LosingAPendingConnectionDoesNothing) {
  startLink();
  Core.connected(Port::World, 10);
  Core.disconnected(10);
  // A connection the node already forgot.
  Core.disconnected(99);
  EXPECT_TRUE(Out.take().empty());
  EXPECT_EQ(heartbeat().size(), 1u);
}

TEST_F(NodeCoreTest, LosingAJoinedConnectionDropsTheLinkOnce) {
  startLink();
  Core.disconnected(ControlId);
  const auto Done = Out.take();
  ASSERT_EQ(Done.size(), 2u);
  EXPECT_TRUE(closes(Done, WorldId));
  EXPECT_EQ(Done[1].What, Event::Drop);
  Core.disconnected(WorldId);
  EXPECT_TRUE(Out.take().empty());
}

TEST_F(NodeCoreTest, ASilentConnectionIsClosedAfterTheJoinTimeout) {
  startLink();
  Core.connected(Port::World, 10);
  Time += milliseconds(1000);
  Core.connected(Port::Control, 11);
  Time += milliseconds(3999);
  Core.expirePending();
  EXPECT_TRUE(Out.take().empty());

  Time += milliseconds(1);
  Core.expirePending();
  auto Done = Out.take();
  ASSERT_EQ(Done.size(), 1u);
  EXPECT_TRUE(closes(Done, 10));
  Time += milliseconds(1000);
  Core.expirePending();
  Done = Out.take();
  ASSERT_EQ(Done.size(), 1u);
  EXPECT_TRUE(closes(Done, 11));
  // The joined connections are not pending, however old.
  EXPECT_EQ(heartbeat().size(), 1u);
}

TEST_F(NodeCoreTest, HeartbeatsNeedALiveLinkWithItsControlConnection) {
  EXPECT_TRUE(heartbeat().empty());
  ASSERT_TRUE(join(Port::World, WorldId).empty());
  EXPECT_TRUE(heartbeat().empty());
  ASSERT_EQ(join(Port::Control, ControlId).size(), 1u);
  ASSERT_TRUE(receive(ControlId, mobClassFrame()).empty());
  ASSERT_TRUE(datagram(requestFrame({1, 2})).empty());
  const auto Beat = heartbeat();
  ASSERT_EQ(Beat.size(), 1u);
  EXPECT_EQ(Beat[0].Connection, ControlId);
  ASSERT_NE(heartbeatIn(Beat[0]), nullptr);
  EXPECT_EQ(heartbeatIn(Beat[0])->queue_depth(), 2u);

  Core.disconnected(WorldId);
  Out.take();
  EXPECT_TRUE(heartbeat().empty());
}

TEST_F(NodeCoreTest, ADatagramNeedsALiveLinkOfItsRun) {
  // No link, so not even an unknown profile is reported.
  EXPECT_TRUE(datagram(requestFrame({1})).empty());
  ASSERT_EQ(join(Port::Control, ControlId).size(), 1u);
  EXPECT_TRUE(datagram(requestFrame({2}, TestClass, TestRun + 1)).empty());

  const auto Refused = datagram(requestFrame({3}));
  ASSERT_EQ(Refused.size(), 1u);
  const auto *Response = responseIn(Refused[0]);
  ASSERT_NE(Response, nullptr);
  EXPECT_EQ(Response->run(), TestRun);
  EXPECT_EQ(Response->body_as_UnknownProfile()->request_id(), 3u);
}

TEST_F(NodeCoreTest, ACancelOfAnotherRunIsDropped) {
  startLink();
  ASSERT_TRUE(datagram(requestFrame({1})).empty());
  ASSERT_TRUE(datagram(cancelFrame({1}, TestRun + 1)).empty());
  EXPECT_EQ(Core.queue().depth(), 1u);
  ASSERT_TRUE(datagram(cancelFrame({1})).empty());
  EXPECT_EQ(Core.queue().depth(), 0u);
}

TEST_F(NodeCoreTest, AnAnswerNamesTheRequestsRunAndTheSequenceSearched) {
  startLink();
  const fbs::PathResponse *Answer = answer(requestFrame({1}));
  ASSERT_NE(Answer, nullptr);
  EXPECT_EQ(responseIn(Answers[0])->run(), TestRun);
  EXPECT_EQ(Answer->request_id(), 1u);
  EXPECT_EQ(Answer->world_sequence(), 4u);
  EXPECT_TRUE(Answer->reached());
}

TEST_F(NodeCoreTest, ARequestForAnotherAreaIsAnsweredAsIfNoneWereHeld) {
  startLink();
  const fbs::PathResponse *Answer =
      answer(requestFrame({1}, TestClass, TestRun, TestRegion + 1));
  ASSERT_NE(Answer, nullptr);
  EXPECT_EQ(Answer->world_sequence(), 0u);
  EXPECT_FALSE(Answer->reached());
  EXPECT_EQ(Answer->nodes()->size(), 0u);
  EXPECT_EQ(Answer->cost(), 0.0F);

  // The area of an earlier run, held into a link of a new one.
  ASSERT_TRUE(drops(join(Port::Control, 10, 1, TestRun + 1)));
  ASSERT_TRUE(receive(10, mobClassFrame()).empty());
  Answer = answer(requestFrame({2}, TestClass, TestRun + 1));
  ASSERT_NE(Answer, nullptr);
  EXPECT_EQ(Answer->world_sequence(), 0u);
  EXPECT_EQ(Answer->nodes()->size(), 0u);
}

TEST_F(NodeCoreTest, AJoinAfterTheFirstFrameIsIgnored) {
  startLink();
  EXPECT_TRUE(receive(WorldId, joinFrame(Port::World, TestRun, 1)).empty());
  EXPECT_TRUE(receive(WorldId, joinFrame(Port::World, TestRun, 2)).empty());
  EXPECT_TRUE(
      receive(ControlId, joinFrame(Port::Control, TestRun + 1, 1)).empty());
  ASSERT_NE(Core.store().pin(), nullptr);
  EXPECT_EQ(Core.store().pin()->sequence(), 4u);
  EXPECT_TRUE(receive(WorldId, clearedChunk(5, 0, 0)).empty());
  EXPECT_EQ(answer(requestFrame({1}))->world_sequence(), 5u);
}

TEST_F(NodeCoreTest, ARefusedWorldFrameDropsTheWorldAndTheLink) {
  startLink();
  const auto Done = receive(WorldId, clearedChunk(6, 0, 0));
  ASSERT_EQ(Done.size(), 3u);
  EXPECT_TRUE(closes(Done, WorldId));
  EXPECT_TRUE(closes(Done, ControlId));
  EXPECT_EQ(Done[2].What, Event::Drop);
  EXPECT_EQ(Core.store().pin(), nullptr);
}

TEST_F(NodeCoreTest, AMalformedWorldFrameDropsTheWorldAndTheLink) {
  startLink();
  const auto Done = receive(WorldId, Bytes{1, 2, 3, 4, 5, 6, 7, 8});
  ASSERT_EQ(Done.size(), 3u);
  EXPECT_TRUE(closes(Done, WorldId));
  EXPECT_TRUE(closes(Done, ControlId));
  EXPECT_EQ(Done[2].What, Event::Drop);
  EXPECT_EQ(Core.store().pin(), nullptr);
}

TEST_F(NodeCoreTest, AnAssignmentOfAnotherRunDropsTheWorldAndTheLink) {
  // The store alone would take a fresh assignment of another run.
  DenseWorld Flat(32, 0, 24);
  std::uint64_t Link = 0;
  for (const bool Resume : {false, true}) {
    for (const std::uint64_t Run : {TestRun + 1, std::uint64_t{0}}) {
      const ConnectionId World = 10 + ++Link;
      ASSERT_TRUE(join(Port::World, World, Link).empty());
      ASSERT_TRUE(
          receive(World, Flat.assignment(false, TestRun, TestRegion)).empty());
      ASSERT_TRUE(receive(World, Flat.chunk(1, 0, 0)).empty());

      const auto Done =
          receive(World, Flat.assignment(Resume, Run, TestRegion, 1));
      ASSERT_EQ(Done.size(), 2u) << Resume << " " << Run;
      EXPECT_TRUE(closes(Done, World));
      EXPECT_EQ(Done[1].What, Event::Drop);
      EXPECT_EQ(Core.store().pin(), nullptr);
    }
  }
}

TEST_F(NodeCoreTest, AFreshAssignmentBehindTheStreamHeldDropsTheLink) {
  startLink();
  DenseWorld Flat(32, 0, 24);
  EXPECT_TRUE(
      drops(receive(WorldId, Flat.assignment(false, TestRun, TestRegion, 3))));
  EXPECT_EQ(Core.store().pin(), nullptr);
}

TEST_F(NodeCoreTest, AMalformedControlFrameDropsTheLink) {
  startLink();
  EXPECT_TRUE(drops(receive(ControlId, Bytes{1, 2, 3, 4, 5, 6, 7, 8})));
}

TEST_F(NodeCoreTest, ServesTheOldestWaitingRequestsAndSkipsCanceledOnes) {
  startLink();
  ASSERT_TRUE(datagram(requestFrame({1, 2, 3, 4})).empty());
  ASSERT_TRUE(datagram(cancelFrame({1})).empty());
  auto Done = serve(2);
  ASSERT_EQ(Done.size(), 2u);
  EXPECT_EQ(responseIn(Done[0])->body_as_PathResponse()->request_id(), 2u);
  EXPECT_EQ(responseIn(Done[1])->body_as_PathResponse()->request_id(), 3u);
  Done = serve();
  ASSERT_EQ(Done.size(), 1u);
  EXPECT_EQ(responseIn(Done[0])->body_as_PathResponse()->request_id(), 4u);
  EXPECT_TRUE(serve().empty());
}

TEST_F(NodeCoreTest, ACopyIsAnsweredFromTheCacheUntilTheRetentionEnds) {
  startLink();
  ASSERT_NE(answer(requestFrame({1})), nullptr);
  const Bytes First = Answers[0].Frame;

  Time += milliseconds(999);
  const auto Cached = datagram(requestFrame({1}));
  ASSERT_EQ(Cached.size(), 1u);
  EXPECT_EQ(Cached[0].Frame, First);

  Time += milliseconds(1);
  EXPECT_TRUE(datagram(requestFrame({1})).empty());
  EXPECT_EQ(Core.queue().depth(), 1u);
}

TEST_F(NodeCoreTest, AnAnswerOfADroppedLinkIsNotSent) {
  startLink();
  ASSERT_TRUE(datagram(requestFrame({1, 2})).empty());
  serve::Scratch Own(fbs::Pathfinder::VANILLA);

  // Finished before the drop, delivered after it.
  const std::optional<serve::Job> Early = Core.queue().tryTake();
  ASSERT_TRUE(Early);
  const serve::Outcome Done = Core.run(*Early, Own);
  ASSERT_NE(Done.Result, nullptr);
  // Running when the link drops.
  const std::optional<serve::Job> Late = Core.queue().tryTake();
  ASSERT_TRUE(Late);

  Core.disconnected(ControlId);
  Out.take();
  Core.deliver(*Early, Done.Result);
  EXPECT_EQ(Core.run(*Late, Own).Result, nullptr);
  EXPECT_TRUE(Out.take().empty());
}

TEST_F(NodeCoreTest, AJobThatWaitedPastMaxWaitIsDroppedUnanswered) {
  startLink();
  ASSERT_TRUE(datagram(requestFrame({1})).empty());
  Time += milliseconds(500);
  ASSERT_TRUE(datagram(requestFrame({2, 3})).empty());
  Time += milliseconds(501);

  // 1 waited 1001 ms and is not counted, so the one job run is 2.
  EXPECT_EQ(serveWaiting(Core, With, 1), 1u);
  auto Done = Out.take();
  ASSERT_EQ(Done.size(), 1u);
  EXPECT_EQ(responseIn(Done[0])->body_as_PathResponse()->request_id(), 2u);
  EXPECT_EQ(Core.queue().depth(), 1u);

  // A copy of 1 is dropped as a copy of a canceled request is, until the
  // retention ends.
  Time += milliseconds(999);
  EXPECT_TRUE(datagram(requestFrame({1})).empty());
  EXPECT_EQ(Core.queue().depth(), 1u);
  Time += milliseconds(1);
  EXPECT_TRUE(datagram(requestFrame({1})).empty());
  EXPECT_EQ(Core.queue().depth(), 2u);
}

TEST_F(NodeCoreTest, AJobThatWaitedExactlyMaxWaitIsSearched) {
  startLink();
  ASSERT_TRUE(datagram(requestFrame({1})).empty());
  Time += serve::NodeOptions{}.MaxWait;
  const auto Done = serve();
  ASSERT_EQ(Done.size(), 1u);
  EXPECT_EQ(responseIn(Done[0])->body_as_PathResponse()->request_id(), 1u);
}

TEST_F(NodeCoreTest, RunReportsAJobDroppedUnsearched) {
  startLink();
  ASSERT_TRUE(datagram(requestFrame({1})).empty());
  Time += milliseconds(1001);
  const std::optional<serve::Job> Next = Core.queue().tryTake();
  ASSERT_TRUE(Next);
  const serve::Outcome Done = Core.run(*Next, With);
  EXPECT_FALSE(Done.Searched);
  EXPECT_EQ(Done.Result, nullptr);
  EXPECT_EQ(Core.queue().depth(), 0u);
  EXPECT_TRUE(Out.take().empty());
}

TEST(NodeCore, AnAnswerLargerThanADatagramIsNotSent) {
  const Bytes Largest(serve::MaxAnswerSize, 7);
  const serve::Answer Kept = serve::answerOf(Largest);
  ASSERT_NE(Kept, nullptr);
  EXPECT_EQ(*Kept, Largest);
  const Bytes Over(serve::MaxAnswerSize + 1, 7);
  EXPECT_EQ(serve::answerOf(Over), nullptr);
}

TEST(NodeCore, RefusesASearchThisBuildLacks) {
  Recorder Out;
  EXPECT_THROW(
      serve::NodeCore(
          serve::NodeOptions{.Search = static_cast<fbs::Pathfinder>(99)},
          [] { return serve::Clock::time_point{}; }, Out),
      std::invalid_argument);
}
