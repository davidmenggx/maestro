#include "maestro/world/area.hpp"

#include <algorithm>
#include <cassert>
#include <cstdint>
#include <utility>
#include <vector>

namespace maestro::world {

namespace {

/// Appends `R`, merging it into the previous run if the value is the same.
void appendMerged(std::vector<Run> &Runs, Run R) {
  if (!Runs.empty() && Runs.back().Value == R.Value)
    Runs.back().End = R.End;
  else
    Runs.push_back(R);
}

int columnIndex(int X, int Z) {
  return (X % ChunkSize) * ChunkSize + Z % ChunkSize;
}

/// Orders the indices below `Count` by `Key`, whose values are below
/// `Ends.size()`, into `Out`, keeping their order within a key. Afterwards the
/// indices of key `K` end at `Ends[K]` and begin where those of `K - 1` end.
template <typename KeyOf>
void groupBy(std::uint32_t Count, std::uint32_t *Out,
             std::vector<std::uint32_t> &Ends, KeyOf Key) {
  for (std::uint32_t I = 0; I < Count; ++I)
    ++Ends[Key(I)];
  std::uint32_t Begin = 0;
  for (std::uint32_t &Slot : Ends)
    Begin += std::exchange(Slot, Begin);
  for (std::uint32_t I = 0; I < Count; ++I)
    Out[Ends[Key(I)]++] = I;
}

} // namespace

Cell Column::at(int Offset) const {
  const auto It =
      std::upper_bound(Runs.begin(), Runs.end(), Offset,
                       [](int Value, const Run &R) { return Value < R.End; });
  assert(It != Runs.end() && "offset past the top of the column");
  return It->Value;
}

std::shared_ptr<const Column> Column::with(int Offset, Cell Value) const {
  const ColumnEdit Edit{Offset, Value};
  return with({&Edit, 1});
}

std::shared_ptr<const Column>
Column::with(std::span<const ColumnEdit> Edits) const {
  std::vector<Run> Out;
  Out.reserve(Runs.size() + 2 * Edits.size());
  auto Edit = Edits.begin();
  int Start = 0;
  for (const Run &R : Runs) {
    // The first offset of `R` not yet written.
    int Next = Start;
    for (; Edit != Edits.end() && Edit->Offset < R.End; ++Edit) {
      assert(Edit->Offset >= Next && "edits out of order");
      if (Edit->Offset > Next)
        appendMerged(Out,
                     Run{static_cast<std::uint16_t>(Edit->Offset), R.Value});
      appendMerged(
          Out, Run{static_cast<std::uint16_t>(Edit->Offset + 1), Edit->Value});
      Next = Edit->Offset + 1;
    }
    if (Next < R.End)
      appendMerged(Out, R);
    Start = R.End;
  }
  assert(Edit == Edits.end() && "offset past the top of the column");
  return std::make_shared<const Column>(std::move(Out));
}

Area::Area(Geometry Geo, std::uint64_t ControllerRun, std::uint32_t RegionId,
           std::uint64_t Sequence)
    : Geo(Geo), ControllerRun(ControllerRun), RegionId(RegionId),
      Sequence(Sequence), Rows(static_cast<std::size_t>(Geo.chunksPerSide())),
      AirColumn(std::make_shared<const Column>(
          std::vector<Run>{Run{static_cast<std::uint16_t>(Geo.Height), Air}})) {
}

const ChunkPtr *Area::chunkAt(int X, int Z) const {
  const RowPtr &Row = Rows[X / ChunkSize];
  return Row ? &(*Row)[Z / ChunkSize] : nullptr;
}

Cell Area::at(int X, int Y, int Z) const {
  if (!Geo.contains(X, Y, Z))
    return Air;
  const ChunkPtr *Content = chunkAt(X, Z);
  if (!Content || !*Content)
    return Air;
  return (**Content)[columnIndex(X, Z)]->at(Y - Geo.MinY);
}

const Column *Area::column(int X, int Z) const {
  if (X < 0 || X >= Geo.Size || Z < 0 || Z >= Geo.Size)
    return nullptr;
  const ChunkPtr *Content = chunkAt(X, Z);
  return Content && *Content ? (**Content)[columnIndex(X, Z)].get() : nullptr;
}

std::shared_ptr<Area::ChunkRow> Area::copyRow(int ChunkX) const {
  const RowPtr &Old = Rows[ChunkX];
  return Old ? std::make_shared<ChunkRow>(*Old)
             : std::make_shared<ChunkRow>(
                   static_cast<std::size_t>(Geo.chunksPerSide()));
}

std::shared_ptr<const Area> Area::withChunk(int ChunkX, int ChunkZ,
                                            ChunkPtr Content,
                                            std::uint64_t Sequence) const {
  assert(ChunkX >= 0 && ChunkX < Geo.chunksPerSide() && ChunkZ >= 0 &&
         ChunkZ < Geo.chunksPerSide() && "chunk outside the area");
  auto Next = std::make_shared<Area>(*this);
  Next->Sequence = Sequence;
  auto Row = copyRow(ChunkX);
  (*Row)[ChunkZ] = std::move(Content);
  Next->Rows[ChunkX] = std::move(Row);
  return Next;
}

std::shared_ptr<const Area> Area::withCells(std::span<const CellEdit> Edits,
                                            std::uint64_t Sequence) const {
  auto Next = std::make_shared<Area>(*this);
  Next->Sequence = Sequence;
  const int PerSide = Geo.chunksPerSide();

  // The edits by chunk, so every row and chunk is copied once.
  std::vector<std::uint32_t> ByChunk(Edits.size());
  std::vector<std::uint32_t> ChunkEnds(static_cast<std::size_t>(PerSide) *
                                       PerSide);
  groupBy(static_cast<std::uint32_t>(Edits.size()), ByChunk.data(), ChunkEnds,
          [&](std::uint32_t I) {
            assert(Geo.contains(Edits[I].X, Edits[I].Y, Edits[I].Z) &&
                   "edit outside the area");
            return Edits[I].X / ChunkSize * PerSide + Edits[I].Z / ChunkSize;
          });

  // Rows already copied for this version, by `ChunkX`.
  std::vector<ChunkRow *> CopiedRows(static_cast<std::size_t>(PerSide));
  std::vector<std::uint64_t> Keys;
  std::vector<ColumnEdit> ColumnEdits;
  std::uint32_t ChunkBegin = 0;
  for (std::size_t ChunkIndex = 0; ChunkIndex < ChunkEnds.size();
       ++ChunkIndex) {
    const std::span<const std::uint32_t> InChunk(
        ByChunk.data() + ChunkBegin, ChunkEnds[ChunkIndex] - ChunkBegin);
    ChunkBegin = ChunkEnds[ChunkIndex];
    if (InChunk.empty())
      continue;

    const int ChunkX = static_cast<int>(ChunkIndex) / PerSide;
    const int ChunkZ = static_cast<int>(ChunkIndex) % PerSide;
    ChunkRow *&Row = CopiedRows[ChunkX];
    if (!Row) {
      auto Copy = copyRow(ChunkX);
      Row = Copy.get();
      Next->Rows[ChunkX] = std::move(Copy);
    }
    // A cleared chunk becomes air, so an edit to it still applies.
    ChunkPtr &Slot = (*Row)[ChunkZ];
    auto Content = std::make_shared<Chunk>();
    if (Slot)
      *Content = *Slot;
    else
      Content->fill(AirColumn);

    // Each edit keyed by its column, then its offset, which fits 16 bits as
    // the height does, then its place in the delta. Sorted, the edits of one
    // column are adjacent and a cell's last edit comes last.
    Keys.clear();
    for (const std::uint32_t I : InChunk) {
      const auto Column =
          static_cast<std::uint64_t>(columnIndex(Edits[I].X, Edits[I].Z));
      const auto Offset = static_cast<std::uint64_t>(Edits[I].Y - Geo.MinY);
      Keys.push_back((Column << 16 | Offset) << 32 | I);
    }
    std::sort(Keys.begin(), Keys.end());
    for (std::size_t Begin = 0; Begin < Keys.size();) {
      const std::uint64_t Column = Keys[Begin] >> 48;
      ColumnEdits.clear();
      std::size_t End = Begin;
      for (; End < Keys.size() && Keys[End] >> 48 == Column; ++End) {
        const auto Offset = static_cast<int>(Keys[End] >> 32 & 0xFFFF);
        const Cell Value = Edits[Keys[End] & 0xFFFFFFFF].Value;
        if (!ColumnEdits.empty() && ColumnEdits.back().Offset == Offset)
          ColumnEdits.back().Value = Value;
        else
          ColumnEdits.push_back({Offset, Value});
      }
      ColumnPtr &Target = (*Content)[Column];
      Target = Target->with(ColumnEdits);
      Begin = End;
    }
    Slot = std::move(Content);
  }
  return Next;
}

} // namespace maestro::world
