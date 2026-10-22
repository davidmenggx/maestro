package io.github.davidmenggx.maestro.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Seeded test terrain on a square of whole chunks: mild hills, terraces and pits, obstacles giving
 * every per-block path type, and rooms and pens with a door or gate. Each column is one vertical
 * band. Obstacle counts are drawn per seed, from bare to crowded.
 */
final class Terrain {

  /**
   * A walled rectangle, in coordinates relative to the corner. Its interior is inside the walls.
   */
  record Enclosure(int minX, int minZ, int width, int depth) {}

  /** Blocks rewritten in every column, counted up from the bottom of the world. */
  static final int BAND = 48;

  /** Room left above the highest ground for obstacles. */
  private static final int HEADROOM = 8;

  /** Height of the lowest ground above the bottom of the world. */
  private static final int BASE = 10;

  /** Writes blocks as they are, without updates that would change or break them. */
  static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

  private static final Direction[] HORIZONTAL = {
    Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
  };

  private final ServerLevel level;
  private final int originX;
  private final int originZ;
  private final int size;
  private final int minY;
  private final SplittableRandom rng;
  private final int[] ground;
  private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
  private final List<BlockPos> connecting = new ArrayList<>();
  private final List<Enclosure> enclosures = new ArrayList<>();

  private Terrain(ServerLevel level, int originX, int originZ, int size, long seed) {
    this.level = level;
    this.originX = originX;
    this.originZ = originZ;
    this.size = size;
    this.minY = level.getMinY();
    this.rng = new SplittableRandom(seed);
    this.ground = new int[size * size];
  }

  /**
   * Builds the terrain for {@code seed} over the square with corner {@code (originX, originZ)}.
   * Returns its rooms and pens.
   */
  static List<Enclosure> build(ServerLevel level, int originX, int originZ, int size, long seed) {
    Terrain terrain = new Terrain(level, originX, originZ, size, seed);
    terrain.shape();
    terrain.fill();
    terrain.decorate();
    terrain.connect();
    return List.copyOf(terrain.enclosures);
  }

  /** Hills from coarse noise, then raised terraces and dug pits. */
  private void shape() {
    int relief = rng.nextInt(7);
    int step = 8;
    int cells = size / step + 2;
    int[] corners = new int[cells * cells];
    for (int i = 0; i < corners.length; i++) {
      corners[i] = rng.nextInt(relief + 1);
    }
    for (int x = 0; x < size; x++) {
      for (int z = 0; z < size; z++) {
        int cx = x / step;
        int cz = z / step;
        double fx = (x % step) / (double) step;
        double fz = (z % step) / (double) step;
        double top =
            lerp(
                lerp(corners[cx * cells + cz], corners[(cx + 1) * cells + cz], fx),
                lerp(corners[cx * cells + cz + 1], corners[(cx + 1) * cells + cz + 1], fx),
                fz);
        ground[x * size + z] = minY + BASE + (int) Math.round(top);
      }
    }

    // Terraces and pits give falls of every length a mob may or may not accept.
    int terraces = rng.nextInt(7);
    for (int i = 0; i < terraces; i++) {
      int rise = 1 + rng.nextInt(5);
      forRectangle(4, 18, (x, z) -> ground[x * size + z] += rise);
    }
    int pits = rng.nextInt(9);
    for (int i = 0; i < pits; i++) {
      int depth = 2 + rng.nextInt(9);
      forRectangle(1, 4, (x, z) -> ground[x * size + z] -= depth);
    }
    for (int i = 0; i < ground.length; i++) {
      ground[i] = Math.clamp(ground[i], minY + 1, minY + BAND - HEADROOM);
    }
  }

  /** Solid ground up to the surface, grass on top, air above it to the top of the band. */
  private void fill() {
    BlockState stone = Blocks.STONE.defaultBlockState();
    BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
    BlockState air = Blocks.AIR.defaultBlockState();
    for (int x = 0; x < size; x++) {
      for (int z = 0; z < size; z++) {
        int top = ground[x * size + z];
        for (int y = minY; y < minY + BAND; y++) {
          set(x, y, z, y < top ? stone : y == top ? grass : air);
        }
      }
    }
  }

  private void decorate() {
    BlockState stone = Blocks.STONE.defaultBlockState();
    scatter(40, 1, (x, z, top) -> stack(x, top + 1, z, 1 + rng.nextInt(3), stone));
    for (int i = rng.nextInt(13); i > 0; i--) {
      line(
          3,
          14,
          (x, z, top, along) -> {
            if (rng.nextInt(7) != 0) {
              stack(x, top + 1, z, 1 + rng.nextInt(3), Blocks.COBBLESTONE.defaultBlockState());
            }
          });
    }
    scatter(
        40,
        1,
        (x, z, top) ->
            set(
                x,
                top + 1,
                z,
                Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)));
    scatter(
        30,
        1,
        (x, z, top) ->
            set(
                x,
                top + 1,
                z,
                Blocks.SNOW
                    .defaultBlockState()
                    .setValue(SnowLayerBlock.LAYERS, 1 + rng.nextInt(8))));
    scatter(20, 1, (x, z, top) -> set(x, top + 1, z, Blocks.WHITE_CARPET.defaultBlockState()));
    pools(6, 7, 2, Blocks.WATER.defaultBlockState());
    pools(3, 3, 1, Blocks.LAVA.defaultBlockState());
    scatter(20, 1, (x, z, top) -> set(x, top, z, Blocks.MAGMA_BLOCK.defaultBlockState()));
    scatter(8, 1, (x, z, top) -> set(x, top + 1, z, Blocks.CAMPFIRE.defaultBlockState()));
    scatter(10, 1, (x, z, top) -> set(x, top + 1, z, Blocks.FIRE.defaultBlockState()));
    scatter(15, 1, (x, z, top) -> set(x, top + 1, z, Blocks.SWEET_BERRY_BUSH.defaultBlockState()));
    scatter(10, 1, (x, z, top) -> set(x, top + 1, z, Blocks.WITHER_ROSE.defaultBlockState()));
    for (int i = rng.nextInt(7); i > 0; i--) {
      line(
          3,
          10,
          (x, z, top, along) -> {
            RailShape shape =
                along.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
            set(x, top + 1, z, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape));
          });
    }
    BlockState leaves =
        Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    scatter(20, 1, (x, z, top) -> stack(x, top + 1, z, 1 + rng.nextInt(2), leaves));
    scatter(15, 1, (x, z, top) -> set(x, top + 1, z, Blocks.OAK_TRAPDOOR.defaultBlockState()));
    BlockState[] softFloors = {
      Blocks.FARMLAND.defaultBlockState(),
      Blocks.DIRT_PATH.defaultBlockState(),
      Blocks.SOUL_SAND.defaultBlockState()
    };
    scatter(20, 2, (x, z, top) -> set(x, top, z, softFloors[rng.nextInt(softFloors.length)]));
    hazards(16);
    decorateShapes();
  }

  /**
   * Patches of mixed hazards, so some cells border several kinds and vanilla's scan order decides
   * their type.
   */
  private void hazards(int maxCount) {
    BlockState[] onGround = {
      Blocks.FIRE.defaultBlockState(),
      Blocks.SWEET_BERRY_BUSH.defaultBlockState(),
      Blocks.WITHER_ROSE.defaultBlockState(),
      Blocks.CAMPFIRE.defaultBlockState()
    };
    BlockState[] inGround = {
      Blocks.WATER.defaultBlockState(),
      Blocks.LAVA.defaultBlockState(),
      Blocks.MAGMA_BLOCK.defaultBlockState()
    };
    for (int i = rng.nextInt(maxCount + 1); i > 0; i--) {
      forRectangle(
          2,
          6,
          (x, z) -> {
            int top = ground[x * size + z];
            switch (rng.nextInt(3)) {
              case 0 -> set(x, top + 1, z, onGround[rng.nextInt(onGround.length)]);
              case 1 -> set(x, top, z, inGround[rng.nextInt(inGround.length)]);
              default -> {}
            }
          });
    }
  }

  /** Rooms, pens, fences, doors and blocks whose collision shape is not a full-width box. */
  private void decorateShapes() {
    for (int i = rng.nextInt(9); i > 0; i--) {
      room();
    }
    for (int i = rng.nextInt(7); i > 0; i--) {
      pen();
    }
    BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
    BlockState wall = Blocks.COBBLESTONE_WALL.defaultBlockState();
    for (int i = rng.nextInt(9); i > 0; i--) {
      BlockState post = rng.nextBoolean() ? fence : wall;
      line(
          3,
          10,
          (x, z, top, along) -> {
            if (post == fence && rng.nextInt(6) == 0) {
              set(
                  x,
                  top + 1,
                  z,
                  Blocks.OAK_FENCE_GATE
                      .defaultBlockState()
                      .setValue(FenceGateBlock.FACING, along.getClockWise())
                      .setValue(FenceGateBlock.OPEN, rng.nextBoolean()));
            } else {
              set(x, top + 1, z, post);
              connecting.add(new BlockPos(x, top + 1, z));
            }
          });
    }
    for (int i = rng.nextInt(7); i > 0; i--) {
      int doorAt = 1 + rng.nextInt(3);
      int[] index = {0};
      line(
          4,
          9,
          (x, z, top, along) -> {
            if (index[0]++ == doorAt) {
              door(x, top + 1, z, along.getClockWise());
            } else {
              stack(x, top + 1, z, 2, Blocks.STONE_BRICKS.defaultBlockState());
            }
          });
    }
    scatter(
        10,
        1,
        (x, z, top) -> stack(x, top + 1, z, 1 + rng.nextInt(3), Blocks.CACTUS.defaultBlockState()));
    scatter(12, 2, (x, z, top) -> set(x, top, z, Blocks.HONEY_BLOCK.defaultBlockState()));
    scatter(
        15,
        1,
        (x, z, top) ->
            set(
                x,
                top + 1,
                z,
                Blocks.COBBLESTONE_STAIRS
                    .defaultBlockState()
                    .setValue(StairBlock.FACING, HORIZONTAL[rng.nextInt(4)])
                    .setValue(StairBlock.HALF, rng.nextInt(4) == 0 ? Half.TOP : Half.BOTTOM)));
    scatter(
        10,
        1,
        (x, z, top) ->
            set(
                x,
                top + 1,
                z,
                Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)));
    for (int i = rng.nextInt(6); i > 0; i--) {
      line(
          2,
          6,
          (x, z, top, along) -> {
            set(x, top + 1, z, Blocks.GLASS_PANE.defaultBlockState());
            connecting.add(new BlockPos(x, top + 1, z));
          });
    }
    scatter(5, 1, (x, z, top) -> set(x, top + 1, z, Blocks.CHEST.defaultBlockState()));
    scatter(
        5,
        1,
        (x, z, top) ->
            set(
                x,
                top + 1,
                z,
                Blocks.POINTED_DRIPSTONE
                    .defaultBlockState()
                    .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.UP)));
    scatter(6, 1, (x, z, top) -> set(x, top, z, Blocks.POWDER_SNOW.defaultBlockState()));
    scatter(
        4,
        1,
        (x, z, top) -> {
          Direction toLog = HORIZONTAL[rng.nextInt(4)];
          int logX = x + toLog.getStepX();
          int logZ = z + toLog.getStepZ();
          if (inside(logX, logZ) && ground[logX * size + logZ] == top) {
            set(logX, top + 1, logZ, Blocks.JUNGLE_LOG.defaultBlockState());
            set(x, top + 1, z, Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, toLog));
          }
        });
    // Lily pads float on the water already placed.
    scatter(
        12,
        1,
        (x, z, top) -> {
          if (level.getBlockState(cursor.set(originX + x, top, originZ + z)).is(Blocks.WATER)) {
            set(x, top + 1, z, Blocks.LILY_PAD.defaultBlockState());
          }
        });
  }

  /** Fences, walls and panes take their connections from their neighbours. */
  private void connect() {
    for (BlockPos local : connecting) {
      BlockPos pos = new BlockPos(originX + local.getX(), local.getY(), originZ + local.getZ());
      BlockState state = level.getBlockState(pos);
      level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), FLAGS);
    }
  }

  /** Walls two blocks high around a rectangle, with one or two doors in them. */
  private void room() {
    int width = 4 + rng.nextInt(6);
    int depth = 4 + rng.nextInt(6);
    int x0 = rng.nextInt(size - width);
    int z0 = rng.nextInt(size - depth);
    BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
    enclosures.add(new Enclosure(x0, z0, width, depth));
    perimeter(x0, z0, width, depth, (x, z, top, along) -> stack(x, top + 1, z, 2, brick));
    for (int doors = 1 + rng.nextInt(2); doors > 0; doors--) {
      boolean alongX = rng.nextBoolean();
      int x = alongX ? x0 + 1 + rng.nextInt(width - 2) : x0 + (rng.nextBoolean() ? 0 : width - 1);
      int z = alongX ? z0 + (rng.nextBoolean() ? 0 : depth - 1) : z0 + 1 + rng.nextInt(depth - 2);
      door(x, ground[x * size + z] + 1, z, alongX ? Direction.NORTH : Direction.EAST);
    }
  }

  /** A fence ring with a gate in it. */
  private void pen() {
    int width = 3 + rng.nextInt(6);
    int depth = 3 + rng.nextInt(6);
    int x0 = rng.nextInt(size - width);
    int z0 = rng.nextInt(size - depth);
    BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
    enclosures.add(new Enclosure(x0, z0, width, depth));
    perimeter(
        x0,
        z0,
        width,
        depth,
        (x, z, top, along) -> {
          set(x, top + 1, z, fence);
          connecting.add(new BlockPos(x, top + 1, z));
        });
    int x = x0 + 1 + rng.nextInt(width - 2);
    connecting.remove(new BlockPos(x, ground[x * size + z0] + 1, z0));
    set(
        x,
        ground[x * size + z0] + 1,
        z0,
        Blocks.OAK_FENCE_GATE
            .defaultBlockState()
            .setValue(FenceGateBlock.FACING, Direction.NORTH)
            .setValue(FenceGateBlock.OPEN, rng.nextBoolean()));
  }

  private void perimeter(int x0, int z0, int width, int depth, LineAction action) {
    for (int x = x0; x < x0 + width; x++) {
      for (int z = z0; z < z0 + depth; z++) {
        if (x == x0 || x == x0 + width - 1 || z == z0 || z == z0 + depth - 1) {
          action.apply(x, z, ground[x * size + z], Direction.NORTH);
        }
      }
    }
  }

  private void door(int x, int y, int z, Direction facing) {
    BlockState lower =
        (rng.nextInt(3) == 0 ? Blocks.IRON_DOOR : Blocks.OAK_DOOR)
            .defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.OPEN, rng.nextInt(3) == 0)
            .setValue(
                DoorBlock.HINGE, rng.nextBoolean() ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT);
    set(x, y, z, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
    set(x, y + 1, z, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
  }

  /** Replaces the top {@code depth} blocks of rectangles with a fluid. */
  private void pools(int maxCount, int maxSide, int maxDepth, BlockState fluid) {
    for (int i = rng.nextInt(maxCount + 1); i > 0; i--) {
      int depth = 1 + rng.nextInt(maxDepth);
      forRectangle(
          2,
          maxSide,
          (x, z) -> {
            int top = ground[x * size + z];
            for (int d = 0; d < depth; d++) {
              set(x, top - d, z, fluid);
            }
          });
    }
  }

  @FunctionalInterface
  private interface ColumnAction {
    void apply(int x, int z, int top);
  }

  @FunctionalInterface
  private interface LineAction {
    void apply(int x, int z, int top, Direction along);
  }

  @FunctionalInterface
  private interface CellAction {
    void apply(int x, int z);
  }

  /** Up to {@code maxCount} random columns, each covering a square of side {@code spread}. */
  private void scatter(int maxCount, int spread, ColumnAction action) {
    for (int i = rng.nextInt(maxCount + 1); i > 0; i--) {
      int x0 = rng.nextInt(size - spread + 1);
      int z0 = rng.nextInt(size - spread + 1);
      for (int x = x0; x < x0 + spread; x++) {
        for (int z = z0; z < z0 + spread; z++) {
          action.apply(x, z, ground[x * size + z]);
        }
      }
    }
  }

  /** A straight run of columns in one horizontal direction, clipped to the square. */
  private void line(int minLength, int maxLength, LineAction action) {
    Direction along = HORIZONTAL[rng.nextInt(4)];
    int length = minLength + rng.nextInt(maxLength - minLength + 1);
    int x = rng.nextInt(size);
    int z = rng.nextInt(size);
    for (int i = 0; i < length && inside(x, z); i++) {
      action.apply(x, z, ground[x * size + z], along);
      x += along.getStepX();
      z += along.getStepZ();
    }
  }

  private void forRectangle(int minSide, int maxSide, CellAction action) {
    int width = minSide + rng.nextInt(maxSide - minSide + 1);
    int depth = minSide + rng.nextInt(maxSide - minSide + 1);
    int x0 = rng.nextInt(size);
    int z0 = rng.nextInt(size);
    for (int x = x0; x < Math.min(size, x0 + width); x++) {
      for (int z = z0; z < Math.min(size, z0 + depth); z++) {
        action.apply(x, z);
      }
    }
  }

  private void stack(int x, int y, int z, int height, BlockState state) {
    for (int dy = 0; dy < height; dy++) {
      set(x, y + dy, z, state);
    }
  }

  private boolean inside(int x, int z) {
    return x >= 0 && x < size && z >= 0 && z < size;
  }

  /** {@code x} and {@code z} are relative to the corner, {@code y} absolute. */
  private void set(int x, int y, int z, BlockState state) {
    if (y < minY || y >= minY + BAND) {
      return;
    }
    level.setBlock(cursor.set(originX + x, y, originZ + z), state, FLAGS);
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
