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
  /// Blocks along each horizontal axis, multiples of `ChunkSize`.
  int SizeX = 0;
  int SizeZ = 0;
  int MinY = 0;
  int Height = 0;

  int chunksX() const { return SizeX / ChunkSize; }
  int chunksZ() const { return SizeZ / ChunkSize; }

  bool contains(int X, int Y, int Z) const {
    return X >= 0 && X < SizeX && Z >= 0 && Z < SizeZ && Y >= MinY &&
           Y < MinY + Height;
  }

  /// Whether an area of shape `Other` with the same corner lies inside.
  bool holds(const Geometry &Other) const {
    return Other.SizeX <= SizeX && Other.SizeZ <= SizeZ && Other.MinY == MinY &&
           Other.Height == Height;
  }

  friend bool operator==(const Geometry &, const Geometry &) = default;
};

/// Vertically contiguous cells with one value. `End` is the exclusive end of
/// the run, as an offset above the area's `MinY`.
struct Run {
  std::uint16_t End = 0;
  Cell Value;

  friend bool operator==(Run, Run) = default;
};

/// One cell to set in a column, `Offset` counting up from the area's `MinY`.
struct ColumnEdit {
  int Offset = 0;
  Cell Value;
};

/// One full-height column, run-length encoded. Immutable, so versions of an
/// area can share it.
class Column : public std::enable_shared_from_this<Column> {
public:
  /// `Runs` must be ordered, non-empty, and end at the area's height.
  explicit Column(std::vector<Run> Runs) : Runs(std::move(Runs)) {}

  /// `Offset` counts up from the area's `MinY` and is below its height.
  Cell at(int Offset) const;

  /// A copy with the cell at `Offset` replaced and equal neighbours merged.
  std::shared_ptr<const Column> with(int Offset, Cell Value) const;

  /// A copy with every cell in `Edits` replaced and equal neighbours merged.
  /// `Edits` must be in strictly increasing `Offset` order. If no cell
  /// changes, this column itself, which must then be owned by a `shared_ptr`.
  std::shared_ptr<const Column> with(std::span<const ColumnEdit> Edits) const;

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
/// A new version shares every chunk and column it did not change. A chunk with
/// no state reads as air, as does anything outside the area. `run`, `regionId`
/// and `sequence` of one version describe one stream position.
class Area {
public:
  /// An all-air area at position `Sequence` of the stream named by
  /// `ControllerRun` and `RegionId`, before any frame after it.
  Area(Geometry Geo, std::uint64_t ControllerRun, std::uint32_t RegionId,
       std::uint64_t Sequence = 0);

  const Geometry &geometry() const { return Geo; }

  /// Controller run the area was assigned by.
  std::uint64_t run() const { return ControllerRun; }

  std::uint32_t regionId() const { return RegionId; }

  /// Sequence of the last world frame applied, or the assignment's before
  /// the first.
  std::uint64_t sequence() const { return Sequence; }

  Cell at(int X, int Y, int Z) const;

  /// The column at `X`, `Z`, or null where it reads as air.
  const Column *column(int X, int Z) const;

  /// A version with one chunk replaced. A null `Content` clears the chunk to
  /// air.
  std::shared_ptr<const Area> withChunk(int ChunkX, int ChunkZ,
                                        ChunkPtr Content,
                                        std::uint64_t Sequence) const;

  /// A version with `Edits` applied, a later edit to a cell winning. Every
  /// edit must be inside the area.
  std::shared_ptr<const Area> withCells(std::span<const CellEdit> Edits,
                                        std::uint64_t Sequence) const;

  /// This version cut down to `To`, which `geometry()` must hold, as region
  /// `RegionId` of the same run and sequence. Chunks inside are shared.
  std::shared_ptr<const Area> cropped(Geometry To,
                                      std::uint32_t RegionId) const;

private:
  /// The chunks at one `ChunkX`, indexed by `ChunkZ`. A null chunk is air.
  using ChunkRow = std::vector<ChunkPtr>;
  using RowPtr = std::shared_ptr<const ChunkRow>;

  /// A writable copy of the row at `ChunkX`, or an all-air row if it has none.
  std::shared_ptr<ChunkRow> copyRow(int ChunkX) const;

  const ChunkPtr *chunkAt(int X, int Z) const;

  Geometry Geo;
  std::uint64_t ControllerRun;
  std::uint32_t RegionId;
  std::uint64_t Sequence = 0;
  /// Indexed by `ChunkX`. A null row is air.
  std::vector<RowPtr> Rows;
  /// Shared by every chunk created from air.
  ColumnPtr AirColumn;
};

} // namespace maestro::world
