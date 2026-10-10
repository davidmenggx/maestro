#include "maestro/world/world_store.hpp"

#include <algorithm>
#include <cstdint>
#include <limits>
#include <utility>
#include <vector>

#include <flatbuffers/flatbuffers.h>

#include "control_generated.h"

namespace maestro::world {

namespace {

/// Decodes one chunk's runs, or returns null if they break the contract:
/// one column per position, every column at full height, and only per-block
/// types.
ChunkPtr decodeChunk(const fbs::ChunkSnapshot &Snapshot, int Height,
                     bool &Malformed) {
  Malformed = false;
  const auto *Counts = Snapshot.run_counts();
  const auto *Runs = Snapshot.runs();
  if (!Counts || Counts->size() == 0) {
    Malformed = Runs && Runs->size() != 0;
    return nullptr;
  }
  if (Counts->size() != ColumnsPerChunk || !Runs) {
    Malformed = true;
    return nullptr;
  }

  auto Content = std::make_shared<Chunk>();
  std::size_t Offset = 0;
  for (int Index = 0; Index < ColumnsPerChunk; ++Index) {
    const std::size_t Count = Counts->Get(Index);
    if (Count == 0 || Offset + Count > Runs->size()) {
      Malformed = true;
      return nullptr;
    }
    std::vector<Run> ColumnRuns;
    ColumnRuns.reserve(Count);
    int End = 0;
    for (std::size_t I = 0; I < Count; ++I) {
      const fbs::CellRun *Wire = Runs->Get(Offset + I);
      End += Wire->length();
      if (Wire->length() == 0 || End > Height ||
          !isPerBlock(Wire->node_type())) {
        Malformed = true;
        return nullptr;
      }
      ColumnRuns.push_back(Run{static_cast<std::uint16_t>(End),
                               Cell{Wire->node_type(), Wire->floor()}});
    }
    if (End != Height) {
      Malformed = true;
      return nullptr;
    }
    Offset += Count;

    // Neighbouring columns are often identical, so share one copy.
    if (Index > 0 &&
        std::ranges::equal((*Content)[Index - 1]->runs(), ColumnRuns))
      (*Content)[Index] = (*Content)[Index - 1];
    else
      (*Content)[Index] = std::make_shared<const Column>(std::move(ColumnRuns));
  }
  if (Offset != Runs->size()) {
    Malformed = true;
    return nullptr;
  }
  return Content;
}

} // namespace

ApplyStatus WorldStore::apply(std::span<const std::uint8_t> Frame) {
  const ApplyStatus Status = applyFrame(Frame);
  if (Status != ApplyStatus::Applied) {
    Current.store(nullptr);
    AwaitingBaseline = false;
  }
  return Status;
}

ApplyStatus WorldStore::applyFrame(std::span<const std::uint8_t> Frame) {
  flatbuffers::Verifier Verifier(Frame.data(), Frame.size());
  if (!fbs::VerifyWorldFrameBuffer(Verifier))
    return ApplyStatus::Malformed;

  const auto *Root = fbs::GetWorldFrame(Frame.data());
  switch (Root->body_type()) {
  case fbs::WorldMsg::RegionAssignment:
    if (const auto *Body = Root->body_as_RegionAssignment())
      return assign(*Body);
    break;
  case fbs::WorldMsg::ChunkSnapshot:
    if (const auto *Body = Root->body_as_ChunkSnapshot())
      return applyChunk(*Body);
    break;
  case fbs::WorldMsg::WorldDelta:
    if (const auto *Body = Root->body_as_WorldDelta())
      return applyDelta(*Body);
    break;
  default:
    break;
  }
  return ApplyStatus::Malformed;
}

ApplyStatus WorldStore::assign(const fbs::RegionAssignment &Assignment) {
  const Geometry Geo{Assignment.size(), Assignment.min_y(),
                     Assignment.height()};
  // Wire positions are shorts, so the area must fit in one.
  constexpr int ShortMin = std::numeric_limits<std::int16_t>::min();
  constexpr int ShortMax = std::numeric_limits<std::int16_t>::max();
  if (Geo.Size <= 0 || Geo.Size % ChunkSize != 0 || Geo.Size > ShortMax ||
      Geo.Height <= 0 || Geo.MinY < ShortMin ||
      Geo.MinY + Geo.Height - 1 > ShortMax)
    return ApplyStatus::Malformed;

  if (Assignment.resume()) {
    // Resuming keeps the held state, so it must be the stream the controller
    // is continuing.
    const auto Held = pin();
    if (!Held || Held->sequence() == 0 || Held->run() != Assignment.run() ||
        Held->regionId() != Assignment.region_id() || Held->geometry() != Geo)
      return ApplyStatus::CannotResume;
    return ApplyStatus::Applied;
  }

  AwaitingBaseline = true;
  Current.store(std::make_shared<const Area>(Geo, Assignment.run(),
                                             Assignment.region_id()));
  return ApplyStatus::Applied;
}

ApplyStatus WorldStore::applyChunk(const fbs::ChunkSnapshot &Snapshot) {
  const auto Held = pin();
  if (!Held)
    return ApplyStatus::Unassigned;

  const auto *Position = Snapshot.chunk_xz();
  const int PerSide = Held->geometry().chunksPerSide();
  if (!Position || Position->x() < 0 || Position->x() >= PerSide ||
      Position->z() < 0 || Position->z() >= PerSide)
    return ApplyStatus::Malformed;

  bool Malformed = false;
  ChunkPtr Content = decodeChunk(Snapshot, Held->geometry().Height, Malformed);
  if (Malformed)
    return ApplyStatus::Malformed;

  if (const ApplyStatus Status = admit(Snapshot.sequence());
      Status != ApplyStatus::Applied)
    return Status;
  AwaitingBaseline = false;
  Current.store(Held->withChunk(Position->x(), Position->z(),
                                std::move(Content), Snapshot.sequence()));
  return ApplyStatus::Applied;
}

ApplyStatus WorldStore::applyDelta(const fbs::WorldDelta &Delta) {
  const auto Held = pin();
  if (!Held)
    return ApplyStatus::Unassigned;

  std::vector<CellEdit> Edits;
  if (const auto *Cells = Delta.cells()) {
    Edits.reserve(Cells->size());
    for (const fbs::CellDelta *Wire : *Cells) {
      const CellEdit Edit{Wire->pos().x(), Wire->pos().y(), Wire->pos().z(),
                          Cell{Wire->node_type(), Wire->floor()}};
      if (!Held->geometry().contains(Edit.X, Edit.Y, Edit.Z) ||
          !isPerBlock(Edit.Value.Type))
        return ApplyStatus::Malformed;
      Edits.push_back(Edit);
    }
  }

  if (const ApplyStatus Status = admit(Delta.sequence());
      Status != ApplyStatus::Applied)
    return Status;
  AwaitingBaseline = false;
  Current.store(Held->withCells(Edits, Delta.sequence()));
  return ApplyStatus::Applied;
}

ApplyStatus WorldStore::admit(std::uint64_t Sequence) const {
  if (Sequence == 0)
    return ApplyStatus::Malformed;
  if (AwaitingBaseline)
    return ApplyStatus::Applied;
  return Sequence == pin()->sequence() + 1 ? ApplyStatus::Applied
                                           : ApplyStatus::OutOfSequence;
}

} // namespace maestro::world
