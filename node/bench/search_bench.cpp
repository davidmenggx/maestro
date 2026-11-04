#include <algorithm>
#include <cstdint>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <memory>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

#include <benchmark/benchmark.h>
#include <flatbuffers/flatbuffers.h>

#include "control_generated.h"
#include "fixture_generated.h"
#include "maestro/cli.hpp"
#include "maestro/net/framing.hpp"
#include "maestro/search/search.hpp"
#include "maestro/world/world_store.hpp"

namespace fbs = maestro::fbs;
namespace search = maestro::search;
namespace world = maestro::world;

namespace {

using Bytes = std::vector<std::uint8_t>;

/// One recorded vanilla search: the world it ran on, the mob and the call.
struct Case {
  std::string Entity;
  std::shared_ptr<const world::Area> Area;
  search::Profile Mob;
  search::Request Call;
};

[[noreturn]] void fail(const std::filesystem::path &Path,
                       const std::string &Why) {
  throw std::runtime_error(Path.string() + ": " + Why);
}

/// Copies a frame into its own aligned buffer, as read off the wire.
Bytes copy(const flatbuffers::Vector<std::uint8_t> *Frame) {
  return Frame ? Bytes(Frame->begin(), Frame->end()) : Bytes();
}

template <typename T> const T *verified(const Bytes &Frame) {
  flatbuffers::Verifier Verifier(Frame.data(), Frame.size());
  if (!Verifier.VerifyBuffer<T>(nullptr))
    return nullptr;
  return flatbuffers::GetRoot<T>(Frame.data());
}

/// Splits the framed world stream `Stream` into its frames.
std::vector<Bytes> split(const std::filesystem::path &Path,
                         const flatbuffers::Vector<std::uint8_t> *Stream) {
  if (!Stream)
    fail(Path, "no world stream");
  std::vector<Bytes> Frames;
  std::size_t Offset = 0;
  while (Offset < Stream->size()) {
    const std::span<const std::uint8_t> Rest(Stream->data() + Offset,
                                             Stream->size() - Offset);
    const auto Header = maestro::net::peekHeader(Rest);
    if (Header.Status != maestro::net::HeaderStatus::Ready ||
        Rest.size() < maestro::net::HeaderSize + Header.Length)
      fail(Path, "torn world frame at byte " + std::to_string(Offset));
    const auto Payload = Rest.subspan(maestro::net::HeaderSize, Header.Length);
    Frames.emplace_back(Payload.begin(), Payload.end());
    Offset += maestro::net::HeaderSize + Header.Length;
  }
  return Frames;
}

/// Appends the cases of the parity fixture at `Path` to `Out`, each with the
/// world as of the frames applied before it.
void load(const std::filesystem::path &Path, std::vector<Case> &Out) {
  std::ifstream In(Path, std::ios::binary);
  const Bytes File(std::istreambuf_iterator<char>(In), {});
  flatbuffers::Verifier Verifier(File.data(), File.size());
  if (!fbs::VerifyParityFixtureBuffer(Verifier))
    fail(Path, "not a parity fixture");
  const fbs::ParityFixture *Fixture = fbs::GetParityFixture(File.data());
  if (!Fixture->cases())
    fail(Path, "no cases");

  const std::vector<Bytes> WorldFrames = split(Path, Fixture->world());
  world::WorldStore Store;
  std::size_t Applied = 0;
  for (const fbs::ParityCase *Recorded : *Fixture->cases()) {
    if (Recorded->world_frames() < Applied ||
        Recorded->world_frames() > WorldFrames.size())
      fail(Path, "a case names world frames out of order");
    for (; Applied < Recorded->world_frames(); ++Applied) {
      const auto *Frame = verified<fbs::WorldFrame>(WorldFrames[Applied]);
      if (!Frame || Store.apply(*Frame) != world::ApplyStatus::Applied)
        fail(Path, "world frame " + std::to_string(Applied) + " refused");
    }

    const Bytes ProfileFrame = copy(Recorded->mob_class());
    const Bytes RequestFrame = copy(Recorded->request());
    const auto *Control = verified<fbs::ControlFrame>(ProfileFrame);
    const auto *Requests = verified<fbs::RequestFrame>(RequestFrame);
    if (!Control || !Control->body_as_MobClass() || !Requests ||
        !Requests->body_as_PathRequestBatch() ||
        !Requests->body_as_PathRequestBatch()->requests() ||
        Requests->body_as_PathRequestBatch()->requests()->size() != 1)
      fail(Path, "a case holds no mob or request");
    const auto Profile = search::Profile::decode(*Control->body_as_MobClass());
    const auto Call = search::Request::decode(
        *Requests->body_as_PathRequestBatch()->requests()->Get(0));
    auto Area = Store.pin();
    if (!Profile || !Call || !Area)
      fail(Path, "a case breaks the wire contract");
    Out.push_back({Recorded->entity() ? Recorded->entity()->str() : "?",
                   std::move(Area), *Profile, *Call});
  }
}

/// Runs `Cases` in turn, one search an iteration, so the rate is that of the
/// recorded mix. Each thread has its own search, as each worker does, and
/// starts at its own case.
void searchCases(benchmark::State &State, const std::vector<Case> *Cases) {
  const auto Search = search::makeSearch(fbs::Pathfinder::VANILLA);
  search::Result Out;
  std::size_t Next = static_cast<std::size_t>(State.thread_index()) *
                     Cases->size() / static_cast<std::size_t>(State.threads());
  for (auto _ : State) {
    const Case &Run = (*Cases)[Next];
    Search->find(*Run.Area, Run.Mob, Run.Call, Out);
    benchmark::DoNotOptimize(Out.Nodes.data());
    Next = Next + 1 == Cases->size() ? 0 : Next + 1;
  }
  State.SetItemsProcessed(State.iterations());
  State.SetLabel(std::to_string(Cases->size()) + " cases");
}

} // namespace

/// Searches per second over the recorded parity cases: all of them on one
/// thread and on one per core this process may run on, as a node's workers
/// would, and each entity's on one thread. An argument left after the
/// benchmark's own flags names the fixture directory.
int main(int Argc, char **Argv) {
  benchmark::Initialize(&Argc, Argv);
  // A flag left is one the benchmark did not know, such as a misspelt one.
  if (Argc > 2 || (Argc == 2 && Argv[1][0] == '-')) {
    if (Argv[1][0] == '-')
      std::cerr << "maestro-bench: unknown flag " << Argv[1] << '\n';
    std::cerr << "usage: maestro-bench [benchmark flags] [fixture dir]\n";
    return 2;
  }
  const std::filesystem::path Dir = Argc == 2 ? Argv[1] : MAESTRO_FIXTURE_DIR;

  // Registered benchmarks keep pointers into these.
  static std::vector<Case> All;
  static std::map<std::string, std::vector<Case>> ByEntity;
  try {
    std::vector<std::filesystem::path> Files;
    for (const auto &Entry : std::filesystem::directory_iterator(Dir))
      if (Entry.path().extension() == ".bin")
        Files.push_back(Entry.path());
    std::sort(Files.begin(), Files.end());
    for (const auto &Path : Files)
      load(Path, All);
  } catch (const std::exception &Error) {
    std::cerr << "maestro-bench: " << Error.what() << '\n';
    return 1;
  }
  if (All.empty()) {
    std::cerr << "maestro-bench: no parity cases in " << Dir << '\n';
    return 1;
  }
  for (const Case &Recorded : All)
    ByEntity[Recorded.Entity].push_back(Recorded);

  const auto Workers = static_cast<int>(maestro::cli::defaultWorkers());
  auto *Mix = benchmark::RegisterBenchmark("search/all", searchCases, &All)
                  ->UseRealTime()
                  ->Threads(1);
  if (Workers > 1)
    Mix->Threads(Workers);
  for (const auto &[Entity, Cases] : ByEntity)
    benchmark::RegisterBenchmark("search/" + Entity, searchCases, &Cases)
        ->UseRealTime();

  benchmark::RunSpecifiedBenchmarks();
  benchmark::Shutdown();
  return 0;
}
