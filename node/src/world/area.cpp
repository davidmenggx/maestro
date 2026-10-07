#include "maestro/world/area.hpp"

#include <algorithm>
#include <cassert>
#include <utility>

namespace maestro::world {

namespace {

/// Appends `R`, extending the previous run instead when it holds the same
/// value.
void appendMerged(std::vector<Run> &Runs, Run R) {
  if (!Runs.empty() && Runs.back().Value == R.Value)
    Runs.back().End = R.End;
  else
    Runs.push_back(R);
}

int columnIndex(int X, int Z) {
  return (X % ChunkSize) * ChunkSize + Z % ChunkSize;
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
  std::vector<Run> Out;
  Out.reserve(Runs.size() + 2);
  int Start = 0;
  for (const Run &R : Runs) {
    if (Offset >= Start && Offset < R.End) {
      if (Offset > Start)
        appendMerged(Out, Run{static_cast<std::uint16_t>(Offset), R.Value});
      appendMerged(Out, Run{static_cast<std::uint16_t>(Offset + 1), Value});
      if (Offset + 1 < R.End)
        appendMerged(Out, R);
    } else {
      appendMerged(Out, R);
    }
    Start = R.End;
  }
  return std::make_shared<const Column>(std::move(Out));
}

Area::Area(Geometry Geo, std::uint64_t ControllerRun, std::uint32_t RegionId)
    : Geo(Geo), ControllerRun(ControllerRun), RegionId(RegionId),
      Rows(static_cast<std::size_t>(Geo.chunksPerSide())),
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

  // Rows and chunks copied for this version so far. A delta touches few, so a
  // linear scan beats a map.
  std::vector<std::pair<int, std::shared_ptr<ChunkRow>>> CopiedRows;
  std::vector<std::pair<int, std::shared_ptr<Chunk>>> CopiedChunks;
  for (const CellEdit &Edit : Edits) {
    assert(Geo.contains(Edit.X, Edit.Y, Edit.Z) && "edit outside the area");
    const int ChunkX = Edit.X / ChunkSize;
    const int ChunkZ = Edit.Z / ChunkSize;

    auto RowIt =
        std::find_if(CopiedRows.begin(), CopiedRows.end(),
                     [&](const auto &Entry) { return Entry.first == ChunkX; });
    if (RowIt == CopiedRows.end()) {
      auto Row = copyRow(ChunkX);
      Next->Rows[ChunkX] = Row;
      RowIt = CopiedRows.emplace(CopiedRows.end(), ChunkX, std::move(Row));
    }

    const int Index = ChunkX * Geo.chunksPerSide() + ChunkZ;
    auto ChunkIt =
        std::find_if(CopiedChunks.begin(), CopiedChunks.end(),
                     [&](const auto &Entry) { return Entry.first == Index; });
    if (ChunkIt == CopiedChunks.end()) {
      // A cleared chunk is materialized as air, so an edit to it still lands.
      ChunkPtr &Slot = (*RowIt->second)[ChunkZ];
      auto Fresh = std::make_shared<Chunk>();
      if (Slot)
        *Fresh = *Slot;
      else
        Fresh->fill(AirColumn);
      Slot = Fresh;
      ChunkIt =
          CopiedChunks.emplace(CopiedChunks.end(), Index, std::move(Fresh));
    }

    ColumnPtr &Target = (*ChunkIt->second)[columnIndex(Edit.X, Edit.Z)];
    Target = Target->with(Edit.Y - Geo.MinY, Edit.Value);
  }
  return Next;
}

} // namespace maestro::world
