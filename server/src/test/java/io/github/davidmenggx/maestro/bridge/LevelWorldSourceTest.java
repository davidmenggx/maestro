package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LevelWorldSourceTest {

  private static final int SIZE = 16;
  private static final int MIN_X = 32;
  private static final int MIN_Y = -48;
  private static final int MIN_Z = -16;

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
          int expected =
              (BlockCells.floor(world, pos) << 8) | (BlockCells.pathType(world, pos) & 0xFF);
          assertEquals(expected, cells[index(dx, dy, dz)], world.getBlockState(pos) + " at " + pos);
        }
      }
    }
    // So a cell is not always the state's.
    assertEquals(16, cells[index(3, 4, 5)] >> 8);
    assertEquals(8, cells[index(9, 4, 5)] >> 8);
  }

  @Test
  void aSectionIsUniformOnlyIfItsPaletteHoldsOneStateWhoseCellIsTheState() {
    assertEquals(PathType.OPEN.ordinal(), uniformCell(new OneSection()));

    OneSection stone = new OneSection(Blocks.STONE.defaultBlockState());
    assertEquals((16 << 8) | PathType.BLOCKED.ordinal(), uniformCell(stone));
    // The palette keeps a state after its last block is gone.
    stone.set(7, 7, 7, Blocks.DIRT.defaultBlockState());
    assertEquals(WorldSource.NOT_UNIFORM, uniformCell(stone));
    stone.set(7, 7, 7, Blocks.STONE.defaultBlockState());
    assertEquals(WorldSource.NOT_UNIFORM, uniformCell(stone));

    assertEquals(
        WorldSource.NOT_UNIFORM,
        uniformCell(new OneSection(Blocks.MOVING_PISTON.defaultBlockState())));
  }

  private static int[] read(OneSection world) {
    int[] cells = new int[SIZE * SIZE * WorldSource.SECTION_HEIGHT];
    new LevelWorldSource.Sections(world).read(world.section, MIN_X, MIN_Y, MIN_Z, cells);
    return cells;
  }

  private static int uniformCell(OneSection world) {
    return new LevelWorldSource.Sections(world).uniformCell(world.section, MIN_X, MIN_Y, MIN_Z);
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
