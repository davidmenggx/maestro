package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Chance;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generated replacement for a recorded server trace, and the fuzzer of the suite. Every event the
 * boundary defines can be generated here. The kinds of activity in a tick run in an order drawn per
 * tick, so the sweep covers how they interleave, such as a block change followed by its chunk
 * leaving. {@code quietAfter} stops all world activity so the end of a run can drain. By then every
 * request has been answered or dropped, so the invariants can require it.
 */
public final class SyntheticWorkload implements Workload {

  /** One kind of activity in a tick. */
  @FunctionalInterface
  private interface Activity {
    void run(long tick, WorldInput input);
  }

  private static final int PLAYER_MOVE_INTERVAL_TICKS = 5;

  /** Vertical range of block changes around the surface. */
  private static final int BLOCK_CHANGE_SPREAD = 2;

  /** How far outside the region grid a stray request can start. */
  private static final int MAX_STRAY_DISTANCE = 8;

  /**
   * `reachRange` values vanilla passes: 0 and 1 at nearly every call site, and 6 for the one POI
   * type whose `validRange` is not 1.
   */
  private static final int[] REACH_RANGES = {0, 0, 1, 6};

  /**
   * `maxPathLength` is `max(FOLLOW_RANGE, requiredPathLength)`, where the attribute runs from 12 to
   * 64 over land mobs and `requiredPathLength` defaults to 16, so the bound lands between 16 and
   * 64. One caller passes an explicit 8 instead.
   */
  private static final int MIN_PATH_LENGTH = 16;

  private static final int MAX_EXTRA_PATH_LENGTH = 48;

  private static final int SHORT_PATH_LENGTH = 8;

  /** `maxVisitedNodes` is sixteen times the bound in force when the navigation last updated it. */
  private static final int VISITED_NODES_PER_BLOCK = 16;

  /** `getMaxFallDistance` for a mob with no target. */
  private static final int CALM_FALL_DISTANCE = 3;

  /**
   * Extra fall a mob with a target accepts: its health above a third of the maximum, minus a
   * difficulty allowance. A 100-health mob on hard reaches 67.
   */
  private static final int MAX_EXTRA_FALL_DISTANCE = 67;

  private final SimWorld world;
  private final Rng rng;
  private final Rng order;
  private final Activity[] activities = {
    this::churnChunks,
    this::changeBlocks,
    this::churnMobs,
    (tick, input) -> moveMobs(),
    this::requestPaths,
    this::cancelPath,
    this::movePlayers
  };

  /** Tries at finding a loaded chunk to spawn a mob in, before giving up for the tick. */
  private static final int SPAWN_TRIES = 8;

  /** Every type a cell may carry, in enum order, so a given index always picks the same type. */
  private static final byte[] PER_BLOCK_TYPES = perBlockTypes();

  private static byte[] perBlockTypes() {
    byte[] all = new byte[PathTypes.count()];
    int found = 0;
    for (int ordinal = 0; ordinal < PathTypes.count(); ordinal++) {
      if (PathTypes.isPerBlock((byte) ordinal)) {
        all[found++] = (byte) ordinal;
      }
    }
    return Arrays.copyOf(all, found);
  }

  private final long quietAfter;
  private final Scenario scenario;
  private final int classCount;
  private final Map<Integer, Pos> mobs = new LinkedHashMap<>();
  private final Map<Integer, Pos> players = new LinkedHashMap<>();

  private int nextMobId = 1;

  public SyntheticWorkload(SimWorld world, Rng rng, long quietAfter, Scenario scenario) {
    this.world = world;
    this.rng = rng;
    this.order = rng.derive("order");
    this.quietAfter = quietAfter;
    this.scenario = scenario;
    this.classCount = world.mobClassCount();
  }

  @Override
  public void tick(long tick, WorldInput input) {
    if (tick == 0) {
      start(input);
      return;
    }
    if (tick >= quietAfter) {
      // Chunks that left stay unloaded, and are air in the final comparison too.
      return;
    }
    for (int i = activities.length - 1; i > 0; i--) {
      int j = order.nextInt(i + 1);
      Activity swapped = activities[i];
      activities[i] = activities[j];
      activities[j] = swapped;
    }
    for (Activity activity : activities) {
      activity.run(tick, input);
    }
  }

  private void start(WorldInput input) {
    loadEverything(input);
    for (int i = 0; i < scenario.counts().get(Count.MOBS); i++) {
      spawn(0, input);
    }
    for (int player = 1; player <= scenario.counts().get(Count.PLAYERS); player++) {
      movePlayer(0, player, input);
    }
  }

  @Override
  public void replayState(long tick, WorldInput input) {
    for (int chunkX = 0; chunkX < world.chunksPerSide(); chunkX++) {
      for (int chunkZ = 0; chunkZ < world.chunksPerSide(); chunkZ++) {
        if (world.chunkLoaded(chunkX, chunkZ)) {
          input.accept(new WorldEvent.ChunkLoaded(tick, chunkX, chunkZ));
        }
      }
    }
    for (Map.Entry<Integer, Pos> mob : mobs.entrySet()) {
      input.accept(new WorldEvent.MobSpawned(tick, mob.getKey(), mob.getValue()));
    }
    for (Map.Entry<Integer, Pos> player : players.entrySet()) {
      input.accept(new WorldEvent.PlayerMoved(tick, player.getKey(), player.getValue()));
    }
  }

  private void loadEverything(WorldInput input) {
    for (int chunkX = 0; chunkX < world.chunksPerSide(); chunkX++) {
      for (int chunkZ = 0; chunkZ < world.chunksPerSide(); chunkZ++) {
        world.loadChunk(chunkX, chunkZ);
        input.accept(new WorldEvent.ChunkLoaded(0, chunkX, chunkZ));
      }
    }
  }

  private void churnChunks(long tick, WorldInput input) {
    if (rng.nextDouble() >= scenario.chances().get(Chance.CHUNK_CHURN)) {
      return;
    }
    int chunkX = rng.nextInt(world.chunksPerSide());
    int chunkZ = rng.nextInt(world.chunksPerSide());
    if (world.chunkLoaded(chunkX, chunkZ)) {
      // Mobs leave with their chunk, and as many appear in the chunks still loaded.
      int left = 0;
      Iterator<Map.Entry<Integer, Pos>> each = mobs.entrySet().iterator();
      while (each.hasNext()) {
        Map.Entry<Integer, Pos> mob = each.next();
        if (Chunks.of(mob.getValue().x()) == chunkX && Chunks.of(mob.getValue().z()) == chunkZ) {
          each.remove();
          input.accept(new WorldEvent.MobRemoved(tick, mob.getKey()));
          left++;
        }
      }
      world.unloadChunk(chunkX, chunkZ);
      input.accept(new WorldEvent.ChunkUnloaded(tick, chunkX, chunkZ));
      for (int i = 0; i < left; i++) {
        spawn(tick, input);
      }
    } else {
      world.loadChunk(chunkX, chunkZ);
      input.accept(new WorldEvent.ChunkLoaded(tick, chunkX, chunkZ));
    }
  }

  /**
   * Changes use every type a cell may carry, in a band around the surface, so a column encodes into
   * more than the two runs flat terrain would give.
   */
  private void changeBlocks(long tick, WorldInput input) {
    int changes = rng.nextInt(scenario.counts().get(Count.BLOCK_CHANGES_PER_TICK) + 1);
    for (int i = 0; i < changes; i++) {
      int x = rng.nextInt(world.size());
      int z = rng.nextInt(world.size());
      if (!world.chunkLoaded(Chunks.of(x), Chunks.of(z))) {
        // Minecraft cannot change a block in a chunk it does not hold.
        continue;
      }
      int y =
          Math.clamp(
              world.groundAt(x, z) + rng.nextInt(2 * BLOCK_CHANGE_SPREAD + 1) - BLOCK_CHANGE_SPREAD,
              world.minY(),
              world.minY() + world.height() - 1);
      byte type = PER_BLOCK_TYPES[rng.nextInt(PER_BLOCK_TYPES.length)];
      int floor = SimWorld.floorFor(type, rng);
      world.set(x, y, z, type, floor);
      input.accept(new WorldEvent.BlockChanged(tick, new Pos(x, y, z), type, floor));
    }
  }

  private void churnMobs(long tick, WorldInput input) {
    if (mobs.isEmpty() || rng.nextDouble() >= scenario.chances().get(Chance.MOB_CHURN)) {
      return;
    }
    input.accept(new WorldEvent.MobRemoved(tick, removeNth(rng.nextInt(mobs.size()))));
    spawn(tick, input);
  }

  /**
   * Mobs wander, so successive requests for one mob can fall in different regions, and a mob's
   * owner can change during a run. A mob never walks into a chunk that is not loaded, as vanilla
   * does not tick it there.
   */
  private void moveMobs() {
    for (Map.Entry<Integer, Pos> mob : mobs.entrySet()) {
      int x = Math.clamp(mob.getValue().x() + rng.nextInt(3) - 1, 0, world.size() - 1);
      int z = Math.clamp(mob.getValue().z() + rng.nextInt(3) - 1, 0, world.size() - 1);
      if (world.chunkLoaded(Chunks.of(x), Chunks.of(z))) {
        mob.setValue(new Pos(x, world.groundAt(x, z) + 1, z));
      }
    }
  }

  private void requestPaths(long tick, WorldInput input) {
    // Safe to iterate directly, because emitting an event never calls back into the workload.
    for (Map.Entry<Integer, Pos> mob : mobs.entrySet()) {
      if (rng.nextDouble() >= scenario.chances().get(Chance.REQUEST)) {
        continue;
      }
      // A stray start is outside every region, so no node owns it and no snapshot covers it.
      Pos start =
          rng.nextDouble() < scenario.chances().get(Chance.STRAY_REQUEST)
              ? outsideTheGrid()
              : mob.getValue();
      input.accept(
          new WorldEvent.PathRequested(
              tick, mob.getKey(), randomClass(), start, randomStanding(), randomSearch()));
      if (rng.nextDouble() < scenario.chances().get(Chance.REPEAT_REQUEST)) {
        input.accept(
            new WorldEvent.PathRequested(
                tick,
                mob.getKey(),
                randomClass(),
                mob.getValue(),
                randomStanding(),
                randomSearch()));
      }
    }
  }

  /**
   * The visit budget follows the follow range, not the length bound, because the navigation updates
   * it only when an attribute changes. A caller that passes a shorter bound therefore leaves the
   * budget unchanged. The two are drawn separately to cover that case.
   */
  private WorldEvent.Search randomSearch() {
    int followRange = MIN_PATH_LENGTH + rng.nextInt(MAX_EXTRA_PATH_LENGTH + 1);
    float maxPathLength = rng.nextInt(16) == 0 ? SHORT_PATH_LENGTH : followRange;
    int maxFallDistance =
        rng.nextBoolean()
            ? CALM_FALL_DISTANCE
            : CALM_FALL_DISTANCE + rng.nextInt(MAX_EXTRA_FALL_DISTANCE + 1);
    return new WorldEvent.Search(
        REACH_RANGES[rng.nextInt(REACH_RANGES.length)],
        maxPathLength,
        followRange * VISITED_NODES_PER_BLOCK,
        maxFallDistance);
  }

  /** The chosen mob may have no outstanding request, in which case the cancel must do nothing. */
  private void cancelPath(long tick, WorldInput input) {
    if (mobs.isEmpty() || rng.nextDouble() >= scenario.chances().get(Chance.CANCEL)) {
      return;
    }
    input.accept(new WorldEvent.PathCanceled(tick, mobsFrom(rng.nextInt(mobs.size())).next()));
  }

  /**
   * Players leave and return, and a move event announces each, so the set of players used for
   * shedding priority changes during a run.
   */
  private void movePlayers(long tick, WorldInput input) {
    int playerCount = scenario.counts().get(Count.PLAYERS);
    if (playerCount > 0 && rng.nextDouble() < scenario.chances().get(Chance.PLAYER_CHURN)) {
      int player = 1 + rng.nextInt(playerCount);
      if (players.remove(player) != null) {
        input.accept(new WorldEvent.PlayerLeft(tick, player));
      } else {
        movePlayer(tick, player, input);
      }
    }
    if (tick % PLAYER_MOVE_INTERVAL_TICKS != 0) {
      return;
    }
    for (int player : List.copyOf(players.keySet())) {
      movePlayer(tick, player, input);
    }
  }

  private void movePlayer(long tick, int player, WorldInput input) {
    Pos pos = randomStanding();
    players.put(player, pos);
    input.accept(new WorldEvent.PlayerMoved(tick, player, pos));
  }

  /** A mob appears in a loaded chunk, if one is found. */
  private void spawn(long tick, WorldInput input) {
    for (int i = 0; i < SPAWN_TRIES; i++) {
      Pos pos = randomStanding();
      if (world.chunkLoaded(Chunks.of(pos.x()), Chunks.of(pos.z()))) {
        int mobId = nextMobId++;
        mobs.put(mobId, pos);
        input.accept(new WorldEvent.MobSpawned(tick, mobId, pos));
        return;
      }
    }
  }

  /**
   * Drawn per request, not per mob, because a mob's profile is its current evaluator inputs. A goal
   * that changes its water malus gives it a different profile from one request to the next.
   */
  private short randomClass() {
    return (short) (1 + rng.nextInt(classCount));
  }

  private int removeNth(int position) {
    Iterator<Integer> ids = mobsFrom(position);
    int mobId = ids.next();
    ids.remove();
    return mobId;
  }

  /** Gets the key at an insertion position without copying the key set. */
  private Iterator<Integer> mobsFrom(int position) {
    Iterator<Integer> ids = mobs.keySet().iterator();
    for (int i = 0; i < position; i++) {
      ids.next();
    }
    return ids;
  }

  private Pos randomStanding() {
    int x = rng.nextInt(world.size());
    int z = rng.nextInt(world.size());
    return new Pos(x, world.groundAt(x, z) + 1, z);
  }

  private Pos outsideTheGrid() {
    int y = world.minY() + world.height() / 2;
    return rng.nextBoolean()
        ? new Pos(-1 - rng.nextInt(MAX_STRAY_DISTANCE), y, rng.nextInt(world.size()))
        : new Pos(rng.nextInt(world.size()), y, world.size() + rng.nextInt(MAX_STRAY_DISTANCE));
  }
}
