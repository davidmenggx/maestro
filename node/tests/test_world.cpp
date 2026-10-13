#include "test_world.hpp"

#include <cassert>

#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include "control_generated.h"
#include "maestro/world/world_store.hpp"

namespace maestro::test {

namespace {

Frame finish(flatbuffers::FlatBufferBuilder &Builder) {
  return {Builder.GetBufferPointer(),
          Builder.GetBufferPointer() + Builder.GetSize()};
}

} // namespace

DenseWorld::DenseWorld(int Size, int MinY, int Height)
    : Geo{Size, MinY, Height},
      Cells(static_cast<std::size_t>(Size) * Size * Height, world::Air) {
  assert(Size % world::ChunkSize == 0 && "the area must tile into chunks");
}

std::size_t DenseWorld::index(int X, int Y, int Z) const {
  assert(Geo.contains(X, Y, Z) && "position outside the test world");
  return (static_cast<std::size_t>(X) * Geo.Size + Z) * Geo.Height +
         (Y - Geo.MinY);
}

world::Cell DenseWorld::at(int X, int Y, int Z) const {
  return Cells[index(X, Y, Z)];
}

void DenseWorld::set(int X, int Y, int Z, world::Cell Value) {
  Cells[index(X, Y, Z)] = Value;
}

void DenseWorld::fill(int X0, int Y0, int Z0, int X1, int Y1, int Z1,
                      world::Cell Value) {
  for (int X = X0; X <= X1; ++X)
    for (int Y = Y0; Y <= Y1; ++Y)
      for (int Z = Z0; Z <= Z1; ++Z)
        set(X, Y, Z, Value);
}

Frame DenseWorld::assignment(bool Resume, std::uint64_t Run,
                             std::uint32_t RegionId,
                             std::uint64_t Sequence) const {
  flatbuffers::FlatBufferBuilder Builder;
  const auto Body = fbs::CreateRegionAssignment(
      Builder, Run, RegionId, static_cast<std::uint16_t>(Geo.Size), Geo.MinY,
      static_cast<std::uint16_t>(Geo.Height), Resume, Sequence);
  Builder.Finish(fbs::CreateWorldFrame(Builder, fbs::WorldMsg::RegionAssignment,
                                       Body.Union()));
  return finish(Builder);
}

Frame DenseWorld::chunk(std::uint64_t Sequence, int ChunkX, int ChunkZ) const {
  std::vector<std::uint16_t> RunCounts;
  std::vector<fbs::CellRun> Runs;
  for (int Dx = 0; Dx < world::ChunkSize; ++Dx) {
    for (int Dz = 0; Dz < world::ChunkSize; ++Dz) {
      const int X = ChunkX * world::ChunkSize + Dx;
      const int Z = ChunkZ * world::ChunkSize + Dz;
      const std::size_t Before = Runs.size();
      world::Cell Current = at(X, Geo.MinY, Z);
      int Length = 0;
      for (int Y = Geo.MinY; Y < Geo.MinY + Geo.Height; ++Y) {
        const world::Cell Here = at(X, Y, Z);
        if (Length > 0 && Here != Current) {
          Runs.emplace_back(static_cast<std::uint16_t>(Length), Current.Type,
                            Current.Floor);
          Length = 0;
        }
        Current = Here;
        ++Length;
      }
      Runs.emplace_back(static_cast<std::uint16_t>(Length), Current.Type,
                        Current.Floor);
      RunCounts.push_back(static_cast<std::uint16_t>(Runs.size() - Before));
    }
  }

  flatbuffers::FlatBufferBuilder Builder;
  const auto CountsVec = Builder.CreateVector(RunCounts);
  const auto RunsVec = Builder.CreateVectorOfStructs(Runs);
  const fbs::Vec2i ChunkXz(ChunkX, ChunkZ);
  fbs::ChunkSnapshotBuilder Snapshot(Builder);
  Snapshot.add_sequence(Sequence);
  Snapshot.add_chunk_xz(&ChunkXz);
  Snapshot.add_run_counts(CountsVec);
  Snapshot.add_runs(RunsVec);
  const auto Body = Snapshot.Finish();
  Builder.Finish(fbs::CreateWorldFrame(Builder, fbs::WorldMsg::ChunkSnapshot,
                                       Body.Union()));
  return finish(Builder);
}

std::shared_ptr<const world::Area> DenseWorld::load() const {
  world::WorldStore Store;
  EXPECT_EQ(Store.apply(assignment()), world::ApplyStatus::Applied);
  std::uint64_t Sequence = 1;
  for (int ChunkX = 0; ChunkX < Geo.chunksPerSide(); ++ChunkX)
    for (int ChunkZ = 0; ChunkZ < Geo.chunksPerSide(); ++ChunkZ)
      EXPECT_EQ(Store.apply(chunk(Sequence++, ChunkX, ChunkZ)),
                world::ApplyStatus::Applied);
  return Store.pin();
}

Frame clearedChunk(std::uint64_t Sequence, int ChunkX, int ChunkZ) {
  flatbuffers::FlatBufferBuilder Builder;
  const fbs::Vec2i ChunkXz(ChunkX, ChunkZ);
  fbs::ChunkSnapshotBuilder Snapshot(Builder);
  Snapshot.add_sequence(Sequence);
  Snapshot.add_chunk_xz(&ChunkXz);
  const auto Body = Snapshot.Finish();
  Builder.Finish(fbs::CreateWorldFrame(Builder, fbs::WorldMsg::ChunkSnapshot,
                                       Body.Union()));
  return finish(Builder);
}

Frame delta(std::uint64_t Sequence, std::span<const world::CellEdit> Edits) {
  std::vector<fbs::CellDelta> Cells;
  for (const world::CellEdit &Edit : Edits)
    Cells.emplace_back(fbs::Vec3s(static_cast<std::int16_t>(Edit.X),
                                  static_cast<std::int16_t>(Edit.Y),
                                  static_cast<std::int16_t>(Edit.Z)),
                       Edit.Value.Type, Edit.Value.Floor);
  flatbuffers::FlatBufferBuilder Builder;
  const auto Body = fbs::CreateWorldDelta(Builder, Sequence,
                                          Builder.CreateVectorOfStructs(Cells));
  Builder.Finish(
      fbs::CreateWorldFrame(Builder, fbs::WorldMsg::WorldDelta, Body.Union()));
  return finish(Builder);
}

} // namespace maestro::test
