#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
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

/// Splits the framed world stream into its frames.
testing::AssertionResult split(const flatbuffers::Vector<std::uint8_t> *Stream,
                               std::vector<Bytes> &Frames) {
  if (!Stream)
    return testing::AssertionFailure() << "no world stream";
  std::size_t Offset = 0;
  while (Offset < Stream->size()) {
    const std::span<const std::uint8_t> Rest(Stream->data() + Offset,
                                             Stream->size() - Offset);
    const auto Header = maestro::net::peekHeader(Rest);
    if (Header.Status != maestro::net::HeaderStatus::Ready ||
        Rest.size() < maestro::net::HeaderSize + Header.Length)
      return testing::AssertionFailure() << "torn frame at byte " << Offset;
    Frames.emplace_back(Rest.begin() + maestro::net::HeaderSize,
                        Rest.begin() + maestro::net::HeaderSize +
                            Header.Length);
    Offset += maestro::net::HeaderSize + Header.Length;
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

bool sameAnswer(const search::Result &A, const search::Result &B) {
  return A.Nodes == B.Nodes && A.Reached == B.Reached && A.Cost == B.Cost;
}

/// Whether the path climbs two or more blocks between two nodes, which takes a
/// jump of at least two.
bool climbsTwo(const std::vector<search::PathPoint> &Nodes) {
  for (std::size_t I = 1; I < Nodes.size(); ++I)
    if (Nodes[I].Pos.Y - Nodes[I - 1].Pos.Y >= 2)
      return true;
  return false;
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
  /// Exact cases that cover what the rest of the suite barely reaches: long
  /// paths, an answer that a block change decided, an answer that the visit
  /// budget decided, and a climb that takes a jump of two.
  int ExactLong = 0;
  int ExactChangedByDelta = 0;
  int ExactBudgetBound = 0;
  int ExactHighJump = 0;
  int Approximated = 0;
  int ApproximatedMatched = 0;
  /// How far the node's approximated answers land from vanilla's: the node's
  /// cost and node count minus vanilla's, summed, and the largest of each.
  double CostDifference = 0.0;
  double LargestCostDifference = 0.0;
  std::int64_t LengthDifference = 0;
  std::int64_t LargestLengthDifference = 0;
};

std::string text(double Value) {
  std::ostringstream Out;
  Out << Value;
  return Out.str();
}

/// Replays every case of one fixture file and checks it against vanilla.
void replay(const std::filesystem::path &Path, Tally &Count) {
  SCOPED_TRACE(Path.filename().string());
  const Bytes File = readFile(Path);
  flatbuffers::Verifier Verifier(File.data(), File.size());
  ASSERT_TRUE(fbs::VerifyParityFixtureBuffer(Verifier));
  const fbs::ParityFixture *Fixture = fbs::GetParityFixture(File.data());
  ASSERT_NE(Fixture->cases(), nullptr);

  std::vector<Bytes> WorldFrames;
  ASSERT_TRUE(split(Fixture->world(), WorldFrames));
  world::WorldStore Store;
  std::size_t Applied = 0;
  // The world as it was before the first block change, to tell which answers
  // a change decided.
  std::shared_ptr<const world::Area> BeforeDeltas;

  const auto Search = search::makeSearch(fbs::Pathfinder::VANILLA);
  search::Result Result;
  search::Result Other;
  for (flatbuffers::uoffset_t I = 0; I < Fixture->cases()->size(); ++I) {
    const fbs::ParityCase *Case = Fixture->cases()->Get(I);
    const std::string Entity = Case->entity() ? Case->entity()->str() : "?";
    SCOPED_TRACE("case " + std::to_string(I) + " (" + Entity + ")");

    // A case sees the world as of the frames applied before it.
    ASSERT_GE(Case->world_frames(), Applied);
    ASSERT_LE(Case->world_frames(), WorldFrames.size());
    for (; Applied < Case->world_frames(); ++Applied) {
      const auto *Frame = verified<fbs::WorldFrame>(WorldFrames[Applied]);
      ASSERT_NE(Frame, nullptr);
      if (!BeforeDeltas && Frame->body_type() == fbs::WorldMsg::WorldDelta)
        BeforeDeltas = Store.pin();
      ASSERT_EQ(Store.apply(WorldFrames[Applied]), world::ApplyStatus::Applied)
          << "frame " << Applied << " refused";
    }
    const std::shared_ptr<const world::Area> Area = Store.pin();
    ASSERT_NE(Area, nullptr);

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
      Count.ExactLong += Vanilla->Nodes.size() > 40 ? 1 : 0;
      Count.ExactHighJump += climbsTwo(Vanilla->Nodes) ? 1 : 0;
      if (BeforeDeltas) {
        Search->find(*BeforeDeltas, *Profile, *Call, Other);
        Count.ExactChangedByDelta += sameAnswer(Result, Other) ? 0 : 1;
      }
      search::Request Unbounded = *Call;
      Unbounded.MaxVisitedNodes = std::numeric_limits<int>::max();
      Search->find(*Area, *Profile, Unbounded, Other);
      Count.ExactBudgetBound += sameAnswer(Result, Other) ? 0 : 1;
      EXPECT_TRUE(Diff.empty()) << "differs from vanilla:" << Diff;
    } else {
      ++Count.Approximated;
      Count.ApproximatedMatched += Diff.empty() ? 1 : 0;
      const double Cost = static_cast<double>(Result.Cost) - Vanilla->Cost;
      const auto Length = static_cast<std::int64_t>(Result.Nodes.size()) -
                          static_cast<std::int64_t>(Vanilla->Nodes.size());
      Count.CostDifference += Cost;
      Count.LargestCostDifference =
          std::max(Count.LargestCostDifference, std::abs(Cost));
      Count.LengthDifference += Length;
      Count.LargestLengthDifference =
          std::max(Count.LargestLengthDifference, std::abs(Length));
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
  EXPECT_GT(Count.ExactLong, 0) << "no long path among the exact cases";
  EXPECT_GT(Count.ExactChangedByDelta, 0)
      << "no block change decided an exact case";
  EXPECT_GT(Count.ExactBudgetBound, 0)
      << "the visit budget decided no exact case";
  EXPECT_GT(Count.ExactHighJump, 0) << "no exact path climbs two blocks";
  // Approximated cases may differ by design, so they are measured, not
  // asserted. The measures are also test properties, which the XML report
  // keeps, so they can be followed across runs.
  const int Approximated = std::max(Count.Approximated, 1);
  const double MeanCost = Count.CostDifference / Approximated;
  const double MeanLength =
      static_cast<double>(Count.LengthDifference) / Approximated;
  std::cout << "[ parity   ] " << Count.Exact << " exact cases from "
            << Files.size() << " fixtures; " << Count.ApproximatedMatched
            << " of " << Count.Approximated
            << " approximated cases match vanilla\n";
  std::cout << "[ parity   ] exact cases: " << Count.ExactLong
            << " with a path over 40 nodes, " << Count.ExactChangedByDelta
            << " decided by a block change, " << Count.ExactBudgetBound
            << " decided by the visit budget, " << Count.ExactHighJump
            << " climbing two blocks\n";
  std::cout << "[ parity   ] approximated cases, node minus vanilla: cost "
            << MeanCost << " on average (largest "
            << Count.LargestCostDifference << "), nodes " << MeanLength
            << " on average (largest " << Count.LargestLengthDifference
            << ")\n";
  RecordProperty("exact", Count.Exact);
  RecordProperty("approximated", Count.Approximated);
  RecordProperty("approximated_matched", Count.ApproximatedMatched);
  RecordProperty("approximated_cost_difference_mean", text(MeanCost));
  RecordProperty("approximated_cost_difference_largest",
                 text(Count.LargestCostDifference));
  RecordProperty("approximated_length_difference_mean", text(MeanLength));
  RecordProperty("approximated_length_difference_largest",
                 std::to_string(Count.LargestLengthDifference));
}
