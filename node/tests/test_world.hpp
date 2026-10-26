#pragma once

#include <cstdint>
#include <memory>
#include <span>
#include <vector>

#include "maestro/world/area.hpp"
#include "maestro/world/world_store.hpp"

namespace maestro::test {

using Frame = std::vector<std::uint8_t>;

inline constexpr world::Cell Stone{world::PathType::BLOCKED, world::FullFloor};
inline constexpr world::Cell Water{world::PathType::WATER, 0};
inline constexpr world::Cell Lava{world::PathType::LAVA, 0};
inline constexpr world::Cell Fire{world::PathType::FIRE, 0};
inline constexpr world::Cell Cactus{world::PathType::DAMAGING, 15};
inline constexpr world::Cell Fence{world::PathType::FENCE, 24};
inline constexpr world::Cell Rail{world::PathType::RAIL, 0};
inline constexpr world::Cell WoodDoor{world::PathType::DOOR_WOOD_CLOSED,
                                      world::FullFloor};
inline constexpr world::Cell IronDoor{world::PathType::DOOR_IRON_CLOSED,
                                      world::FullFloor};
inline constexpr world::Cell OpenDoor{world::PathType::DOOR_OPEN,
                                      world::FullFloor};

/// A dense, writable test world that converts to controller frames. Positions
/// are in wire coordinates.
class DenseWorld {
public:
  /// An all-air square world.
  DenseWorld(int Size, int MinY, int Height)
      : DenseWorld(Size, Size, MinY, Height) {}

  /// An all-air world of `SizeX` by `SizeZ` columns.
  DenseWorld(int SizeX, int SizeZ, int MinY, int Height);

  const world::Geometry &geometry() const { return Geo; }

  world::Cell at(int X, int Y, int Z) const;
  void set(int X, int Y, int Z, world::Cell Value);

  /// Sets every cell in the box between the two corners, inclusive.
  void fill(int X0, int Y0, int Z0, int X1, int Y1, int Z1, world::Cell Value);

  /// An assignment standing at `Sequence` of the stream.
  Frame assignment(bool Resume = false, std::uint64_t Run = 1,
                   std::uint32_t RegionId = 1,
                   std::uint64_t Sequence = 0) const;

  /// One chunk's state, run-length encoded as the controller encodes it.
  Frame chunk(std::uint64_t Sequence, int ChunkX, int ChunkZ) const;

  /// A new store given the assignment and then every chunk, pinned.
  std::shared_ptr<const world::Area> load() const;

private:
  std::size_t index(int X, int Y, int Z) const;

  world::Geometry Geo;
  std::vector<world::Cell> Cells;
};

/// A chunk the server no longer holds.
/// An assignment of a `Geo` area; with `FromRegionId`, a kept one.
Frame assignmentFrame(const world::Geometry &Geo, std::uint64_t Run,
                      std::uint32_t RegionId, bool Resume,
                      std::uint64_t Sequence, std::uint32_t FromRegionId = 0);

Frame clearedChunk(std::uint64_t Sequence, int ChunkX, int ChunkZ);

Frame delta(std::uint64_t Sequence, std::span<const world::CellEdit> Edits);

/// Verifies `Bytes` as a `WorldFrame` and applies it to `Store`. Bytes that
/// fail verification are `Malformed` and drop what `Store` holds, as the node
/// treats them.
world::ApplyStatus applyBytes(world::WorldStore &Store,
                              std::span<const std::uint8_t> Bytes);

} // namespace maestro::test
