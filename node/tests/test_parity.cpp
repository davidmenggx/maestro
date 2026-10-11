#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <optional>
#include <span>
#include <sstream>
#include <string>
#include <vector>

#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include "control_generated.h"
#include "fixture_generated.h"
#include "maestro/net/framing.hpp"
#include "maestro/search/search.hpp"
#include "maestro/world/world_store.hpp"

namespace fbs = maestro::fbs;
namespace search = maestro::search;
namespace world = maestro::world;

namespace {

using Bytes = std::vector<std::uint8_t>;

std::vector<std::filesystem::path> fixtureFiles() {
  std::vector<std::filesystem::path> Files;
  for (const auto &Entry :
       std::filesystem::directory_iterator(MAESTRO_FIXTURE_DIR))
    if (Entry.path().extension() == ".bin")
      Files.push_back(Entry.path());
  std::sort(Files.begin(), Files.end());
  return Files;
}

Bytes readFile(const std::filesystem::path &Path) {
  std::ifstream In(Path, std::ios::binary);
  return Bytes(std::istreambuf_iterator<char>(In), {});
}

/// Copies a frame out of the container into its own buffer, aligned as a frame
/// read off the wire would be.
Bytes copy(const flatbuffers::Vector<std::uint8_t> *Frame) {
  return Frame ? Bytes(Frame->begin(), Frame->end()) : Bytes();
}

template <typename T> const T *verified(const Bytes &Frame) {
  flatbuffers::Verifier Verifier(Frame.data(), Frame.size());
  if (!Verifier.VerifyBuffer<T>(nullptr))
    return nullptr;
  return flatbuffers::GetRoot<T>(Frame.data());
}

/// Splits the framed world stream and applies each frame, as the node's world
/// channel would.
testing::AssertionResult load(const flatbuffers::Vector<std::uint8_t> *Stream,
                              world::WorldStore &Store) {
  if (!Stream)
    return testing::AssertionFailure() << "no world stream";
  std::size_t Offset = 0;
  int Frames = 0;
  while (Offset < Stream->size()) {
    const std::span<const std::uint8_t> Rest(Stream->data() + Offset,
                                             Stream->size() - Offset);
    const auto Header = maestro::net::peekHeader(Rest);
    if (Header.Status != maestro::net::HeaderStatus::Ready ||
        Rest.size() < maestro::net::HeaderSize + Header.Length)
      return testing::AssertionFailure() << "torn frame at byte " << Offset;
    const Bytes Frame(Rest.begin() + maestro::net::HeaderSize,
                      Rest.begin() + maestro::net::HeaderSize + Header.Length);
    if (Store.apply(Frame) != world::ApplyStatus::Applied)
      return testing::AssertionFailure() << "frame " << Frames << " refused";
    Offset += maestro::net::HeaderSize + Header.Length;
    ++Frames;
  }
  return testing::AssertionSuccess();
}

/// Vanilla's answer, as recorded.
struct Recorded {
  std::vector<search::PathPoint> Nodes;
  bool Reached = false;
  float Cost = 0.0F;
  std::uint64_t WorldSequence = 0;
};

std::optional<Recorded> decode(const fbs::PathResponse &Response) {
  if (!Response.nodes())
    return std::nullopt;
  Recorded Out;
  for (const fbs::PathNode *Node : *Response.nodes())
    Out.Nodes.push_back(
        {{Node->pos().x(), Node->pos().y(), Node->pos().z()}, Node->type()});
  Out.Reached = Response.reached();
  Out.Cost = Response.cost();
  Out.WorldSequence = Response.world_sequence();
  return Out;
}

std::string describe(const std::vector<search::PathPoint> &Nodes) {
  std::ostringstream Out;
  for (const search::PathPoint &Point : Nodes)
    Out << " (" << Point.Pos.X << "," << Point.Pos.Y << "," << Point.Pos.Z
        << " " << fbs::EnumNamePathType(Point.Type) << ")";
  return Out.str();
}

/// How the node's answer differs from vanilla's, or empty if it does not.
std::string difference(const search::Result &Node, const Recorded &Vanilla) {
  std::ostringstream Out;
  if (Node.Nodes != Vanilla.Nodes)
    Out << "\n  vanilla path:" << describe(Vanilla.Nodes)
        << "\n  node path:   " << describe(Node.Nodes);
  if (Node.Reached != Vanilla.Reached)
    Out << "\n  reached: vanilla " << Vanilla.Reached << ", node "
        << Node.Reached;
  if (Node.Cost != Vanilla.Cost)
    Out << "\n  cost: vanilla " << Vanilla.Cost << ", node " << Node.Cost;
  return Out.str();
}

struct Tally {
  int Exact = 0;
  int Approximated = 0;
  int ApproximatedMatched = 0;
};

/// Replays every case of one fixture file and checks it against vanilla.
void replay(const std::filesystem::path &Path, Tally &Count) {
  SCOPED_TRACE(Path.filename().string());
  const Bytes File = readFile(Path);
  flatbuffers::Verifier Verifier(File.data(), File.size());
  ASSERT_TRUE(fbs::VerifyParityFixtureBuffer(Verifier));
  const fbs::ParityFixture *Fixture = fbs::GetParityFixture(File.data());
  ASSERT_NE(Fixture->cases(), nullptr);

  world::WorldStore Store;
  ASSERT_TRUE(load(Fixture->world(), Store));
  const std::shared_ptr<const world::Area> Area = Store.pin();
  ASSERT_NE(Area, nullptr);

  const auto Search = search::makeSearch(fbs::Pathfinder::VANILLA);
  search::Result Result;
  for (flatbuffers::uoffset_t I = 0; I < Fixture->cases()->size(); ++I) {
    const fbs::ParityCase *Case = Fixture->cases()->Get(I);
    const std::string Entity = Case->entity() ? Case->entity()->str() : "?";
    SCOPED_TRACE("case " + std::to_string(I) + " (" + Entity + ")");

    const Bytes ProfileFrame = copy(Case->mob_class());
    const Bytes RequestFrame = copy(Case->request());
    const Bytes ResponseFrame = copy(Case->response());
    const auto *Control = verified<fbs::ControlFrame>(ProfileFrame);
    const auto *Requests = verified<fbs::RequestFrame>(RequestFrame);
    const auto *Responses = verified<fbs::ResponseFrame>(ResponseFrame);
    ASSERT_TRUE(Control && Control->body_as_MobClass());
    ASSERT_TRUE(Requests && Requests->body_as_PathRequestBatch() &&
                Requests->body_as_PathRequestBatch()->requests() &&
                Requests->body_as_PathRequestBatch()->requests()->size() == 1);
    ASSERT_TRUE(Responses && Responses->body_as_PathResponse());

    const auto Profile = search::Profile::decode(*Control->body_as_MobClass());
    const fbs::PathRequest *Wire =
        Requests->body_as_PathRequestBatch()->requests()->Get(0);
    const auto Call = search::Request::decode(*Wire);
    const auto Vanilla = decode(*Responses->body_as_PathResponse());
    ASSERT_TRUE(Profile && Call && Vanilla);
    ASSERT_EQ(Profile->ClassId, Wire->class_id());
    ASSERT_EQ(Vanilla->WorldSequence, Area->sequence());

    Search->find(*Area, *Profile, *Call, Result);
    const std::string Diff = difference(Result, *Vanilla);
    if (Case->exact()) {
      ++Count.Exact;
      EXPECT_TRUE(Diff.empty()) << "differs from vanilla:" << Diff;
    } else {
      ++Count.Approximated;
      Count.ApproximatedMatched += Diff.empty() ? 1 : 0;
      if (!Diff.empty())
        std::cout << "[ parity   ] approximated " << Path.filename().string()
                  << " case " << I << " (" << Entity << ") differs:" << Diff
                  << "\n";
    }
  }
}

} // namespace

TEST(Parity, ReproducesEveryExactVanillaSearch) {
  const auto Files = fixtureFiles();
  ASSERT_FALSE(Files.empty()) << "no fixtures in " << MAESTRO_FIXTURE_DIR;

  Tally Count;
  for (const auto &Path : Files)
    replay(Path, Count);

  EXPECT_GT(Count.Exact, 0) << "no exact case to check";
  // Approximated cases may differ by design, so they are measured, not
  // asserted.
  std::cout << "[ parity   ] " << Count.Exact << " exact cases from "
            << Files.size() << " fixtures; " << Count.ApproximatedMatched
            << " of " << Count.Approximated
            << " approximated cases match vanilla\n";
  RecordProperty("exact", Count.Exact);
  RecordProperty("approximated", Count.Approximated);
  RecordProperty("approximated_matched", Count.ApproximatedMatched);
}
