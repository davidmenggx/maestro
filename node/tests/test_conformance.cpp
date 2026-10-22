#include <array>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <map>
#include <optional>
#include <set>
#include <span>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest-spi.h>
#include <gtest/gtest.h>

#include "conformance_generated.h"
#include "control_generated.h"
#include "maestro/serve/node_core.hpp"
#include "test_frames.hpp"
#include "test_world.hpp"

using namespace maestro;
using namespace maestro::test;
using serve::ConnectionId;
using serve::Port;

namespace {

std::string hello(std::uint64_t Run, std::uint32_t RegionId,
                  std::uint64_t Sequence) {
  return "Hello(run " + std::to_string(Run) + ", region " +
         std::to_string(RegionId) + ", sequence " + std::to_string(Sequence) +
         ")";
}

std::string heartbeat(std::uint32_t Depth) {
  return "Heartbeat(depth " + std::to_string(Depth) + ")";
}

std::string answer(std::uint64_t RequestId, std::uint64_t Run,
                   std::uint64_t Sequence) {
  return "Answer(request " + std::to_string(RequestId) + ", run " +
         std::to_string(Run) + ", sequence " + std::to_string(Sequence) + ")";
}

std::string unknownProfile(std::uint64_t RequestId, std::uint64_t Run) {
  return "UnknownProfile(request " + std::to_string(RequestId) + ", run " +
         std::to_string(Run) + ")";
}

/// What `Wire` says the node does, in the words `Replay` reports it in.
std::string expected(const fbs::Effect &Wire) {
  switch (Wire.kind()) {
  case fbs::EffectKind::Hello:
    return hello(Wire.run(), Wire.region_id(), Wire.sequence());
  case fbs::EffectKind::Heartbeat:
    return heartbeat(Wire.depth());
  case fbs::EffectKind::Answer:
    return answer(Wire.request_id(), Wire.run(), Wire.sequence());
  case fbs::EffectKind::UnknownProfile:
    return unknownProfile(Wire.request_id(), Wire.run());
  case fbs::EffectKind::LinkDropped:
    return "LinkDropped";
  case fbs::EffectKind::Refused:
    return "Refused";
  }
  return "an effect of unknown kind " +
         std::to_string(static_cast<int>(Wire.kind()));
}

std::string join(const std::vector<std::string> &Effects) {
  std::ostringstream Out;
  Out << "[";
  for (std::size_t I = 0; I < Effects.size(); ++I)
    Out << (I > 0 ? ", " : "") << Effects[I];
  Out << "]";
  return Out.str();
}

/// `Ids` as text, for a report.
std::string list(const std::set<ConnectionId> &Ids) {
  std::string Out = "{";
  for (const ConnectionId Id : Ids)
    Out += (Out.size() > 1 ? ", " : "") + std::to_string(Id);
  return Out + "}";
}

/// A node with no workers and default options, driven one step at a time,
/// which describes everything it does in reply.
class Replay final : public serve::NodeCore::Output {
public:
  Replay() : Core(serve::NodeOptions{}, [this] { return Time; }, *this) {}

  /// Runs `Step` and returns what the node did.
  std::vector<std::string> run(const fbs::Step &Step) {
    Effects.clear();
    Opened.reset();
    Refused = false;
    // Copied out of the fixture, so it is aligned as a frame off the wire.
    const Bytes Frame = Step.frame()
                            ? Bytes(Step.frame()->begin(), Step.frame()->end())
                            : Bytes();
    const Port On =
        Step.port() == fbs::LinkPort::World ? Port::World : Port::Control;
    std::optional<ConnectionId> &Connection =
        Current[static_cast<std::size_t>(On)];
    switch (Step.kind()) {
    case fbs::StepKind::Connect:
      Opened = ++Last;
      PortOf[*Opened] = On;
      Core.connected(On, *Opened);
      Core.received(*Opened, Frame);
      // A refused connection never joined, so the port keeps its connection.
      if (!Refused) {
        Connection = *Opened;
        Joined.insert(*Opened);
      }
      break;
    case fbs::StepKind::Stream:
      if (Connection)
        Core.received(*Connection, Frame);
      else
        Effects.push_back("(the script streams on a port with no connection)");
      break;
    case fbs::StepKind::Disconnect:
      if (Connection) {
        // Closed by the controller, so not one the node closes.
        Joined.erase(*Connection);
        Core.disconnected(*Connection);
      } else {
        Effects.push_back("(the script closes a port with no connection)");
      }
      Connection.reset();
      break;
    case fbs::StepKind::Datagram:
      Core.datagram(Frame, Address);
      break;
    case fbs::StepKind::Advance:
      Time += std::chrono::milliseconds(Step.millis());
      break;
    case fbs::StepKind::Serve:
      serveWaiting(Core, With, Step.count());
      break;
    case fbs::StepKind::Heartbeat:
      Core.heartbeat();
      break;
    default:
      Effects.push_back("(a step of unknown kind)");
    }
    if (!Closed.empty()) {
      Effects.push_back("closed " + std::to_string(Closed.size()) +
                        " connections without dropping the link");
      for (const ConnectionId Id : Closed)
        Joined.erase(Id);
      Closed.clear();
    }
    return Effects;
  }

private:
  void send(ConnectionId To, std::span<const std::uint8_t> Frame) override {
    const auto PortFound = PortOf.find(To);
    const std::string Where =
        PortFound != PortOf.end() && PortFound->second == Port::Control
            ? ""
            : " on a connection that is not a control connection";
    const Bytes Copy(Frame.begin(), Frame.end());
    const auto *Reply = root<fbs::ControlReplyFrame>(Copy);
    if (const auto *Body = Reply ? Reply->body_as_Hello() : nullptr)
      Effects.push_back(
          hello(Body->run(), Body->region_id(), Body->last_sequence()) + Where);
    else if (const auto *Beat = Reply ? Reply->body_as_Heartbeat() : nullptr)
      Effects.push_back(heartbeat(Beat->queue_depth()) + Where);
    else
      Effects.push_back("an unreadable frame" + Where);
  }

  void close(ConnectionId Which) override {
    if (Opened && Which == *Opened) {
      Effects.push_back("Refused");
      Refused = true;
    } else {
      Closed.insert(Which);
    }
  }

  void sendTo(const asio::ip::udp::endpoint &To,
              std::span<const std::uint8_t> Datagram) override {
    const std::string Where = To == Address ? "" : " to another address";
    const Bytes Copy(Datagram.begin(), Datagram.end());
    const auto *Response = root<fbs::ResponseFrame>(Copy);
    if (const auto *Path =
            Response ? Response->body_as_PathResponse() : nullptr)
      Effects.push_back(
          answer(Path->request_id(), Response->run(), Path->world_sequence()) +
          Where);
    else if (const auto *Refusal =
                 Response ? Response->body_as_UnknownProfile() : nullptr)
      Effects.push_back(unknownProfile(Refusal->request_id(), Response->run()) +
                        Where);
    else
      Effects.push_back("an unreadable datagram" + Where);
  }

  void linkDropped(std::string_view) override {
    // The drop closes exactly the link's joined connections.
    if (Closed == Joined)
      Effects.push_back("LinkDropped");
    else
      Effects.push_back("LinkDropped after closing " + list(Closed) +
                        " instead of " + list(Joined));
    Closed.clear();
    Joined.clear();
  }

  const asio::ip::udp::endpoint Address{asio::ip::make_address("127.0.0.1"),
                                        40000};
  serve::Clock::time_point Time{};
  serve::NodeCore Core;
  serve::Scratch With{fbs::Pathfinder::VANILLA};
  /// The connection each port's steps act on.
  std::array<std::optional<ConnectionId>, 2> Current;
  std::map<ConnectionId, Port> PortOf;
  ConnectionId Last = 0;
  /// The connection the current step opened, and whether the node refused it.
  std::optional<ConnectionId> Opened;
  bool Refused = false;
  /// Connections that joined and that neither side has closed since.
  std::set<ConnectionId> Joined;
  /// Connections the node closed since the last link drop, other than
  /// `Opened`.
  std::set<ConnectionId> Closed;
  std::vector<std::string> Effects;
};

/// Replays every case of `Fixture` on a fresh node, and fails a case at its
/// first step whose effects differ.
void replay(const fbs::ConformanceFixture &Fixture) {
  ASSERT_NE(Fixture.cases(), nullptr);
  for (flatbuffers::uoffset_t C = 0; C < Fixture.cases()->size(); ++C) {
    const fbs::ConformanceCase *Case = Fixture.cases()->Get(C);
    const std::string Name =
        Case->name() ? Case->name()->str() : "case " + std::to_string(C);
    if (!Case->steps()) {
      ADD_FAILURE() << Name << " has no steps";
      continue;
    }
    Replay Node;
    for (flatbuffers::uoffset_t I = 0; I < Case->steps()->size(); ++I) {
      const fbs::Step *Step = Case->steps()->Get(I);
      const std::vector<std::string> Got = Node.run(*Step);
      std::vector<std::string> Want;
      if (Step->effects())
        for (const fbs::Effect *Wire : *Step->effects())
          Want.push_back(expected(*Wire));
      if (Got != Want) {
        ADD_FAILURE() << Name << ", step " << I << " ("
                      << fbs::EnumNameStepKind(Step->kind()) << "): expected "
                      << join(Want) << ", got " << join(Got);
        break;
      }
    }
  }
}

/// A script under construction, for checking the replay itself.
class Script {
public:
  struct Effect {
    fbs::EffectKind Kind;
    std::uint64_t Run = 0;
    std::uint32_t RegionId = 0;
    std::uint64_t Sequence = 0;
    std::uint32_t Depth = 0;
    std::uint64_t RequestId = 0;
  };

  Script &step(fbs::StepKind Kind, fbs::LinkPort On, const Bytes &Frame,
               std::vector<Effect> Effects = {}, std::uint32_t Millis = 0,
               std::uint32_t Count = 0) {
    std::vector<flatbuffers::Offset<fbs::Effect>> Built;
    for (const Effect &E : Effects)
      Built.push_back(fbs::CreateEffect(Builder, E.Kind, E.Run, E.RegionId,
                                        E.Sequence, E.Depth, E.RequestId));
    Steps.push_back(fbs::CreateStep(
        Builder, Kind, On, Frame.empty() ? 0 : Builder.CreateVector(Frame),
        Millis, Count, Builder.CreateVector(Built)));
    return *this;
  }

  Bytes finish(const char *Name) {
    const auto Case = fbs::CreateConformanceCase(
        Builder, Builder.CreateString(Name), Builder.CreateVector(Steps));
    fbs::FinishConformanceFixtureBuffer(
        Builder, fbs::CreateConformanceFixture(
                     Builder, Builder.CreateVector(std::vector{Case})));
    return {Builder.GetBufferPointer(),
            Builder.GetBufferPointer() + Builder.GetSize()};
  }

private:
  flatbuffers::FlatBufferBuilder Builder;
  std::vector<flatbuffers::Offset<fbs::Step>> Steps;
};

} // namespace

TEST(Conformance, ReplaysEveryCaseOfTheFixture) {
  const std::filesystem::path Path = MAESTRO_CONFORMANCE_FIXTURE;
  if (!std::filesystem::exists(Path))
    GTEST_SKIP() << "no conformance fixture at " << Path;
  std::ifstream In(Path, std::ios::binary);
  const Bytes File(std::istreambuf_iterator<char>(In), {});
  flatbuffers::Verifier Verifier(File.data(), File.size());
  ASSERT_TRUE(fbs::VerifyConformanceFixtureBuffer(Verifier))
      << Path << " is not a conformance fixture";
  const fbs::ConformanceFixture *Fixture =
      fbs::GetConformanceFixture(File.data());
  ASSERT_NE(Fixture->cases(), nullptr);
  EXPECT_GT(Fixture->cases()->size(), 0u);
  replay(*Fixture);
}

TEST(Conformance, TheReplayChecksEveryKindOfStepAndEffect) {
  using fbs::EffectKind;
  using fbs::LinkPort;
  using fbs::StepKind;
  DenseWorld Flat(32, 0, 24);
  Flat.fill(0, 0, 0, 31, 3, 31, Stone);
  Script Steps;
  Steps
      .step(StepKind::Connect, LinkPort::Control,
            joinFrame(Port::Control, TestRun, 1), {{EffectKind::Hello}})
      .step(StepKind::Connect, LinkPort::World,
            joinFrame(Port::World, TestRun, 1))
      .step(StepKind::Stream, LinkPort::World,
            Flat.assignment(false, TestRun, TestRegion));
  for (int X = 0; X < 2; ++X)
    for (int Z = 0; Z < 2; ++Z)
      Steps.step(StepKind::Stream, LinkPort::World,
                 Flat.chunk(1 + X * 2 + Z, X, Z));
  const auto Answered = [](std::uint64_t RequestId) {
    return Script::Effect{.Kind = EffectKind::Answer,
                          .Run = TestRun,
                          .Sequence = 4,
                          .RequestId = RequestId};
  };
  Steps
      .step(StepKind::Datagram, LinkPort::World, requestFrame({1}),
            {{.Kind = EffectKind::UnknownProfile,
              .Run = TestRun,
              .RequestId = 1}})
      .step(StepKind::Stream, LinkPort::Control, mobClassFrame())
      .step(StepKind::Datagram, LinkPort::World, requestFrame({2, 3, 4}))
      .step(StepKind::Datagram, LinkPort::World, cancelFrame({2}))
      .step(StepKind::Heartbeat, LinkPort::World, {},
            {{.Kind = EffectKind::Heartbeat, .Depth = 2}})
      // 2 was canceled, so it is skipped and not counted.
      .step(StepKind::Serve, LinkPort::World, {}, {Answered(3)}, 0, 1)
      .step(StepKind::Advance, LinkPort::World, {}, {}, 999)
      .step(StepKind::Datagram, LinkPort::World, requestFrame({3}),
            {Answered(3)})
      .step(StepKind::Datagram, LinkPort::World, requestFrame({5}))
      .step(StepKind::Advance, LinkPort::World, {}, {}, 2)
      // 4 waited past the longest wait, so it is skipped and not counted.
      .step(StepKind::Serve, LinkPort::World, {}, {Answered(5)}, 0, 1)
      .step(StepKind::Datagram, LinkPort::World, requestFrame({4}))
      .step(StepKind::Heartbeat, LinkPort::World, {},
            {{.Kind = EffectKind::Heartbeat, .Depth = 0}})
      .step(StepKind::Connect, LinkPort::Control,
            joinFrame(Port::Control, TestRun, 1), {{EffectKind::Refused}})
      // The refused connection never became the port's.
      .step(StepKind::Disconnect, LinkPort::Control, {},
            {{EffectKind::LinkDropped}})
      .step(StepKind::Connect, LinkPort::Control,
            joinFrame(Port::Control, TestRun, 2),
            {{EffectKind::Hello, TestRun, TestRegion, 4}})
      .step(StepKind::Connect, LinkPort::World,
            joinFrame(Port::World, TestRun, 2))
      // A fresh assignment behind the stream held.
      .step(StepKind::Stream, LinkPort::World,
            Flat.assignment(false, TestRun, TestRegion, 3),
            {{EffectKind::LinkDropped}})
      .step(StepKind::Connect, LinkPort::Control,
            joinFrame(Port::Control, TestRun, 3), {{EffectKind::Hello}})
      .step(StepKind::Serve, LinkPort::World, {})
      .step(StepKind::Disconnect, LinkPort::Control, {},
            {{EffectKind::LinkDropped}});
  const Bytes File = Steps.finish("every kind");
  replay(*fbs::GetConformanceFixture(File.data()));
}

TEST(Conformance, TheReplayReportsADifference) {
  Script Steps;
  // The node greets the control connection, which the script leaves out.
  Steps.step(fbs::StepKind::Connect, fbs::LinkPort::Control,
             joinFrame(Port::Control, TestRun, 1));
  const Bytes File = Steps.finish("missing hello");
  EXPECT_NONFATAL_FAILURE(replay(*fbs::GetConformanceFixture(File.data())),
                          "missing hello, step 0 (Connect): expected [], got "
                          "[Hello(run 0, region 0, sequence 0)]");
}
