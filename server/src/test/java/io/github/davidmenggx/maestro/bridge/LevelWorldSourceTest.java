package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LevelWorldSourceTest {

  private static final int SIZE = 16;
  private static final int MIN_X = 32;
  private static final int MIN_Y = -48;
  private static final int MIN_Z = -16;
  private static final int CHUNK_X = Chunks.of(MIN_X);
  private static final int CHUNK_Z = Chunks.of(MIN_Z);

  /** A world one section high, the section {@link OneSection} holds. */
  private static final LevelHeightAccessor HEIGHTS = LevelHeightAccessor.create(MIN_Y, SIZE);

  private static final byte OPEN = (byte) PathType.OPEN.ordinal();
  private static final byte BLOCKED = (byte) PathType.BLOCKED.ordinal();

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @Test
  void aSectionReadWholeGivesTheCellsOfItsBlocksReadOneAtATime() {
    OneSection world = new OneSection();
    BlockState[] states = {
      Blocks.STONE.defaultBlockState(),
      Blocks.AIR.defaultBlockState(),
      Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true),
      Blocks.IRON_DOOR.defaultBlockState(),
      Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
      Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
      Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3),
      Blocks.WHITE_CARPET.defaultBlockState(),
      Blocks.CACTUS.defaultBlockState(),
      Blocks.HONEY_BLOCK.defaultBlockState(),
      Blocks.RAIL.defaultBlockState(),
      Blocks.OAK_LEAVES.defaultBlockState(),
      Blocks.SOUL_SAND.defaultBlockState(),
      Blocks.LILY_PAD.defaultBlockState(),
      Blocks.WATER.defaultBlockState(),
      Blocks.OAK_FENCE.defaultBlockState(),
      // Blocks with a dynamic shape.
      Blocks.BAMBOO.defaultBlockState(),
      Blocks.POINTED_DRIPSTONE.defaultBlockState(),
      Blocks.POWDER_SNOW.defaultBlockState(),
      Blocks.SCAFFOLDING.defaultBlockState()
    };
    SplittableRandom rng = new SplittableRandom(7);
    for (int dx = 0; dx < SIZE; dx++) {
      for (int dz = 0; dz < SIZE; dz++) {
        // Runs of one state up the column, as terrain has.
        BlockState state = states[rng.nextInt(states.length)];
        for (int dy = 0; dy < SIZE; dy++) {
          if (rng.nextInt(4) == 0) {
            state = states[rng.nextInt(states.length)];
          }
          world.set(dx, dy, dz, state);
        }
      }
    }
    // The same state twice, whose block entities move blocks of different heights.
    world.moving(3, 4, 5, Blocks.STONE.defaultBlockState());
    world.moving(
        9, 4, 5, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));

    int[] cells = read(world);
    for (int dx = 0; dx < SIZE; dx++) {
      for (int dz = 0; dz < SIZE; dz++) {
        for (int dy = 0; dy < SIZE; dy++) {
          BlockPos pos = new BlockPos(MIN_X + dx, MIN_Y + dy, MIN_Z + dz);
          int expected = Cells.pack(BlockCells.floor(world, pos), BlockCells.pathType(world, pos));
          assertEquals(expected, cells[index(dx, dy, dz)], world.getBlockState(pos) + " at " + pos);
        }
      }
    }
    // So a cell is not always the state's.
    assertEquals(16, Cells.floorOf(cells[index(3, 4, 5)]));
    assertEquals(8, Cells.floorOf(cells[index(9, 4, 5)]));
  }

  @Test
  void aSectionIsUniformOnlyIfItsPaletteHoldsOneStateWhoseCellIsTheState() {
    assertEquals(Cells.pack(0, OPEN), uniformCell(new OneSection()));

    OneSection stone = new OneSection(Blocks.STONE.defaultBlockState());
    assertEquals(Cells.pack(16, BLOCKED), uniformCell(stone));
    // The palette keeps a state after its last block is gone.
    stone.set(7, 7, 7, Blocks.DIRT.defaultBlockState());
    assertEquals(WorldSource.NOT_UNIFORM, uniformCell(stone));
    stone.set(7, 7, 7, Blocks.STONE.defaultBlockState());
    assertEquals(WorldSource.NOT_UNIFORM, uniformCell(stone));

    assertEquals(
        WorldSource.NOT_UNIFORM,
        uniformCell(new OneSection(Blocks.MOVING_PISTON.defaultBlockState())));
  }

  @Test
  void aChunkThatIsNotLoadedIsNotReadableAndReadsAsAir() {
    LevelWorldSource source =
        new LevelWorldSource(HEIGHTS, (chunkX, chunkZ) -> null, new ProfileTable());

    assertFalse(source.readable(CHUNK_X, CHUNK_Z));
    assertNull(Frames.readChunk(source, CHUNK_X, CHUNK_Z));
    assertEquals(Cells.pack(0, OPEN), source.uniformCell(CHUNK_X, CHUNK_Z, 0));
    assertFalse(source.readSection(CHUNK_X, CHUNK_Z, 0, new int[SIZE * SIZE * SIZE]));
    assertEquals(OPEN, source.pathTypeAt(MIN_X, MIN_Y, MIN_Z));
    assertEquals(0, source.floorAt(MIN_X, MIN_Y, MIN_Z));
  }

  @Test
  void aLoadedChunkIsReadThroughTheChunk() {
    OneSection world = new OneSection(Blocks.STONE.defaultBlockState());
    world.set(1, 2, 3, Blocks.WHITE_CARPET.defaultBlockState());
    world.moving(
        9, 4, 5, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
    ChunkAccess chunk = world.chunk();
    LevelWorldSource source =
        new LevelWorldSource(
            HEIGHTS,
            (chunkX, chunkZ) -> chunkX == CHUNK_X && chunkZ == CHUNK_Z ? chunk : null,
            new ProfileTable());

    assertTrue(source.readable(CHUNK_X, CHUNK_Z));
    assertFalse(source.readable(CHUNK_X + 1, CHUNK_Z));
    assertEquals(BLOCKED, source.pathTypeAt(MIN_X, MIN_Y, MIN_Z));
    assertEquals(16, source.floorAt(MIN_X, MIN_Y, MIN_Z));
    assertEquals(OPEN, source.pathTypeAt(MIN_X + 1, MIN_Y + 2, MIN_Z + 3));
    assertEquals(1, source.floorAt(MIN_X + 1, MIN_Y + 2, MIN_Z + 3));
    // The moving piston's block entity is read from the chunk too.
    assertEquals(8, source.floorAt(MIN_X + 9, MIN_Y + 4, MIN_Z + 5));
    // The next chunk is not loaded.
    assertEquals(OPEN, source.pathTypeAt(MIN_X + SIZE, MIN_Y, MIN_Z));
    assertEquals(0, source.floorAt(MIN_X + SIZE, MIN_Y, MIN_Z));

    int[] cells = new int[SIZE * SIZE * WorldSource.SECTION_HEIGHT];
    assertTrue(source.readSection(CHUNK_X, CHUNK_Z, 0, cells));
    for (int dx = 0; dx < SIZE; dx++) {
      for (int dz = 0; dz < SIZE; dz++) {
        for (int dy = 0; dy < SIZE; dy++) {
          int x = MIN_X + dx;
          int y = MIN_Y + dy;
          int z = MIN_Z + dz;
          assertEquals(
              Cells.pack(source.floorAt(x, y, z), source.pathTypeAt(x, y, z)),
              cells[index(dx, dy, dz)]);
        }
      }
    }
    assertNotNull(Frames.readChunk(source, CHUNK_X, CHUNK_Z));
  }

  private static int[] read(OneSection world) {
    int[] cells = new int[SIZE * SIZE * WorldSource.SECTION_HEIGHT];
    new LevelWorldSource.Sections().read(world, world.section, MIN_X, MIN_Y, MIN_Z, cells);
    return cells;
  }

  private static int uniformCell(OneSection world) {
    return new LevelWorldSource.Sections().uniformCell(world, world.section, MIN_X, MIN_Y, MIN_Z);
  }

  private static int index(int dx, int dy, int dz) {
    return (dx * SIZE + dz) * WorldSource.SECTION_HEIGHT + dy;
  }

  /** One section with its lowest corner at the minimum coordinates, and air everywhere else. */
  private static final class OneSection implements BlockGetter {

    final LevelChunkSection section;
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();

    OneSection() {
      this(Blocks.AIR.defaultBlockState());
    }

    /** Every block {@code fill}, with a palette of that state alone. */
    OneSection(BlockState fill) {
      section =
          new LevelChunkSection(
              new PalettedContainer<>(
                  fill, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)),
              null);
    }

    void set(int dx, int dy, int dz, BlockState state) {
      section.setBlockState(dx, dy, dz, state);
    }

    /** A moving piston pushing {@code moved} sideways, before it has moved. */
    void moving(int dx, int dy, int dz, BlockState moved) {
      BlockState piston = Blocks.MOVING_PISTON.defaultBlockState();
      set(dx, dy, dz, piston);
      BlockPos pos = new BlockPos(MIN_X + dx, MIN_Y + dy, MIN_Z + dz);
      blockEntities.put(
          pos, new PistonMovingBlockEntity(pos, piston, moved, Direction.NORTH, true, false));
    }

    /** A chunk holding this section and its block entities. */
    ChunkAccess chunk() {
      ProtoChunk chunk =
          new ProtoChunk(
              new ChunkPos(CHUNK_X, CHUNK_Z),
              UpgradeData.EMPTY,
              new LevelChunkSection[] {section},
              new ProtoChunkTicks<>(),
              new ProtoChunkTicks<>(),
              HEIGHTS,
              null,
              null);
      blockEntities.values().forEach(chunk::setBlockEntity);
      return chunk;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
      return blockEntities.get(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
      int dx = pos.getX() - MIN_X;
      int dy = pos.getY() - MIN_Y;
      int dz = pos.getZ() - MIN_Z;
      if (dx < 0 || dx >= SIZE || dy < 0 || dy >= SIZE || dz < 0 || dz >= SIZE) {
        return Blocks.AIR.defaultBlockState();
      }
      return section.getBlockState(dx, dy, dz);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
      return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
      return 384;
    }

    @Override
    public int getMinY() {
      return -64;
    }
  }
}
