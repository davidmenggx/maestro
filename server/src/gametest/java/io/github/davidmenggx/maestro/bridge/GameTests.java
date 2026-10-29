package io.github.davidmenggx.maestro.bridge;

import com.mojang.authlib.GameProfile;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;

/**
 * What the game tests share: the fleet the server started, every event it sent the controller, and
 * the searches of the mobs a test watches. Game tests run on the server thread only.
 */
final class GameTests {

  /** Side of the empty structure each test runs in. */
  static final int SIZE = 8;

  /** Every event the game sent the controller since the first test asked, in order. */
  private static final List<WorldEvent> EVENTS = new ArrayList<>();

  /** What each watched mob's searches are told, by mob id. */
  private static final Map<Integer, Watch> WATCHED = new LinkedHashMap<>();

  /** The fleet's own interceptor, which answers every search no test watches. */
  private static PathOffload offload;

  /** How a test answers the searches of a mob it watches. */
  interface Watch {

    /** Whether to take the call; one not taken runs vanilla's search. */
    boolean wants(Mob mob);

    default Path search(Mob mob, SearchCall call, Searches.Vanilla vanilla) {
      return vanilla.search();
    }
  }

  private GameTests() {}

  /** The fleet the server started for the overworld. */
  static Fleet fleet(GameTestHelper helper) {
    Fleet fleet = Fleet.active();
    if (fleet == null) {
      helper.fail("no fleet is active; the game test config names a node");
    }
    install(fleet);
    return fleet;
  }

  /** Every event the game sent the controller so far. Grows as the server runs. */
  static List<WorldEvent> events(GameTestHelper helper) {
    fleet(helper);
    return EVENTS;
  }

  /** The fleet's own interceptor. */
  static PathOffload offload(GameTestHelper helper) {
    fleet(helper);
    return offload;
  }

  /** Answers the searches of {@code mob} by {@code watch} from now on. */
  static void watch(GameTestHelper helper, Mob mob, Watch watch) {
    fleet(helper);
    WATCHED.put(mob.getId(), watch);
  }

  /** Lays stone under the whole structure, so mobs stand at y = 1 whatever the terrain. */
  static void floor(GameTestHelper helper) {
    for (int x = 0; x < SIZE; x++) {
      for (int z = 0; z < SIZE; z++) {
        helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
      }
    }
  }

  /** Each block change at {@code pos} sent since event {@code from}, in order. */
  static List<WorldEvent.BlockChanged> changesAt(List<WorldEvent> events, int from, BlockPos pos) {
    Pos at = new Pos(pos.getX(), pos.getY(), pos.getZ());
    List<WorldEvent.BlockChanged> changes = new ArrayList<>();
    for (WorldEvent event : events.subList(from, events.size())) {
      if (event instanceof WorldEvent.BlockChanged change && change.pos().equals(at)) {
        changes.add(change);
      }
    }
    return changes;
  }

  /** The last block change at {@code pos} sent since event {@code from}, or null. */
  static WorldEvent.BlockChanged lastChangeAt(List<WorldEvent> events, int from, BlockPos pos) {
    List<WorldEvent.BlockChanged> changes = changesAt(events, from, pos);
    return changes.isEmpty() ? null : changes.get(changes.size() - 1);
  }

  /** The events of {@code type} sent since event {@code from}, in order. */
  static <T extends WorldEvent> List<T> since(List<WorldEvent> events, int from, Class<T> type) {
    List<T> found = new ArrayList<>();
    for (WorldEvent event : events.subList(from, events.size())) {
      if (type.isInstance(event)) {
        found.add(type.cast(event));
      }
    }
    return found;
  }

  /** A player joined to the server, in the game mode {@code mode} gives each time it is asked. */
  static ServerPlayer player(GameTestHelper helper, Supplier<GameType> mode) {
    CommonListenerCookie cookie =
        CommonListenerCookie.createInitial(
            new GameProfile(UUID.randomUUID(), "maestro-test-player"), false);
    ServerPlayer player =
        new ServerPlayer(
            helper.getLevel().getServer(),
            helper.getLevel(),
            cookie.gameProfile(),
            cookie.clientInformation()) {
          @Override
          public GameType gameMode() {
            return mode.get();
          }
        };
    Connection connection = new Connection(PacketFlow.SERVERBOUND);
    new EmbeddedChannel(connection);
    helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
    return player;
  }

  /**
   * Watches the fleet's events and puts the watched mobs' searches in front of its interceptor,
   * once. The interceptor is never removed, so tests running at once cannot undo each other's.
   */
  private static void install(Fleet fleet) {
    if (offload != null) {
      return;
    }
    fleet.watch(EVENTS::add);
    offload = (PathOffload) Searches.interceptor();
    Searches.intercept(
        new Searches.Interceptor() {
          @Override
          public ProfileTable profiles() {
            return offload.profiles();
          }

          @Override
          public boolean wants(Mob mob) {
            Watch watch = WATCHED.get(mob.getId());
            return watch != null ? watch.wants(mob) : offload.wants(mob);
          }

          @Override
          public Path search(Mob mob, SearchCall call, Searches.Vanilla vanilla) {
            Watch watch = WATCHED.get(mob.getId());
            return watch != null
                ? watch.search(mob, call, vanilla)
                : offload.search(mob, call, vanilla);
          }

          @Override
          public void handed(Mob mob, Path path) {
            offload.handed(mob, path);
          }
        });
  }
}
