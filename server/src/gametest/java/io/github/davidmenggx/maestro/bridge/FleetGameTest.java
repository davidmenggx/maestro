package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.pathfinder.PathType;

/** What the fleet tells the controller about the overworld as the game changes it. */
public final class FleetGameTest {

  private static final byte OPEN = (byte) PathType.OPEN.ordinal();
  private static final byte BLOCKED = (byte) PathType.BLOCKED.ordinal();
  private static final byte WATER = (byte) PathType.WATER.ordinal();
  private static final byte DOOR_OPEN = (byte) PathType.DOOR_OPEN.ordinal();

  /** A chunk well outside the square of five cells the game test config covers. */
  private static final int FAR_CHUNK_X = 300;

  /** A chunk inside the covered square, far from the tests and the players near them. */
  private static final int QUIET_CHUNK = 30;

  @GameTest
  public void aBlockChangeIsReportedOnlyWhenItChangesTheCell(GameTestHelper helper) {
    List<WorldEvent> events = GameTests.events(helper);
    BlockPos at = new BlockPos(2, 1, 2);
    int from = events.size();

    helper.setBlock(at, Blocks.STONE);
    // Cobblestone is the same cell as stone.
    helper.setBlock(at, Blocks.COBBLESTONE);
    helper.setBlock(
        at, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
    helper.setBlock(at, Blocks.AIR);

    List<WorldEvent.BlockChanged> changes =
        GameTests.changesAt(events, from, helper.absolutePos(at));
    helper.assertValueEqual(changes.size(), 3, "changes sent");
    assertCell(helper, changes.get(0), BLOCKED, 16);
    assertCell(helper, changes.get(1), BLOCKED, 8);
    assertCell(helper, changes.get(2), OPEN, 0);
    helper.succeed();
  }

  @GameTest
  public void aChunkIsWatchedOnlyOnTheServerThreadAndInsideTheSquare(GameTestHelper helper) {
    Fleet fleet = GameTests.fleet(helper);
    List<WorldEvent> events = GameTests.events(helper);
    ServerLevel level = helper.getLevel();
    LevelChunk here = level.getChunkAt(helper.absolutePos(BlockPos.ZERO));

    helper.assertTrue(fleet.watches(here), "the test's own chunk is not watched");
    helper.assertTrue(
        !CompletableFuture.supplyAsync(() -> fleet.watches(here)).join(),
        "a chunk is watched off the server thread");

    LevelChunk far = level.getChunk(FAR_CHUNK_X, 0);
    helper.assertTrue(!fleet.watches(far), "a chunk outside the square is watched");
    BlockPos outside =
        new BlockPos(FAR_CHUNK_X * 16 + 1, helper.absolutePos(BlockPos.ZERO).getY(), 1);
    int from = events.size();
    level.setBlockAndUpdate(outside, Blocks.STONE.defaultBlockState());
    level.setBlockAndUpdate(outside, Blocks.AIR.defaultBlockState());
    helper.assertValueEqual(
        GameTests.changesAt(events, from, outside).size(), 0, "changes sent from outside");
    helper.succeed();
  }

  @GameTest
  public void aDoorOpenedByHandIsReportedInBothHalves(GameTestHelper helper) {
    List<WorldEvent> events = GameTests.events(helper);
    GameTests.floor(helper);
    BlockPos lower = new BlockPos(3, 1, 3);
    helper.setBlock(
        lower, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
    helper.setBlock(
        lower.above(),
        Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    int from = events.size();

    helper.useBlock(lower);

    helper.assertBlockProperty(lower, DoorBlock.OPEN, true);
    assertCell(
        helper, GameTests.lastChangeAt(events, from, helper.absolutePos(lower)), DOOR_OPEN, 16);
    assertCell(
        helper,
        GameTests.lastChangeAt(events, from, helper.absolutePos(lower.above())),
        DOOR_OPEN,
        16);
    helper.succeed();
  }

  @GameTest(maxTicks = 100)
  public void waterIsReportedWhereItFlows(GameTestHelper helper) {
    List<WorldEvent> events = GameTests.events(helper);
    GameTests.floor(helper);
    BlockPos source = new BlockPos(3, 1, 3);
    int from = events.size();

    helper.setBlock(source, Blocks.WATER);

    helper.succeedWhen(
        () -> {
          for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos flowed = helper.absolutePos(source.relative(side, 2));
            WorldEvent.BlockChanged change = GameTests.lastChangeAt(events, from, flowed);
            helper.assertTrue(change != null, "no change sent where the water flowed");
            assertCell(helper, change, WATER, 0);
          }
        });
  }

  @GameTest(maxTicks = 100)
  public void aFallingBlockIsReportedWhereItLeftAndWhereItLanded(GameTestHelper helper) {
    List<WorldEvent> events = GameTests.events(helper);
    GameTests.floor(helper);
    BlockPos top = new BlockPos(5, 4, 5);
    BlockPos landed = new BlockPos(5, 1, 5);
    int from = events.size();

    helper.setBlock(top, Blocks.SAND);

    helper.succeedWhen(
        () -> {
          helper.assertBlockPresent(Blocks.SAND, landed);
          assertCell(
              helper, GameTests.lastChangeAt(events, from, helper.absolutePos(top)), OPEN, 0);
          assertCell(
              helper,
              GameTests.lastChangeAt(events, from, helper.absolutePos(landed)),
              BLOCKED,
              16);
        });
  }

  @GameTest(maxTicks = 100)
  public void aBlockAPistonPushesIsReportedWhereItStops(GameTestHelper helper) {
    List<WorldEvent> events = GameTests.events(helper);
    GameTests.floor(helper);
    BlockPos piston = new BlockPos(1, 1, 6);
    BlockPos pushed = piston.east();
    BlockPos moved = pushed.east();
    helper.setBlock(
        piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
    helper.setBlock(pushed, Blocks.STONE);
    int from = events.size();

    helper.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);

    helper.succeedWhen(
        () -> {
          helper.assertBlockPresent(Blocks.STONE, moved);
          helper.assertBlockPresent(Blocks.PISTON_HEAD, pushed);
          assertCell(
              helper, GameTests.lastChangeAt(events, from, helper.absolutePos(moved)), BLOCKED, 16);
          helper.assertTrue(
              GameTests.lastChangeAt(events, from, helper.absolutePos(pushed)) != null,
              "no change sent where the piston head went");
        });
  }

  @GameTest(maxTicks = 40)
  public void aMobIsReportedWhenItLoadsAndLeavesButOtherEntitiesAreNot(GameTestHelper helper) {
    List<WorldEvent> events = GameTests.events(helper);
    GameTests.floor(helper);
    int from = events.size();
    Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(2, 1, 2));
    ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(5, 1, 5));

    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    GameTests.since(events, from, WorldEvent.MobSpawned.class).stream()
                        .anyMatch(spawned -> spawned.mobId() == husk.getId()),
                    "the husk was not reported"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  GameTests.since(events, from, WorldEvent.MobSpawned.class).stream()
                      .noneMatch(spawned -> spawned.mobId() == stand.getId()),
                  "an armor stand was reported as a mob");
              husk.discard();
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    GameTests.since(events, from, WorldEvent.MobRemoved.class).stream()
                        .anyMatch(removed -> removed.mobId() == husk.getId()),
                    "the husk's removal was not reported"))
        .thenSucceed();
  }

  @GameTest(maxTicks = 100)
  public void aPlayerIsReportedEachSecondUntilItSpectates(GameTestHelper helper) {
    List<WorldEvent> events = GameTests.events(helper);
    GameType[] mode = {GameType.CREATIVE};
    int from = events.size();
    ServerPlayer player = GameTests.player(helper, () -> mode[0]);
    int[] spectating = new int[1];

    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    GameTests.since(events, from, WorldEvent.PlayerMoved.class).stream()
                        .anyMatch(moved -> moved.playerId() == player.getId()),
                    "the player was not reported"))
        .thenExecute(
            () -> {
              mode[0] = GameType.SPECTATOR;
              spectating[0] = events.size();
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    GameTests.since(events, spectating[0], WorldEvent.PlayerLeft.class).stream()
                        .anyMatch(left -> left.playerId() == player.getId()),
                    "the spectator was not reported gone"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  GameTests.since(events, spectating[0], WorldEvent.PlayerMoved.class).stream()
                      .noneMatch(moved -> moved.playerId() == player.getId()),
                  "a spectator was reported where it stands");
              helper.getLevel().getServer().getPlayerList().remove(player);
            })
        .thenSucceed();
  }

  @GameTest(maxTicks = 600)
  public void aChunkIsReportedWhenItLoadsAndWhenItUnloads(GameTestHelper helper) {
    List<WorldEvent> events = GameTests.events(helper);
    ServerLevel level = helper.getLevel();
    int from = events.size();

    level.setChunkForced(QUIET_CHUNK, QUIET_CHUNK, true);

    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    GameTests.since(events, from, WorldEvent.ChunkLoaded.class).stream()
                        .anyMatch(
                            loaded ->
                                loaded.chunkX() == QUIET_CHUNK && loaded.chunkZ() == QUIET_CHUNK),
                    "the forced chunk's load was not reported"))
        .thenExecute(() -> level.setChunkForced(QUIET_CHUNK, QUIET_CHUNK, false))
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    GameTests.since(events, from, WorldEvent.ChunkUnloaded.class).stream()
                        .anyMatch(
                            unloaded ->
                                unloaded.chunkX() == QUIET_CHUNK
                                    && unloaded.chunkZ() == QUIET_CHUNK),
                    "the released chunk's unload was not reported"))
        .thenSucceed();
  }

  /**
   * Events carry the tick open when they are sent, or the next one when sent between ticks, and
   * ticks open and close in order.
   */
  @GameTest(maxTicks = 40)
  public void everyEventFallsInTheTickItNames(GameTestHelper helper) {
    List<WorldEvent> events = GameTests.events(helper);
    GameTests.floor(helper);
    BlockPos at = new BlockPos(4, 1, 4);
    int from = events.size();
    boolean[] stone = {false};

    helper
        .startSequence()
        .thenExecuteFor(
            10,
            () -> {
              stone[0] = !stone[0];
              helper.setBlock(at, stone[0] ? Blocks.STONE : Blocks.AIR);
            })
        .thenExecute(() -> checkTicks(helper, events.subList(from, events.size())))
        .thenSucceed();
  }

  /** Checks {@code events} from the first tick that begins among them. */
  private static void checkTicks(GameTestHelper helper, List<WorldEvent> events) {
    long open = -1;
    long closed = -1;
    int ticks = 0;
    for (WorldEvent event : events) {
      switch (event) {
        case WorldEvent.TickBegan began -> {
          helper.assertTrue(open == -1, "tick " + began.tick() + " began inside tick " + open);
          helper.assertTrue(
              ticks == 0 || began.tick() == closed + 1,
              "tick " + began.tick() + " began after tick " + closed);
          open = began.tick();
          ticks++;
        }
        case WorldEvent.TickEnded ended -> {
          if (ticks > 0) {
            helper.assertTrue(
                ended.tick() == open, "tick " + ended.tick() + " ended inside tick " + open);
            closed = open;
            open = -1;
          }
        }
        default -> {
          long expected = open != -1 ? open : closed + 1;
          helper.assertTrue(
              ticks == 0 || event.tick() == expected, event + " was sent in tick " + expected);
        }
      }
    }
    helper.assertTrue(ticks >= 10, "only " + ticks + " ticks began");
  }

  private static void assertCell(
      GameTestHelper helper, WorldEvent.BlockChanged change, byte type, int floor) {
    helper.assertTrue(change != null, "no change sent");
    helper.assertValueEqual(PathType.values()[change.pathType()], PathType.values()[type], "type");
    helper.assertValueEqual(change.floor(), floor, "floor");
  }
}
