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

int chunkIndex(const Geometry &Geo, int X, int Z) {
  return (X / ChunkSize) * Geo.chunksPerSide() + Z / ChunkSize;
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

Area::Area(Geometry Geo, std::uint64_t Sequence)
    : Geo(Geo), Sequence(Sequence),
      Chunks(static_cast<std::size_t>(Geo.chunksPerSide()) *
             Geo.chunksPerSide()),
      AirColumn(std::make_shared<const Column>(
          std::vector<Run>{Run{static_cast<std::uint16_t>(Geo.Height), Air}})) {
}

Cell Area::at(int X, int Y, int Z) const {
  if (!Geo.contains(X, Y, Z))
    return Air;
  const ChunkPtr &Content = Chunks[chunkIndex(Geo, X, Z)];
  if (!Content)
    return Air;
  return (*Content)[columnIndex(X, Z)]->at(Y - Geo.MinY);
}

const Column *Area::column(int X, int Z) const {
  if (X < 0 || X >= Geo.Size || Z < 0 || Z >= Geo.Size)
    return nullptr;
  const ChunkPtr &Content = Chunks[chunkIndex(Geo, X, Z)];
  return Content ? (*Content)[columnIndex(X, Z)].get() : nullptr;
}

std::shared_ptr<const Area> Area::withChunk(int ChunkX, int ChunkZ,
                                            ChunkPtr Content,
                                            std::uint64_t Sequence) const {
  assert(ChunkX >= 0 && ChunkX < Geo.chunksPerSide() && ChunkZ >= 0 &&
         ChunkZ < Geo.chunksPerSide() && "chunk outside the area");
  auto Next = std::make_shared<Area>(*this);
  Next->Sequence = Sequence;
  Next->Chunks[ChunkX * Geo.chunksPerSide() + ChunkZ] = std::move(Content);
  return Next;
}

std::shared_ptr<const Area> Area::withCells(std::span<const CellEdit> Edits,
                                            std::uint64_t Sequence) const {
  auto Next = std::make_shared<Area>(*this);
  Next->Sequence = Sequence;

  // Chunks copied for this version so far. A delta touches few chunks, so a
  // linear scan beats a map.
  std::vector<std::pair<int, std::shared_ptr<Chunk>>> Copied;
  for (const CellEdit &Edit : Edits) {
    assert(Geo.contains(Edit.X, Edit.Y, Edit.Z) && "edit outside the area");
    const int Index = chunkIndex(Geo, Edit.X, Edit.Z);
    auto It =
        std::find_if(Copied.begin(), Copied.end(),
                     [&](const auto &Entry) { return Entry.first == Index; });
    if (It == Copied.end()) {
      // A cleared chunk is materialized as air, so an edit to it still lands.
      const ChunkPtr &Old = Chunks[Index];
      auto Fresh = std::make_shared<Chunk>();
      if (Old)
        *Fresh = *Old;
      else
        Fresh->fill(AirColumn);
      Next->Chunks[Index] = Fresh;
      It = Copied.emplace(Copied.end(), Index, std::move(Fresh));
    }
    ColumnPtr &Target = (*It->second)[columnIndex(Edit.X, Edit.Z)];
    Target = Target->with(Edit.Y - Geo.MinY, Edit.Value);
  }
  return Next;
}

} // namespace maestro::world
