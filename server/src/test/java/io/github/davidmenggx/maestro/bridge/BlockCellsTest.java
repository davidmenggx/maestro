package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.davidmenggx.maestro.controller.world.PathTypes;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockCellsTest {

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @Test
  void aCellCarriesTheTypeVanillaReadsFromTheState() {
    assertCell(Blocks.AIR.defaultBlockState(), PathType.OPEN, 0);
    assertCell(Blocks.STONE.defaultBlockState(), PathType.BLOCKED, 16);
    assertCell(Blocks.OAK_DOOR.defaultBlockState(), PathType.DOOR_WOOD_CLOSED, 16);
    assertCell(Blocks.IRON_DOOR.defaultBlockState(), PathType.DOOR_IRON_CLOSED, 16);
    assertCell(
        Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true), PathType.DOOR_OPEN, 16);
    assertCell(Blocks.RAIL.defaultBlockState(), PathType.RAIL, 0);
    assertCell(Blocks.OAK_LEAVES.defaultBlockState(), PathType.LEAVES, 16);
    assertCell(Blocks.MAGMA_BLOCK.defaultBlockState(), PathType.FIRE, 16);
    assertCell(Blocks.CACTUS.defaultBlockState(), PathType.DAMAGING, 15);
    assertCell(Blocks.SWEET_BERRY_BUSH.defaultBlockState(), PathType.DAMAGING, 0);
    assertCell(Blocks.WITHER_ROSE.defaultBlockState(), PathType.DAMAGE_CAUTIOUS, 0);
    assertCell(Blocks.HONEY_BLOCK.defaultBlockState(), PathType.STICKY_HONEY, 15);
    assertCell(Blocks.POWDER_SNOW.defaultBlockState(), PathType.POWDER_SNOW, 0);
    assertCell(Blocks.COCOA.defaultBlockState(), PathType.COCOA, 12);
  }

  @Test
  void aBlockTypedByATagStillCarriesItsFloor() {
    // Tags are bound only in a running server, so the types of these blocks come from the parity
    // fixtures instead.
    assertFloor(Blocks.WATER.defaultBlockState(), 0);
    assertFloor(Blocks.LAVA.defaultBlockState(), 0);
    assertFloor(Blocks.OAK_FENCE.defaultBlockState(), 24);
    assertFloor(Blocks.COBBLESTONE_WALL.defaultBlockState(), 24);
    assertFloor(Blocks.OAK_TRAPDOOR.defaultBlockState(), 3);
    assertFloor(Blocks.FIRE.defaultBlockState(), 0);
  }

  @Test
  void theFloorIsTheTopOfTheCollisionShapeRoundedUpToASixteenth() {
    assertCell(
        Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
        PathType.BLOCKED,
        8);
    assertCell(
        Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
        PathType.BLOCKED,
        16);
    // A snow layer collides one layer lower than it draws, so one layer has no collision.
    assertCell(
        Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1), PathType.OPEN, 0);
    assertCell(
        Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3), PathType.OPEN, 4);
    assertCell(Blocks.WHITE_CARPET.defaultBlockState(), PathType.OPEN, 1);
    assertCell(Blocks.SOUL_SAND.defaultBlockState(), PathType.BLOCKED, 14);
    // 1.5 sixteenths rounds up to 2.
    assertCell(Blocks.LILY_PAD.defaultBlockState(), PathType.TRAPDOOR, 2);
  }

  @Test
  void aCellOnlyEverCarriesAPerBlockType() {
    for (BlockState state : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY) {
      byte type = BlockCells.pathType(new OneBlock(state), BlockPos.ZERO);
      assertEquals(true, PathTypes.isPerBlock(type), state + " gave " + PathTypes.name(type));
    }
  }

  @Test
  void theCellIsReadAtThePositionAsked() {
    BlockGetter level = new OneBlock(Blocks.STONE.defaultBlockState());
    assertEquals(
        (byte) PathType.OPEN.ordinal(),
        BlockCells.pathType(level, BlockPos.ZERO.relative(Direction.UP)));
    assertEquals(0, BlockCells.floor(level, BlockPos.ZERO.relative(Direction.UP)));
  }

  private static void assertCell(BlockState state, PathType type, int floor) {
    BlockGetter level = new OneBlock(state);
    assertEquals(
        (byte) type.ordinal(), BlockCells.pathType(level, BlockPos.ZERO), state.toString());
    assertFloor(state, floor);
  }

  private static void assertFloor(BlockState state, int floor) {
    assertEquals(floor, BlockCells.floor(new OneBlock(state), BlockPos.ZERO), state.toString());
  }

  /** One block at the origin and air everywhere else. */
  private record OneBlock(BlockState state) implements BlockGetter {

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
      return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
      return pos.equals(BlockPos.ZERO) ? state : Blocks.AIR.defaultBlockState();
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
