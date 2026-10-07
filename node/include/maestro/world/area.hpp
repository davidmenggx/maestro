#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <span>
#include <vector>

#include "maestro/world/cell.hpp"

namespace maestro::world {

/// Block width of a chunk along both horizontal axes.
inline constexpr int ChunkSize = 16;
inline constexpr int ColumnsPerChunk = ChunkSize * ChunkSize;

/// Shape of an assigned area. Positions use the wire's frame: `X` and `Z`
/// relative to the area's corner, `Y` absolute.
struct Geometry {
  /// Blocks per side, a multiple of `ChunkSize`.
  int Size = 0;
  int MinY = 0;
  int Height = 0;

  int chunksPerSide() const { return Size / ChunkSize; }

  bool contains(int X, int Y, int Z) const {
    return X >= 0 && X < Size && Z >= 0 && Z < Size && Y >= MinY &&
           Y < MinY + Height;
  }

  friend bool operator==(const Geometry &, const Geometry &) = default;
};

/// Vertically contiguous cells sharing one value. `End` is the offset above
/// the area's `MinY` where the run stops, exclusive.
struct Run {
  std::uint16_t End = 0;
  Cell Value;

  friend bool operator==(Run, Run) = default;
};

/// One full-height column, run-length encoded. Immutable once built, so
/// versions of an area can share it.
class Column {
public:
  /// `Runs` must be ordered, non-empty, and end at the area's height.
  explicit Column(std::vector<Run> Runs) : Runs(std::move(Runs)) {}

  /// `Offset` counts up from the area's `MinY` and is below its height.
  Cell at(int Offset) const;

  /// A copy with the cell at `Offset` replaced and equal neighbours merged.
  std::shared_ptr<const Column> with(int Offset, Cell Value) const;

  std::span<const Run> runs() const { return Runs; }

private:
  std::vector<Run> Runs;
};

using ColumnPtr = std::shared_ptr<const Column>;

/// The columns of one chunk, indexed `X * ChunkSize + Z` within the chunk.
using Chunk = std::array<ColumnPtr, ColumnsPerChunk>;
using ChunkPtr = std::shared_ptr<const Chunk>;

/// One block change, positioned as on the wire.
struct CellEdit {
  int X = 0;
  int Y = 0;
  int Z = 0;
  Cell Value;
};

/// One immutable version of an assigned area, as of a world stream sequence.
/// A new version shares every chunk and column it did not change, so a search
/// can hold a version for as long as it runs while newer ones are published.
/// A chunk with no state reads as air, as does anything outside the area.
///
/// A version also names the stream it belongs to, so `run`, `regionId` and
/// `sequence` read from one version always describe the same stream position.
class Area {
public:
  /// An area that is air throughout, before the first frame of the stream
  /// `ControllerRun` and `RegionId` name.
  Area(Geometry Geo, std::uint64_t ControllerRun, std::uint32_t RegionId);

  const Geometry &geometry() const { return Geo; }

  /// Controller run the area was assigned by.
  std::uint64_t run() const { return ControllerRun; }

  std::uint32_t regionId() const { return RegionId; }

  /// Sequence of the last world frame applied, or 0 before the first.
  std::uint64_t sequence() const { return Sequence; }

  Cell at(int X, int Y, int Z) const;

  /// The column at `X`, `Z`, or null where it reads as air. Lets versions be
  /// checked for what they share.
  const Column *column(int X, int Z) const;

  /// A version with one chunk replaced. A null `Content` clears it to air.
  std::shared_ptr<const Area> withChunk(int ChunkX, int ChunkZ,
                                        ChunkPtr Content,
                                        std::uint64_t Sequence) const;

  /// A version with `Edits` applied in order. Every edit must lie inside the
  /// area; one landing in a chunk with no state starts that chunk from air.
  std::shared_ptr<const Area> withCells(std::span<const CellEdit> Edits,
                                        std::uint64_t Sequence) const;

private:
  /// The chunks at one `ChunkX`, indexed by `ChunkZ`. A null chunk reads as
  /// air.
  using ChunkRow = std::vector<ChunkPtr>;
  using RowPtr = std::shared_ptr<const ChunkRow>;

  /// A writable copy of the row at `ChunkX`, or a row of air if it has none.
  std::shared_ptr<ChunkRow> copyRow(int ChunkX) const;

  const ChunkPtr *chunkAt(int X, int Z) const;

  Geometry Geo;
  std::uint64_t ControllerRun;
  std::uint32_t RegionId;
  std::uint64_t Sequence = 0;
  /// Indexed by `ChunkX`. A frame copies this table and the rows it touches,
  /// so a one-chunk change copies about two rows of pointers rather than one
  /// pointer per chunk in the area. A null row reads as air.
  std::vector<RowPtr> Rows;
  /// Shared by every chunk that is materialized from air.
  ColumnPtr AirColumn;
};

} // namespace maestro::world
