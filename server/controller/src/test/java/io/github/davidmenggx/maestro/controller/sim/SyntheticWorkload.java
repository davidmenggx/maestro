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
 * Generated replacement for a recorded server trace. It can generate every event the boundary
 * defines. Activity kinds run in an order drawn per tick, so a sweep covers interleavings such as a
 * block change followed by its chunk leaving. {@code quietAfter} stops all world activity so the
 * run can drain.
 *
 * <p>As on a server, only chunks around spawn and each player start loaded, and mobs, block changes
 * and requests happen in loaded chunks.
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

  /** Chunks loaded around the origin in every direction, as vanilla keeps spawn chunks. */
  private static final int SPAWN_RADIUS = 2;

  /** Longest a leaving chunk cannot be read before it is reported gone. */
  private static final int MAX_LEAVING_TICKS = 5;

  /** `reachRange` values vanilla passes: 0 and 1 almost everywhere, 6 for one POI type. */
  private static final int[] REACH_RANGES = {0, 0, 1, 6};

  /**
   * `maxPathLength` is `max(FOLLOW_RANGE, requiredPathLength)`: follow range runs 12 to 64 and the
   * default `requiredPathLength` is 16, so 16 to 64. One caller passes 8.
   */
  private static final int MIN_PATH_LENGTH = 16;

  private static final int MAX_EXTRA_PATH_LENGTH = 48;

  private static final int SHORT_PATH_LENGTH = 8;

  /** `maxVisitedNodes` is sixteen times the bound in force when the navigation last updated it. */
  private static final int VISITED_NODES_PER_BLOCK = 16;

  /** `getMaxFallDistance` for a mob with no target. */
  private static final int CALM_FALL_DISTANCE = 3;

  /** Extra fall a mob with a target accepts: health above a third of max, less difficulty. */
  private static final int MAX_EXTRA_FALL_DISTANCE = 67;

  private final SimWorld world;
  private final Rng rng;
  private final Rng order;

  /**
   * Whether and when a chunk leaves slowly, from a stream of its own so other draws do not shift.
   */
  private final Rng leavings;

  private final Activity[] activities = {
    this::churnChunks,
    this::changeBlocks,
    this::churnMobs,
    (tick, input) -> moveMobs(),
    this::requestPaths,
    this::cancelPath,
    this::movePlayers
  };

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

  /** Chunks about to leave, as {@link Chunks#key}, with the tick each is reported gone. */
  private final Map<Long, Long> leaving = new LinkedHashMap<>();

  private int nextMobId = 1;

  public SyntheticWorkload(SimWorld world, Rng rng, long quietAfter, Scenario scenario) {
    this.world = world;
    this.rng = rng;
    this.order = rng.derive("order");
    this.leavings = rng.derive("leavings");
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
    leave(tick, input);
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
    loadAround(0, 0, SPAWN_RADIUS, input);
    for (int player = 1; player <= scenario.counts().get(Count.PLAYERS); player++) {
      movePlayer(0, player, input);
      Pos pos = players.get(player);
      loadAround(
          Chunks.of(pos.x()), Chunks.of(pos.z()), scenario.counts().get(Count.VIEW_RADIUS), input);
    }
    for (int i = 0; i < scenario.counts().get(Count.MOBS); i++) {
      spawn(0, input);
    }
  }

  @Override
  public void replayState(long tick, WorldInput input) {
    for (int chunkX = world.minChunk();
        chunkX < world.minChunk() + world.chunksPerSide();
        chunkX++) {
      for (int chunkZ = world.minChunk();
          chunkZ < world.minChunk() + world.chunksPerSide();
          chunkZ++) {
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

  /** Loads the chunks within {@code radius} of a chunk that lie in the world. */
  private void loadAround(int centerX, int centerZ, int radius, WorldInput input) {
    for (int chunkX = centerX - radius; chunkX <= centerX + radius; chunkX++) {
      for (int chunkZ = centerZ - radius; chunkZ <= centerZ + radius; chunkZ++) {
        if (world.contains(chunkX * Chunks.SIZE, chunkZ * Chunks.SIZE)
            && !world.chunkLoaded(chunkX, chunkZ)) {
          world.loadChunk(chunkX, chunkZ, 0);
          input.accept(new WorldEvent.ChunkLoaded(0, chunkX, chunkZ));
        }
      }
    }
  }

  /** Unloads a loaded chunk or loads any chunk, evenly, so chunks leave as often as they arrive. */
  private void churnChunks(long tick, WorldInput input) {
    if (rng.nextDouble() >= scenario.chances().get(Chance.CHUNK_CHURN)) {
      return;
    }
    if (rng.nextBoolean() && world.loadedCount() > 0) {
      long key = world.loadedChunk(rng.nextInt(world.loadedCount()));
      int chunkX = (int) (key >> 32);
      int chunkZ = (int) key;
      if (leaving.containsKey(key)) {
        return;
      }
      double slowly = scenario.chances().get(Chance.LEAVE_UNREADABLE);
      if (slowly > 0 && leavings.nextDouble() < slowly) {
        long gone = tick + 1 + leavings.nextInt(MAX_LEAVING_TICKS);
        // Gone before the run goes quiet, so a quiet world has no chunk it cannot read.
        if (gone < quietAfter) {
          world.beginLeaving(chunkX, chunkZ);
          leaving.put(key, gone);
          return;
        }
      }
      unload(tick, chunkX, chunkZ, input);
      return;
    }
    int chunkX = world.minChunk() + rng.nextInt(world.chunksPerSide());
    int chunkZ = world.minChunk() + rng.nextInt(world.chunksPerSide());
    if (!world.chunkLoaded(chunkX, chunkZ)) {
      world.loadChunk(chunkX, chunkZ, tick);
      input.accept(new WorldEvent.ChunkLoaded(tick, chunkX, chunkZ));
    }
  }

  /**
   * Each leaving chunk whose time has come is reported gone, or, at even odds, stays after all and
   * can be read again with nothing reported.
   */
  private void leave(long tick, WorldInput input) {
    Iterator<Map.Entry<Long, Long>> each = leaving.entrySet().iterator();
    while (each.hasNext()) {
      Map.Entry<Long, Long> chunk = each.next();
      if (chunk.getValue() > tick) {
        continue;
      }
      each.remove();
      int chunkX = (int) (chunk.getKey() >> 32);
      int chunkZ = (int) (long) chunk.getKey();
      if (leavings.nextBoolean()) {
        world.stayLoaded(chunkX, chunkZ, tick);
      } else {
        unload(tick, chunkX, chunkZ, input);
      }
    }
  }

  /** Unloads a chunk. Mobs leave with it, and as many appear in the chunks still loaded. */
  private void unload(long tick, int chunkX, int chunkZ, WorldInput input) {
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
  }

  /** Changes use every cell type in a band around the surface, so columns encode into many runs. */
  private void changeBlocks(long tick, WorldInput input) {
    int changes = rng.nextInt(scenario.counts().get(Count.BLOCK_CHANGES_PER_TICK) + 1);
    for (int i = 0; i < changes && world.loadedCount() > 0; i++) {
      // Minecraft cannot change a block in a chunk it does not hold.
      long chunk = world.loadedChunk(rng.nextInt(world.loadedCount()));
      int x = (int) (chunk >> 32) * Chunks.SIZE + rng.nextInt(Chunks.SIZE);
      int z = (int) chunk * Chunks.SIZE + rng.nextInt(Chunks.SIZE);
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

  /** Mobs wander, so a mob's region can change. They never enter unloaded chunks, as in vanilla. */
  private void moveMobs() {
    for (Map.Entry<Integer, Pos> mob : mobs.entrySet()) {
      int x = mob.getValue().x() + rng.nextInt(3) - 1;
      int z = mob.getValue().z() + rng.nextInt(3) - 1;
      if (world.contains(x, z) && world.chunkLoaded(Chunks.of(x), Chunks.of(z))) {
        mob.setValue(new Pos(x, world.groundAt(x, z) + 1, z));
      }
    }
  }

  private void requestPaths(long tick, WorldInput input) {
    // Emitting never calls back into the workload, so direct iteration is safe.
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
              tick,
              mob.getKey(),
              randomClass(),
              start,
              randomStanding(),
              randomSearch(),
              hunting()));
      if (rng.nextDouble() < scenario.chances().get(Chance.REPEAT_REQUEST)) {
        input.accept(
            new WorldEvent.PathRequested(
                tick,
                mob.getKey(),
                randomClass(),
                mob.getValue(),
                randomStanding(),
                randomSearch(),
                hunting()));
      }
    }
  }

  /**
   * The visit budget follows the follow range, not the length bound, since the navigation updates
   * it only when an attribute changes. The two are drawn separately.
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

  /** Players leave and return, each announced by a move event. */
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

  /** A mob appears in a loaded chunk, if there is one. */
  private void spawn(long tick, WorldInput input) {
    if (world.loadedCount() == 0) {
      return;
    }
    long chunk = world.loadedChunk(rng.nextInt(world.loadedCount()));
    int x = (int) (chunk >> 32) * Chunks.SIZE + rng.nextInt(Chunks.SIZE);
    int z = (int) chunk * Chunks.SIZE + rng.nextInt(Chunks.SIZE);
    Pos pos = new Pos(x, world.groundAt(x, z) + 1, z);
    int mobId = nextMobId++;
    mobs.put(mobId, pos);
    input.accept(new WorldEvent.MobSpawned(tick, mobId, pos));
  }

  /** Drawn per request, since a mob gains and loses its target. */
  private boolean hunting() {
    return rng.nextDouble() < scenario.chances().get(Chance.HUNTING);
  }

  /** Drawn per request, since a goal can change a mob's evaluator inputs between requests. */
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
    int x = world.min() + rng.nextInt(world.size());
    int z = world.min() + rng.nextInt(world.size());
    return new Pos(x, world.groundAt(x, z) + 1, z);
  }

  private Pos outsideTheGrid() {
    int y = world.minY() + world.height() / 2;
    int min = world.min();
    int end = min + world.size();
    return rng.nextBoolean()
        ? new Pos(min - 1 - rng.nextInt(MAX_STRAY_DISTANCE), y, min + rng.nextInt(world.size()))
        : new Pos(min + rng.nextInt(world.size()), y, end + rng.nextInt(MAX_STRAY_DISTANCE));
  }
}
