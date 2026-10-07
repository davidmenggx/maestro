package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Chance;
import io.github.davidmenggx.maestro.controller.sim.Scenario.Count;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Generated stand-in for a recorded server trace, and the fuzzer of the suite: every event the
 * boundary defines is reachable from here, and the seed sweep explores their orderings. {@code
 * quietAfter} stops all world activity so the tail of a run drains, so by the end every request has
 * been answered or dropped and the invariants can require it.
 */
public final class SyntheticWorkload implements Workload {

  private static final int PLAYER_MOVE_INTERVAL_TICKS = 5;

  /** Vertical spread of block changes around the surface. */
  private static final int BLOCK_CHANGE_SPREAD = 2;

  /** How far outside the region grid a stray request starts. */
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

  private final SimWorld world;
  private final Rng rng;
  private final long quietAfter;
  private final Scenario scenario;
  private final int classCount;
  private final Map<Integer, Pos> mobs = new LinkedHashMap<>();
  private final Set<Integer> players = new LinkedHashSet<>();

  private int nextMobId = 1;

  public SyntheticWorkload(SimWorld world, Rng rng, long quietAfter, Scenario scenario) {
    this.world = world;
    this.rng = rng;
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
      // Everything unloaded during the run comes back, so the final state is
      // comparable with a fresh snapshot of the same world.
      reloadEverything(tick, input);
      return;
    }
    churnChunks(tick, input);
    changeBlocks(tick, input);
    churnMobs(tick, input);
    moveMobs();
    requestPaths(tick, input);
    cancelPath(tick, input);
    movePlayers(tick, input);
  }

  private void start(WorldInput input) {
    reloadEverything(0, input);
    for (int i = 0; i < scenario.counts().get(Count.MOBS); i++) {
      spawn(0, input);
    }
    for (int player = 1; player <= scenario.counts().get(Count.PLAYERS); player++) {
      players.add(player);
      input.accept(new WorldEvent.PlayerMoved(0, player, randomStanding()));
    }
  }

  private void reloadEverything(long tick, WorldInput input) {
    for (int chunkX = 0; chunkX < world.chunksPerSide(); chunkX++) {
      for (int chunkZ = 0; chunkZ < world.chunksPerSide(); chunkZ++) {
        if (world.chunkLoaded(chunkX, chunkZ)) {
          continue;
        }
        world.loadChunk(chunkX, chunkZ);
        input.accept(new WorldEvent.ChunkLoaded(tick, chunkX, chunkZ));
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
      world.unloadChunk(chunkX, chunkZ);
      input.accept(new WorldEvent.ChunkUnloaded(tick, chunkX, chunkZ));
    } else {
      world.loadChunk(chunkX, chunkZ);
      input.accept(new WorldEvent.ChunkLoaded(tick, chunkX, chunkZ));
    }
  }

  /**
   * Changes are drawn from every type a cell may carry and from a band around the surface, so a
   * column run-length encodes into more than the two runs flat terrain would give.
   */
  private void changeBlocks(long tick, WorldInput input) {
    int changes = rng.nextInt(scenario.counts().get(Count.BLOCK_CHANGES_PER_TICK) + 1);
    for (int i = 0; i < changes; i++) {
      int x = rng.nextInt(world.size());
      int z = rng.nextInt(world.size());
      if (!world.chunkLoaded(Chunks.of(x), Chunks.of(z))) {
        // Minecraft cannot change a block it is not holding.
        continue;
      }
      int y =
          Math.clamp(
              world.groundAt(x, z) + rng.nextInt(2 * BLOCK_CHANGE_SPREAD + 1) - BLOCK_CHANGE_SPREAD,
              world.minY(),
              world.minY() + world.height() - 1);
      byte type = PathTypes.perBlock(rng.nextInt(PathTypes.perBlockCount()));
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
   * Mobs wander, so successive requests for one mob can fall in different regions and the owner of
   * a mob is not fixed for the life of the run.
   */
  private void moveMobs() {
    for (Map.Entry<Integer, Pos> mob : mobs.entrySet()) {
      int x = Math.clamp(mob.getValue().x() + rng.nextInt(3) - 1, 0, world.size() - 1);
      int z = Math.clamp(mob.getValue().z() + rng.nextInt(3) - 1, 0, world.size() - 1);
      mob.setValue(new Pos(x, world.groundAt(x, z) + 1, z));
    }
  }

  private void requestPaths(long tick, WorldInput input) {
    // Safe to iterate directly: emitting an event never calls back into the workload.
    for (Map.Entry<Integer, Pos> mob : mobs.entrySet()) {
      if (rng.nextDouble() >= scenario.chances().get(Chance.REQUEST)) {
        continue;
      }
      // A stray start is outside every region, which no node owns and no snapshot covers.
      Pos start =
          rng.nextDouble() < scenario.chances().get(Chance.STRAY_REQUEST)
              ? outsideTheGrid()
              : mob.getValue();
      input.accept(
          new WorldEvent.PathRequested(
              tick, mob.getKey(), randomClass(), start, randomStanding(), randomSearch()));
    }
  }

  /**
   * The visit budget follows the follow range rather than the length bound, because the navigation
   * updates it only when an attribute changes. A caller that passes a shorter bound therefore
   * leaves the budget where it was, which is the case the two are drawn separately to reach.
   */
  private WorldEvent.Search randomSearch() {
    int followRange = MIN_PATH_LENGTH + rng.nextInt(MAX_EXTRA_PATH_LENGTH + 1);
    float maxPathLength = rng.nextInt(16) == 0 ? SHORT_PATH_LENGTH : followRange;
    return new WorldEvent.Search(
        REACH_RANGES[rng.nextInt(REACH_RANGES.length)],
        maxPathLength,
        followRange * VISITED_NODES_PER_BLOCK);
  }

  /** The chosen mob may have nothing outstanding, in which case the cancel must do nothing. */
  private void cancelPath(long tick, WorldInput input) {
    if (mobs.isEmpty() || rng.nextDouble() >= scenario.chances().get(Chance.CANCEL)) {
      return;
    }
    input.accept(new WorldEvent.PathCanceled(tick, mobsFrom(rng.nextInt(mobs.size())).next()));
  }

  /**
   * Players leave and come back, and a move is what announces one, so the set the shedding priority
   * will read is never fixed for a whole run.
   */
  private void movePlayers(long tick, WorldInput input) {
    int playerCount = scenario.counts().get(Count.PLAYERS);
    if (playerCount > 0 && rng.nextDouble() < scenario.chances().get(Chance.PLAYER_CHURN)) {
      int player = 1 + rng.nextInt(playerCount);
      if (players.remove(player)) {
        input.accept(new WorldEvent.PlayerLeft(tick, player));
      } else {
        players.add(player);
        input.accept(new WorldEvent.PlayerMoved(tick, player, randomStanding()));
      }
    }
    if (tick % PLAYER_MOVE_INTERVAL_TICKS != 0) {
      return;
    }
    for (int player : players) {
      input.accept(new WorldEvent.PlayerMoved(tick, player, randomStanding()));
    }
  }

  private void spawn(long tick, WorldInput input) {
    int mobId = nextMobId++;
    Pos pos = randomStanding();
    mobs.put(mobId, pos);
    input.accept(new WorldEvent.MobSpawned(tick, mobId, pos));
  }

  /**
   * Drawn per request rather than per mob, because a mob's profile is its live evaluator inputs: a
   * goal that changes its water malus gives it a different one from one request to the next.
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

  /** Reaches an insertion position without copying the key set. */
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
