package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import maestro.fbs.PathType;

/**
 * Generated stand-in for a recorded server trace, and the fuzzer of the suite: every event the
 * boundary defines is reachable from here, and the seed sweep explores their orderings. {@code
 * quietAfter} stops all world activity so the tail of a run can drain, which is what makes "every
 * request was answered or dropped" a checkable statement.
 */
public final class SyntheticWorkload implements Workload {

  /** A mob repaths every 15 ticks or so, which is roughly what vanilla does. */
  public static final double DEFAULT_REQUEST_CHANCE = 0.07;

  private static final int PLAYER_ID = 1;
  private static final int MAX_BLOCK_CHANGES_PER_TICK = 3;
  private static final int PLAYER_MOVE_INTERVAL_TICKS = 5;
  private static final double CHURN_CHANCE = 0.05;
  private static final double CANCEL_CHANCE = 0.02;
  private static final double PLAYER_LEAVE_CHANCE = 0.01;

  private final SimWorld world;
  private final Rng rng;
  private final long quietAfter;
  private final Scenario scenario;
  private final List<MobClass> classes;
  private final Map<Integer, Pos> mobs = new LinkedHashMap<>();

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
    requestPaths(tick, input);
    cancelPath(tick, input);
    movePlayer(tick, input);
  }

  private void start(WorldInput input) {
    reloadEverything(0, input);
    for (int i = 0; i < scenario.mobCount(); i++) {
      spawn(0, input);
    }
    input.accept(new WorldEvent.PlayerMoved(0, PLAYER_ID, randomStanding()));
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

  private void changeBlocks(long tick, WorldInput input) {
    int changes = 1 + rng.nextInt(MAX_BLOCK_CHANGES_PER_TICK);
    for (int i = 0; i < changes; i++) {
      int x = rng.nextInt(world.size());
      int z = rng.nextInt(world.size());
      if (!world.chunkLoaded(x / SimWorld.CHUNK_SIZE, z / SimWorld.CHUNK_SIZE)) {
        // Minecraft cannot change a block it is not holding.
        continue;
      }
      int y = world.groundAt(x, z) + 1;
      byte type = rng.nextBoolean() ? (byte) PathType.BLOCKED : (byte) PathType.WATER;
      world.set(x, y, z, type);
      input.accept(new WorldEvent.BlockChanged(tick, new Pos(x, y, z), type));
    }
  }

  private void churnMobs(long tick, WorldInput input) {
    if (mobs.isEmpty() || rng.nextDouble() >= CHURN_CHANCE) {
      return;
    }
    input.accept(new WorldEvent.MobRemoved(tick, removeNth(rng.nextInt(mobs.size()))));
    spawn(tick, input);
  }

  private void requestPaths(long tick, WorldInput input) {
    // Safe to iterate directly: emitting an event never calls back into the workload.
    for (Map.Entry<Integer, Pos> mob : mobs.entrySet()) {
      if (rng.nextDouble() < scenario.requestChance()) {
        input.accept(
            new WorldEvent.PathRequested(tick, mob.getKey(), mob.getValue(), randomStanding()));
      }
    }
  }

  /** Cancels for a mob with nothing outstanding are a no-op, which is itself worth exercising. */
  private void cancelPath(long tick, WorldInput input) {
    if (mobs.isEmpty() || rng.nextDouble() >= CANCEL_CHANCE) {
      return;
    }
    input.accept(new WorldEvent.PathCancelled(tick, nth(rng.nextInt(mobs.size()))));
  }

  private void movePlayer(long tick, WorldInput input) {
    if (rng.nextDouble() < PLAYER_LEAVE_CHANCE) {
      input.accept(new WorldEvent.PlayerLeft(tick, PLAYER_ID));
      return;
    }
    if (tick % PLAYER_MOVE_INTERVAL_TICKS == 0) {
      input.accept(new WorldEvent.PlayerMoved(tick, PLAYER_ID, randomStanding()));
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
}
