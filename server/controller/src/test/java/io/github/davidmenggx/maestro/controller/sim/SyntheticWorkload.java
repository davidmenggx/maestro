package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generated stand-in for a recorded server trace, and the fuzzer of the suite: every event the
 * boundary defines is reachable from here, and the seed sweep explores their orderings. {@code
 * quietAfter} stops all world activity so the tail of a run drains, so by the end every request has
 * been answered or dropped and the invariants can require it.
 */
public final class SyntheticWorkload implements Workload {

  /** A mob repaths every 15 ticks or so, which is roughly what vanilla does. */
  public static final double DEFAULT_REQUEST_CHANCE = 0.07;

  private static final int PLAYER_MOVE_INTERVAL_TICKS = 5;

  /** Vertical spread of block changes around the surface. */
  private static final int BLOCK_CHANGE_SPREAD = 2;

  /** How far outside the region grid a stray request starts. */
  private static final int MAX_STRAY_DISTANCE = 8;

  private final SimWorld world;
  private final Rng rng;
  private final long quietAfter;
  private final Scenario scenario;
  private final List<MobClass> classes;
  private final Map<Integer, Pos> mobs = new LinkedHashMap<>();
  private final Set<Integer> players = new LinkedHashSet<>();

  private int nextMobId = 1;

  public SyntheticWorkload(SimWorld world, Rng rng, long quietAfter, Scenario scenario) {
    this.world = world;
    this.rng = rng;
    this.quietAfter = quietAfter;
    this.scenario = scenario;
    this.classes = world.mobClasses();
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
    for (int i = 0; i < scenario.mobCount(); i++) {
      spawn(0, input);
    }
    for (int player = 1; player <= scenario.playerCount(); player++) {
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
    if (rng.nextDouble() >= scenario.chunkChurnChance()) {
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
    int changes = rng.nextInt(scenario.maxBlockChangesPerTick() + 1);
    for (int i = 0; i < changes; i++) {
      int x = rng.nextInt(world.size());
      int z = rng.nextInt(world.size());
      if (!world.chunkLoaded(x / SimWorld.CHUNK_SIZE, z / SimWorld.CHUNK_SIZE)) {
        // Minecraft cannot change a block it is not holding.
        continue;
      }
      int y =
          Math.clamp(
              world.groundAt(x, z) + rng.nextInt(2 * BLOCK_CHANGE_SPREAD + 1) - BLOCK_CHANGE_SPREAD,
              world.minY(),
              world.minY() + world.height() - 1);
      byte type = PathTypes.perBlock(rng.nextInt(PathTypes.perBlockCount()));
      world.set(x, y, z, type);
      input.accept(new WorldEvent.BlockChanged(tick, new Pos(x, y, z), type));
    }
  }

  private void churnMobs(long tick, WorldInput input) {
    if (mobs.isEmpty() || rng.nextDouble() >= scenario.mobChurnChance()) {
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
      if (rng.nextDouble() >= scenario.requestChance()) {
        continue;
      }
      // A stray start is outside every region, which no node owns and no snapshot covers.
      Pos start =
          rng.nextDouble() < scenario.strayRequestChance() ? outsideTheGrid() : mob.getValue();
      input.accept(new WorldEvent.PathRequested(tick, mob.getKey(), start, randomStanding()));
    }
  }

  /** The chosen mob may have nothing outstanding, in which case the cancel must do nothing. */
  private void cancelPath(long tick, WorldInput input) {
    if (mobs.isEmpty() || rng.nextDouble() >= scenario.cancelChance()) {
      return;
    }
    input.accept(new WorldEvent.PathCanceled(tick, nth(rng.nextInt(mobs.size()))));
  }

  /**
   * Players leave and come back, and a move is what announces one, so the set the shedding priority
   * will read is never fixed for a whole run.
   */
  private void movePlayers(long tick, WorldInput input) {
    if (scenario.playerCount() > 0 && rng.nextDouble() < scenario.playerChurnChance()) {
      int player = 1 + rng.nextInt(scenario.playerCount());
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
    short classId = classes.get(rng.nextInt(classes.size())).classId();
    mobs.put(mobId, pos);
    input.accept(new WorldEvent.MobSpawned(tick, mobId, classId, pos));
  }

  private int nth(int position) {
    Iterator<Integer> ids = mobs.keySet().iterator();
    for (int i = 0; i < position; i++) {
      ids.next();
    }
    return ids.next();
  }

  /** Removes the mob at the given insertion position without copying the key set. */
  private int removeNth(int position) {
    Iterator<Integer> ids = mobs.keySet().iterator();
    for (int i = 0; i < position; i++) {
      ids.next();
    }
    int mobId = ids.next();
    ids.remove();
    return mobId;
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
