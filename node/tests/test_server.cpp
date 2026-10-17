#include "maestro/net/server.hpp"

#include <array>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <optional>
#include <span>
#include <thread>
#include <utility>
#include <vector>

#include <asio/connect.hpp>
#include <asio/read.hpp>
#include <asio/write.hpp>
#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include "control_generated.h"
#include "fixture_generated.h"
#include "maestro/net/framing.hpp"
#include "test_frames.hpp"
#include "test_world.hpp"

using namespace maestro;
using namespace maestro::test;
using asio::ip::tcp;
using asio::ip::udp;
using serve::Port;
using std::chrono::milliseconds;

namespace {

/// Long enough for anything on loopback, short enough to fail fast.
constexpr milliseconds Patience{5000};
/// How long to watch for something that must not happen.
constexpr milliseconds Quiet{200};

/// Runs the operations started on `Io` for at most `Timeout`, then cancels
/// whatever is left through `Cancel` and lets it finish.
template <typename CancelFn>
void runFor(asio::io_context &Io, milliseconds Timeout, CancelFn Cancel) {
  Io.restart();
  Io.run_for(Timeout);
  if (!Io.stopped()) {
    Cancel();
    Io.restart();
    Io.run();
  }
}

/// A controller-side TCP connection to one of the node's ports.
class Connection {
public:
  Connection(asio::io_context &Io, std::uint16_t Number) : Io(Io), Socket(Io) {
    Socket.connect({asio::ip::make_address("127.0.0.1"), Number});
    Socket.set_option(tcp::no_delay(true));
  }

  void send(std::span<const std::uint8_t> Payload) {
    asio::write(Socket, asio::buffer(net::frame(Payload)));
  }

  void sendRaw(std::span<const std::uint8_t> Bytes) {
    asio::write(Socket, asio::buffer(Bytes.data(), Bytes.size()));
  }

  /// The next frame, or nothing if none arrives in time or the node closed
  /// the connection.
  std::optional<Bytes> read(milliseconds Timeout = Patience) {
    std::optional<Bytes> Out;
    asio::async_read(
        Socket, asio::buffer(Header), [&](asio::error_code Error, std::size_t) {
          if (Error)
            return noteClosed(Error);
          Body.resize(net::peekHeader(Header).Length);
          asio::async_read(Socket, asio::buffer(Body),
                           [&](asio::error_code Error, std::size_t) {
                             if (Error)
                               return noteClosed(Error);
                             Out = Body;
                           });
        });
    runFor(Io, Timeout, [&] { Socket.cancel(); });
    return Out;
  }

  /// The next control reply of type `Type`, skipping others.
  template <typename T>
  std::optional<Bytes> readReply(fbs::ControlReplyMsg Type,
                                 milliseconds Timeout = Patience) {
    const auto Deadline = std::chrono::steady_clock::now() + Timeout;
    while (std::chrono::steady_clock::now() < Deadline) {
      const auto Left = std::chrono::duration_cast<milliseconds>(
          Deadline - std::chrono::steady_clock::now());
      std::optional<Bytes> Frame = read(Left);
      if (!Frame)
        return std::nullopt;
      const auto *Reply = root<fbs::ControlReplyFrame>(*Frame);
      if (Reply && Reply->body_type() == Type)
        return Frame;
    }
    return std::nullopt;
  }

  std::optional<Bytes> readHello() {
    return readReply<fbs::Hello>(fbs::ControlReplyMsg::Hello);
  }

  /// Reads heartbeats until one reports `Depth`.
  bool awaitDepth(std::uint32_t Depth, milliseconds Timeout = Patience) {
    const auto Deadline = std::chrono::steady_clock::now() + Timeout;
    while (std::chrono::steady_clock::now() < Deadline) {
      std::optional<Bytes> Frame = readReply<fbs::Heartbeat>(
          fbs::ControlReplyMsg::Heartbeat,
          std::chrono::duration_cast<milliseconds>(
              Deadline - std::chrono::steady_clock::now()));
      if (!Frame)
        return false;
      if (root<fbs::ControlReplyFrame>(*Frame)
              ->body_as_Heartbeat()
              ->queue_depth() == Depth)
        return true;
    }
    return false;
  }

  /// Whether the node closes the connection within `Timeout`, reading past
  /// any frames still on their way.
  bool closedByNode(milliseconds Timeout = Patience) {
    const auto Deadline = std::chrono::steady_clock::now() + Timeout;
    while (!Closed && std::chrono::steady_clock::now() < Deadline)
      read(std::chrono::duration_cast<milliseconds>(
          Deadline - std::chrono::steady_clock::now()));
    return Closed;
  }

private:
  void noteClosed(asio::error_code Error) {
    Closed = Closed || Error != asio::error::operation_aborted;
  }

  asio::io_context &Io;
  tcp::socket Socket;
  std::array<std::uint8_t, net::HeaderSize> Header{};
  Bytes Body;
  bool Closed = false;
};

/// The controller's side of the request stream.
class Datagrams {
public:
  Datagrams(asio::io_context &Io, std::uint16_t Number)
      : Io(Io), Socket(Io, udp::endpoint(udp::v4(), 0)),
        Node(asio::ip::make_address("127.0.0.1"), Number) {}

  void send(const Bytes &Frame) { Socket.send_to(asio::buffer(Frame), Node); }

  std::optional<Bytes> receive(milliseconds Timeout = Patience) {
    std::optional<Bytes> Out;
    Buffer.resize(65536);
    Socket.async_receive_from(asio::buffer(Buffer), From,
                              [&](asio::error_code Error, std::size_t Size) {
                                if (!Error)
                                  Out = Bytes(Buffer.begin(),
                                              Buffer.begin() + Size);
                              });
    runFor(Io, Timeout, [&] { Socket.cancel(); });
    return Out;
  }

  /// The node's answer to a request, which must be a `PathResponse`.
  const fbs::PathResponse *answer(const Bytes &Frame) {
    const auto *Response = root<fbs::ResponseFrame>(Frame);
    return Response ? Response->body_as_PathResponse() : nullptr;
  }

  /// The next answer to `Id`, skipping any other reply.
  std::optional<Bytes> answerTo(std::uint64_t Id) {
    while (std::optional<Bytes> Reply = receive()) {
      const fbs::PathResponse *Response = answer(*Reply);
      if (Response && Response->request_id() == Id)
        return Reply;
    }
    return std::nullopt;
  }

private:
  asio::io_context &Io;
  udp::socket Socket;
  udp::endpoint Node;
  udp::endpoint From;
  Bytes Buffer;
};

net::ServerOptions testOptions(unsigned Workers = 2) {
  net::ServerOptions Opts;
  Opts.Bind = "127.0.0.1";
  Opts.WorldPort = 0;
  Opts.ControlPort = 0;
  Opts.RequestPort = 0;
  Opts.Workers = Workers;
  Opts.HeartbeatInterval = milliseconds(20);
  return Opts;
}

/// A node serving on its own thread, and a controller side to talk to it.
class ServerTest : public testing::Test {
protected:
  void start(net::ServerOptions Opts = testOptions()) {
    Node.emplace(NodeIo, Opts);
    Running = std::thread([this] { NodeIo.run(); });
  }

  void TearDown() override {
    if (!Node)
      return;
    Node->stop();
    Running.join();
  }

  /// A connection to `On` that has sent nothing yet.
  Connection connect(Port On) {
    return Connection(Io, On == Port::World ? Node->worldPort()
                                            : Node->controlPort());
  }

  /// A connection to `On` that has sent the `Join` of link `Link` of `Run`.
  Connection join(Port On, std::uint64_t Link, std::uint64_t Run) {
    Connection Joining = connect(On);
    Joining.send(joinFrame(On, Run, Link));
    return Joining;
  }

  Connection control(std::uint64_t Link = 1, std::uint64_t Run = TestRun) {
    return join(Port::Control, Link, Run);
  }

  Connection world(std::uint64_t Link = 1, std::uint64_t Run = TestRun) {
    return join(Port::World, Link, Run);
  }

  Datagrams requests() { return Datagrams(Io, Node->requestPort()); }

  /// Assigns the flat test world and sends its four chunks, sequences 1 to 4.
  static void loadFlat(Connection &World) {
    test::DenseWorld Flat(32, 0, 24);
    Flat.fill(0, 0, 0, 31, 3, 31, test::Stone);
    World.send(Flat.assignment(false, TestRun, TestRegion));
    std::uint64_t Sequence = 1;
    for (int X = 0; X < 2; ++X)
      for (int Z = 0; Z < 2; ++Z)
        World.send(Flat.chunk(Sequence++, X, Z));
  }

  /// Sends `Frame` until the answer to `Id` arrives, which a profile still
  /// on its way may delay, and returns it.
  static std::optional<Bytes> ask(Datagrams &Requests, const Bytes &Frame,
                                  std::uint64_t Id) {
    const auto Deadline = std::chrono::steady_clock::now() + Patience;
    while (std::chrono::steady_clock::now() < Deadline) {
      Requests.send(Frame);
      while (std::optional<Bytes> Reply = Requests.receive(Quiet)) {
        const auto *Response = root<fbs::ResponseFrame>(*Reply);
        if (Response &&
            Response->body_type() == fbs::ResponseMsg::PathResponse &&
            Response->body_as_PathResponse()->request_id() == Id)
          return Reply;
      }
    }
    return std::nullopt;
  }

  /// Waits for an answer at world sequence `Sequence`, asking again under a
  /// new id while the node is still applying frames.
  static std::optional<Bytes> askAt(Datagrams &Requests, std::uint64_t Sequence,
                                    std::uint64_t &Id) {
    const auto Deadline = std::chrono::steady_clock::now() + Patience;
    while (std::chrono::steady_clock::now() < Deadline) {
      ++Id;
      std::optional<Bytes> Reply = ask(Requests, requestFrame({Id}), Id);
      if (Reply && Requests.answer(*Reply)->world_sequence() == Sequence)
        return Reply;
    }
    return std::nullopt;
  }

  asio::io_context NodeIo;
  std::optional<net::Server> Node;
  std::thread Running;
  asio::io_context Io;
};

const fbs::Hello *hello(const Bytes &Frame) {
  return root<fbs::ControlReplyFrame>(Frame)->body_as_Hello();
}

} // namespace

TEST_F(ServerTest, AFreshNodeSaysItHoldsNothing) {
  start();
  Connection Control = control();
  const std::optional<Bytes> Frame = Control.readHello();
  ASSERT_TRUE(Frame);
  const fbs::Hello *Greeting = hello(*Frame);
  ASSERT_NE(Greeting, nullptr);
  EXPECT_EQ(Greeting->run(), 0u);
  EXPECT_EQ(Greeting->region_id(), 0u);
  EXPECT_EQ(Greeting->last_sequence(), 0u);
  EXPECT_EQ(Greeting->pathfinder(), fbs::Pathfinder::VANILLA);
}

TEST_F(ServerTest, HelloArrivesOnlyAfterTheControlJoin) {
  start();
  Connection Control = connect(Port::Control);
  EXPECT_FALSE(Control.read(Quiet));
  Control.send(joinFrame(Port::Control, TestRun, 1));
  EXPECT_TRUE(Control.readHello());
}

TEST_F(ServerTest, AnswersAVanillaSearchAsVanillaDid) {
  // The first exact case of a recorded fixture, over the real sockets.
  const std::filesystem::path Path =
      std::filesystem::path(MAESTRO_FIXTURE_DIR) / "seed-1.bin";
  std::ifstream In(Path, std::ios::binary);
  const Bytes File(std::istreambuf_iterator<char>(In), {});
  const auto *Fixture = root<fbs::ParityFixture>(File);
  ASSERT_NE(Fixture, nullptr);
  const fbs::ParityCase *Case = nullptr;
  for (const fbs::ParityCase *Candidate : *Fixture->cases())
    if (Candidate->exact()) {
      Case = Candidate;
      break;
    }
  ASSERT_NE(Case, nullptr);

  // The fixture's world stream holds framed frames, as the wire carries them.
  // Its first is the assignment, whose run the link and requests must name.
  const auto *Stream = Fixture->world();
  const auto First = net::peekHeader(std::span(Stream->data(), Stream->size()));
  ASSERT_EQ(First.Status, net::HeaderStatus::Ready);
  const Bytes AssignmentFrame(Stream->data() + net::HeaderSize,
                              Stream->data() + net::HeaderSize + First.Length);
  const auto *Assignment =
      root<fbs::WorldFrame>(AssignmentFrame)->body_as_RegionAssignment();
  ASSERT_NE(Assignment, nullptr);
  const std::uint64_t Run = Assignment->run();
  const std::uint32_t Region = Assignment->region_id();
  ASSERT_NE(Run, 0u);

  start();
  Connection Control = control(1, Run);
  Connection World = world(1, Run);
  ASSERT_TRUE(Control.readHello());
  Control.send(Bytes(Case->mob_class()->begin(), Case->mob_class()->end()));

  std::size_t Offset = 0;
  for (std::uint32_t Sent = 0; Sent < Case->world_frames(); ++Sent) {
    const auto Header = net::peekHeader(
        std::span(Stream->data() + Offset, Stream->size() - Offset));
    ASSERT_EQ(Header.Status, net::HeaderStatus::Ready);
    World.sendRaw(
        std::span(Stream->data() + Offset, net::HeaderSize + Header.Length));
    Offset += net::HeaderSize + Header.Length;
  }

  const Bytes RecordedRequest(Case->request()->begin(), Case->request()->end());
  const Bytes RecordedResponse(Case->response()->begin(),
                               Case->response()->end());
  const fbs::PathRequest *Wire = root<fbs::RequestFrame>(RecordedRequest)
                                     ->body_as_PathRequestBatch()
                                     ->requests()
                                     ->Get(0);
  const fbs::PathResponse *Vanilla =
      root<fbs::ResponseFrame>(RecordedResponse)->body_as_PathResponse();

  Datagrams Requests = requests();
  // Asked again under a new id until the node has applied every frame.
  std::optional<Bytes> Reply;
  for (std::uint64_t Id = 1; Id < 100 && !Reply; ++Id) {
    Reply = ask(Requests, requestFrame(*Wire, Id, Run, Region), Id);
    ASSERT_TRUE(Reply);
    if (Requests.answer(*Reply)->world_sequence() != Vanilla->world_sequence())
      Reply.reset();
  }
  ASSERT_TRUE(Reply);
  EXPECT_EQ(root<fbs::ResponseFrame>(*Reply)->run(), Run);
  const fbs::PathResponse *Answer = Requests.answer(*Reply);
  ASSERT_NE(Answer, nullptr);
  EXPECT_EQ(Answer->reached(), Vanilla->reached());
  EXPECT_EQ(Answer->cost(), Vanilla->cost());
  ASSERT_EQ(Answer->nodes()->size(), Vanilla->nodes()->size());
  for (flatbuffers::uoffset_t I = 0; I < Vanilla->nodes()->size(); ++I) {
    const fbs::PathNode *Mine = Answer->nodes()->Get(I);
    const fbs::PathNode *Theirs = Vanilla->nodes()->Get(I);
    EXPECT_EQ(Mine->pos().x(), Theirs->pos().x()) << I;
    EXPECT_EQ(Mine->pos().y(), Theirs->pos().y()) << I;
    EXPECT_EQ(Mine->pos().z(), Theirs->pos().z()) << I;
    EXPECT_EQ(Mine->type(), Theirs->type()) << I;
  }
}

TEST_F(ServerTest, ACopyOfARequestGetsTheSameBytes) {
  start();
  Connection Control = control();
  Connection World = world();
  ASSERT_TRUE(Control.readHello());
  Control.send(mobClassFrame());
  loadFlat(World);
  Datagrams Requests = requests();
  std::uint64_t Id = 0;
  const std::optional<Bytes> First = askAt(Requests, 4, Id);
  ASSERT_TRUE(First);
  ASSERT_TRUE(Requests.answer(*First)->reached());
  EXPECT_EQ(root<fbs::ResponseFrame>(*First)->run(), TestRun);

  Requests.send(requestFrame({Id}));
  EXPECT_EQ(Requests.answerTo(Id), First);
}

TEST_F(ServerTest, ARequestNamingAnUnknownProfileIsAnsweredAtOnce) {
  start();
  Connection Control = control();
  ASSERT_TRUE(Control.readHello());
  Datagrams Requests = requests();
  Requests.send(requestFrame({11}, /*ClassId=*/40));
  const std::optional<Bytes> Reply = Requests.receive();
  ASSERT_TRUE(Reply);
  EXPECT_EQ(root<fbs::ResponseFrame>(*Reply)->run(), TestRun);
  const auto *Refusal =
      root<fbs::ResponseFrame>(*Reply)->body_as_UnknownProfile();
  ASSERT_NE(Refusal, nullptr);
  EXPECT_EQ(Refusal->request_id(), 11u);
  EXPECT_EQ(Refusal->class_id(), 40u);
}

TEST_F(ServerTest, ARefusedWorldFrameDropsTheWorldAndTheLink) {
  start();
  {
    Connection Control = control();
    Connection World = world();
    ASSERT_TRUE(Control.readHello());
    loadFlat(World);
    // Sequence 4 was the last applied, so 6 leaves a gap.
    World.send(test::clearedChunk(6, 0, 0));
    EXPECT_TRUE(World.closedByNode());
    EXPECT_TRUE(Control.closedByNode());
  }

  Connection Control = control(2);
  const std::optional<Bytes> Frame = Control.readHello();
  ASSERT_TRUE(Frame);
  EXPECT_EQ(hello(*Frame)->last_sequence(), 0u);
}

TEST_F(ServerTest, AReconnectingControllerIsToldWhatIsStillHeld) {
  start();
  {
    Connection Control = control();
    Connection World = world();
    ASSERT_TRUE(Control.readHello());
    Control.send(mobClassFrame());
    loadFlat(World);
    Datagrams Requests = requests();
    std::uint64_t Id = 0;
    ASSERT_TRUE(askAt(Requests, 4, Id));
  }

  // The world state outlives the link.
  Connection Control = control(2);
  const std::optional<Bytes> Frame = Control.readHello();
  ASSERT_TRUE(Frame);
  EXPECT_EQ(hello(*Frame)->run(), TestRun);
  EXPECT_EQ(hello(*Frame)->region_id(), TestRegion);
  EXPECT_EQ(hello(*Frame)->last_sequence(), 4u);

  // The controller resumes, and the stream continues where it stopped.
  Connection World = world(2);
  test::DenseWorld Flat(32, 0, 24);
  World.send(Flat.assignment(true, TestRun, TestRegion, 4));
  World.send(test::clearedChunk(5, 0, 0));
  Control.send(mobClassFrame());
  Datagrams Requests = requests();
  std::uint64_t Id = 100;
  EXPECT_TRUE(askAt(Requests, 5, Id));
}

TEST_F(ServerTest, ProfilesAreForgottenWithTheLink) {
  start();
  {
    Connection Control = control();
    ASSERT_TRUE(Control.readHello());
    Control.send(mobClassFrame());
    Datagrams Requests = requests();
    // Answered once the profile is in, with no world held.
    ASSERT_TRUE(ask(Requests, requestFrame({1}), 1));
  }

  Connection Control = control(2);
  ASSERT_TRUE(Control.readHello());
  Datagrams Requests = requests();
  Requests.send(requestFrame({2}));
  const std::optional<Bytes> Reply = Requests.receive();
  ASSERT_TRUE(Reply);
  EXPECT_EQ(root<fbs::ResponseFrame>(*Reply)->body_type(),
            fbs::ResponseMsg::UnknownProfile);
}

TEST_F(ServerTest, TheHeartbeatCountsWaitingRequestsAndACancelDropsOne) {
  // With no workers, requests wait until canceled.
  start(testOptions(/*Workers=*/0));
  Connection Control = control();
  ASSERT_TRUE(Control.readHello());
  Control.send(mobClassFrame());
  Datagrams Requests = requests();

  // Profiles travel on TCP and requests on UDP, so ask until the node stops
  // refusing the profile.
  for (int Attempt = 0; Attempt < 25; ++Attempt) {
    Requests.send(requestFrame({1}));
    if (!Requests.receive(Quiet))
      break;
  }
  ASSERT_TRUE(Control.awaitDepth(1));

  Requests.send(requestFrame({2, 3, 3}));
  ASSERT_TRUE(Control.awaitDepth(3));
  Requests.send(cancelFrame({2, 77}));
  ASSERT_TRUE(Control.awaitDepth(2));
  // A canceled request that arrives again stays dropped.
  Requests.send(requestFrame({2}));
  Requests.send(cancelFrame({3}));
  ASSERT_TRUE(Control.awaitDepth(1));
  EXPECT_FALSE(Requests.receive(Quiet));
}

TEST_F(ServerTest, ALaterLinkReplacesTheLink) {
  start();
  Connection Control = control(1);
  Connection World = world(1);
  ASSERT_TRUE(Control.readHello());

  Connection Next = control(2);
  ASSERT_TRUE(Next.readHello());
  EXPECT_TRUE(Control.closedByNode());
  EXPECT_TRUE(World.closedByNode());
  // The new link works.
  Next.send(mobClassFrame());
  Datagrams Requests = requests();
  EXPECT_TRUE(ask(Requests, requestFrame({1}), 1));
}

TEST_F(ServerTest, ANewRunReplacesTheLink) {
  start();
  Connection Control = control(5);
  ASSERT_TRUE(Control.readHello());

  // A new run starts counting its links again.
  Connection Next = control(1, TestRun + 1);
  ASSERT_TRUE(Next.readHello());
  EXPECT_TRUE(Control.closedByNode());
  Next.send(mobClassFrame());
  Datagrams Requests = requests();
  EXPECT_TRUE(
      ask(Requests, requestFrame({1}, TestClass, TestRun + 1), 1).has_value());
  // The old run's requests are dropped.
  Requests.send(requestFrame({2}));
  EXPECT_FALSE(Requests.receive(Quiet));
}

TEST_F(ServerTest, AStaleLinkIsRefusedAndTheLiveLinkUntouched) {
  start();
  Connection Control = control(2);
  ASSERT_TRUE(Control.readHello());

  Connection Stale = control(1);
  EXPECT_TRUE(Stale.closedByNode());
  Connection StaleWorld = world(1);
  EXPECT_TRUE(StaleWorld.closedByNode());

  EXPECT_FALSE(Control.closedByNode(Quiet));
  Control.send(mobClassFrame());
  Datagrams Requests = requests();
  EXPECT_TRUE(ask(Requests, requestFrame({1}), 1));
}

TEST_F(ServerTest, TheLinksOtherConnectionJoinsIt) {
  start();
  // The world connection joins first, so the link is live before `Hello`.
  Connection World = world(3);
  loadFlat(World);
  Connection Control = control(3);
  const std::optional<Bytes> Frame = Control.readHello();
  ASSERT_TRUE(Frame);
  Control.send(mobClassFrame());
  Datagrams Requests = requests();
  std::uint64_t Id = 0;
  EXPECT_TRUE(askAt(Requests, 4, Id));
  EXPECT_FALSE(World.closedByNode(Quiet));
}

TEST_F(ServerTest, ASecondConnectionForAJoinedPortIsRefused) {
  start();
  Connection Control = control();
  ASSERT_TRUE(Control.readHello());
  Connection World = world();
  Control.send(mobClassFrame());
  loadFlat(World);
  Datagrams Requests = requests();
  std::uint64_t Id = 0;
  ASSERT_TRUE(askAt(Requests, 4, Id));

  Connection ControlTwin = control();
  EXPECT_TRUE(ControlTwin.closedByNode());
  Connection WorldTwin = world();
  EXPECT_TRUE(WorldTwin.closedByNode());

  // Both joined connections still carry the link.
  World.send(test::clearedChunk(5, 0, 0));
  EXPECT_TRUE(askAt(Requests, 5, Id));
  EXPECT_FALSE(Control.closedByNode(Quiet));
}

TEST_F(ServerTest, AFirstFrameThatIsNotAJoinIsRefused) {
  start();
  Connection Control = control();
  ASSERT_TRUE(Control.readHello());

  Connection World = connect(Port::World);
  test::DenseWorld Flat(32, 0, 24);
  World.send(Flat.assignment(false, TestRun, TestRegion));
  EXPECT_TRUE(World.closedByNode());
  Connection Rogue = connect(Port::Control);
  Rogue.send(mobClassFrame());
  EXPECT_TRUE(Rogue.closedByNode());
  // A `Join` on the wrong port is not a frame of that port.
  Connection Crossed = connect(Port::World);
  Crossed.send(joinFrame(Port::Control, TestRun, 1));
  EXPECT_TRUE(Crossed.closedByNode());

  EXPECT_FALSE(Control.closedByNode(Quiet));
  EXPECT_TRUE(Control.awaitDepth(0));
}

TEST_F(ServerTest, ASilentConnectionIsClosedAfterTheJoinTimeout) {
  net::ServerOptions Opts = testOptions();
  Opts.JoinTimeout = milliseconds(250);
  start(Opts);
  Connection Control = control();
  ASSERT_TRUE(Control.readHello());

  Connection Silent = connect(Port::World);
  EXPECT_TRUE(Silent.closedByNode());
  EXPECT_FALSE(Control.closedByNode(Quiet));
  EXPECT_TRUE(Control.awaitDepth(0));
}

TEST_F(ServerTest, ADatagramOfAnotherRunIsDropped) {
  start();
  Connection Control = control();
  ASSERT_TRUE(Control.readHello());
  Datagrams Requests = requests();
  // No profile is held, so a request the node took would be refused at once.
  Requests.send(requestFrame({1}, TestClass, TestRun + 1));
  EXPECT_FALSE(Requests.receive(Quiet));

  Requests.send(requestFrame({2}));
  const std::optional<Bytes> Reply = Requests.receive();
  ASSERT_TRUE(Reply);
  const auto *Refusal =
      root<fbs::ResponseFrame>(*Reply)->body_as_UnknownProfile();
  ASSERT_NE(Refusal, nullptr);
  EXPECT_EQ(Refusal->request_id(), 2u);
}

TEST_F(ServerTest, ARequestForAnotherRegionIsAnsweredAtSequenceZero) {
  start();
  Connection Control = control();
  Connection World = world();
  ASSERT_TRUE(Control.readHello());
  Control.send(mobClassFrame());
  loadFlat(World);
  Datagrams Requests = requests();
  std::uint64_t Id = 0;
  ASSERT_TRUE(askAt(Requests, 4, Id));

  const std::optional<Bytes> Reply = ask(
      Requests, requestFrame({500}, TestClass, TestRun, TestRegion + 1), 500);
  ASSERT_TRUE(Reply);
  const fbs::PathResponse *Answer = Requests.answer(*Reply);
  EXPECT_EQ(Answer->world_sequence(), 0u);
  EXPECT_FALSE(Answer->reached());
  EXPECT_EQ(Answer->nodes()->size(), 0u);
}

TEST_F(ServerTest, AJoinAfterTheFirstFrameIsIgnored) {
  start();
  Connection Control = control();
  Connection World = world();
  ASSERT_TRUE(Control.readHello());
  Control.send(mobClassFrame());
  loadFlat(World);
  Datagrams Requests = requests();
  std::uint64_t Id = 0;
  ASSERT_TRUE(askAt(Requests, 4, Id));

  World.send(joinFrame(Port::World, TestRun, 1));
  Control.send(joinFrame(Port::Control, TestRun, 1));
  // The world stream goes on from where it was, on the same link.
  World.send(test::clearedChunk(5, 0, 0));
  EXPECT_TRUE(askAt(Requests, 5, Id));
  EXPECT_FALSE(
      Control.readReply<fbs::Hello>(fbs::ControlReplyMsg::Hello, Quiet));
  EXPECT_FALSE(World.closedByNode(Quiet));
}

TEST_F(ServerTest, AFrameDeclaredPastTheLimitDropsTheLink) {
  start();
  Connection Control = control();
  Connection World = world();
  ASSERT_TRUE(Control.readHello());
  const std::uint32_t Length = net::MaxPayloadSize + 1;
  const Bytes Header = {static_cast<std::uint8_t>(Length),
                        static_cast<std::uint8_t>(Length >> 8),
                        static_cast<std::uint8_t>(Length >> 16),
                        static_cast<std::uint8_t>(Length >> 24)};
  World.sendRaw(Header);
  EXPECT_TRUE(World.closedByNode());
  EXPECT_TRUE(Control.closedByNode());
}

TEST_F(ServerTest, AMalformedControlFrameDropsTheLink) {
  start();
  Connection Control = control();
  ASSERT_TRUE(Control.readHello());
  Control.send(Bytes{1, 2, 3, 4, 5, 6, 7, 8});
  EXPECT_TRUE(Control.closedByNode());
}

TEST_F(ServerTest, AMalformedDatagramIsIgnored) {
  start();
  Connection Control = control();
  ASSERT_TRUE(Control.readHello());
  Control.send(mobClassFrame());
  Datagrams Requests = requests();
  Requests.send(Bytes{9, 0, 0, 0, 1});
  Requests.send(Bytes{});
  EXPECT_TRUE(ask(Requests, requestFrame({1}), 1));
  EXPECT_FALSE(Control.closedByNode(Quiet));
}

TEST_F(ServerTest, StopsWithRequestsStillWaiting) {
  start(testOptions(/*Workers=*/1));
  Connection Control = control();
  Connection World = world();
  ASSERT_TRUE(Control.readHello());
  Control.send(mobClassFrame());
  loadFlat(World);
  Datagrams Requests = requests();
  std::vector<std::uint64_t> Ids;
  for (std::uint64_t Id = 1; Id <= 24; ++Id)
    Ids.push_back(Id);
  Requests.send(requestFrame(Ids));
  // `TearDown` stops the node, which must not wait for the queue to drain.
}

TEST_F(ServerTest, StopsWithAConnectionStillSilent) {
  start();
  Connection Silent = connect(Port::World);
  // `TearDown` stops the node, which must not wait for the join timeout.
}
