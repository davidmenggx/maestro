#include "maestro/world/area.hpp"

#include <algorithm>
#include <cassert>
#include <compare>
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

/// One edit of a delta, keyed so sorting groups edits by chunk, then column,
/// with a cell's last edit last.
struct EditKey {
  /// `ChunkX * chunksPerSide + ChunkZ`.
  std::uint32_t Chunk;
  /// The column within the chunk, then the offset, which fits 16 bits as the
  /// height does.
  std::uint32_t Cell;
  /// The edit's place in the delta.
  std::uint32_t Index;

  std::uint32_t column() const { return Cell >> 16; }
  int offset() const { return static_cast<int>(Cell & 0xFFFF); }

  friend auto operator<=>(const EditKey &, const EditKey &) = default;
};

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
  if (std::ranges::all_of(Edits, [this](const ColumnEdit &Edit) {
        return at(Edit.Offset) == Edit.Value;
      }))
    return shared_from_this();
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

  std::vector<EditKey> Keys;
  Keys.reserve(Edits.size());
  for (std::size_t I = 0; I < Edits.size(); ++I) {
    const CellEdit &Edit = Edits[I];
    assert(Geo.contains(Edit.X, Edit.Y, Edit.Z) && "edit outside the area");
    const auto Column = static_cast<std::uint32_t>(columnIndex(Edit.X, Edit.Z));
    const auto Offset = static_cast<std::uint32_t>(Edit.Y - Geo.MinY);
    Keys.push_back({static_cast<std::uint32_t>(Edit.X / ChunkSize * PerSide +
                                               Edit.Z / ChunkSize),
                    Column << 16 | Offset, static_cast<std::uint32_t>(I)});
  }
  std::sort(Keys.begin(), Keys.end());

  // The row copied for this version, if any. A row's chunks are adjacent.
  ChunkRow *Row = nullptr;
  int RowX = -1;
  std::vector<ColumnEdit> ColumnEdits;
  for (std::size_t Begin = 0; Begin < Keys.size();) {
    const std::uint32_t ChunkIndex = Keys[Begin].Chunk;
    const int ChunkX = static_cast<int>(ChunkIndex) / PerSide;
    const int ChunkZ = static_cast<int>(ChunkIndex) % PerSide;
    // Null for a chunk with no state, which reads as air, so an edit to it
    // still applies.
    const Chunk *Old = Rows[ChunkX] ? (*Rows[ChunkX])[ChunkZ].get() : nullptr;
    // Copied once a column changes.
    std::shared_ptr<Chunk> Content;
    while (Begin < Keys.size() && Keys[Begin].Chunk == ChunkIndex) {
      const std::uint32_t Column = Keys[Begin].column();
      ColumnEdits.clear();
      for (; Begin < Keys.size() && Keys[Begin].Chunk == ChunkIndex &&
             Keys[Begin].column() == Column;
           ++Begin) {
        const int Offset = Keys[Begin].offset();
        const Cell Value = Edits[Keys[Begin].Index].Value;
        if (!ColumnEdits.empty() && ColumnEdits.back().Offset == Offset)
          ColumnEdits.back().Value = Value;
        else
          ColumnEdits.push_back({Offset, Value});
      }
      const ColumnPtr &Base = Old ? (*Old)[Column] : AirColumn;
      ColumnPtr Edited = Base->with(ColumnEdits);
      if (Edited == Base)
        continue;
      if (!Content) {
        Content = std::make_shared<Chunk>();
        if (Old)
          *Content = *Old;
        else
          Content->fill(AirColumn);
      }
      (*Content)[Column] = std::move(Edited);
    }
    if (!Content)
      continue;
    if (RowX != ChunkX) {
      auto Copy = copyRow(ChunkX);
      Row = Copy.get();
      Next->Rows[ChunkX] = std::move(Copy);
      RowX = ChunkX;
    }
    (*Row)[ChunkZ] = std::move(Content);
  }
  return Next;
}

} // namespace maestro::world
