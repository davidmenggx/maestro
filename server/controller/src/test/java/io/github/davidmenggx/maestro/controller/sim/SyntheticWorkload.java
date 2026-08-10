package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import maestro.fbs.PathType;

/**
 * Generated stand-in for a recorded server trace: chunks load, mobs come and go, blocks change and
 * mobs ask for paths. {@code quietAfter} stops new requests so the tail of a run can drain, which
 * is what makes "every request was answered or dropped" a checkable statement.
 */
public final class SyntheticWorkload implements Workload {

  private static final short MOB_CLASS = 1;
  private static final int PLAYER_ID = 1;
  private static final double CHURN_CHANCE = 0.05;

  /** A mob repaths every 15 ticks or so, which is roughly what vanilla does. */
  public static final double DEFAULT_REQUEST_CHANCE = 0.07;

  private final SimWorld world;
  private final Rng rng;
  private final int mobCount;
  private final long quietAfter;
  private final double requestChance;
  private final Map<Integer, Pos> mobs = new LinkedHashMap<>();

  private int nextMobId = 1;
  private int requestsIssued;

  public SyntheticWorkload(
      SimWorld world, Rng rng, int mobCount, long quietAfter, double requestChance) {
    this.world = world;
    this.rng = rng;
    this.mobCount = mobCount;
    this.quietAfter = quietAfter;
    this.requestChance = requestChance;
  }

  public int requestsIssued() {
    return requestsIssued;
  }

  @Override
  public void tick(long tick, WorldInput input) {
    if (tick == 0) {
      start(input);
      return;
    }

    if (tick >= quietAfter) {
      return;
    }
    changeBlocks(tick, input);
    churn(tick, input);
    requestPaths(tick, input);
    if (tick % 5 == 0) {
      input.accept(new WorldEvent.PlayerMoved(tick, PLAYER_ID, randomStanding()));
    }
  }

  private void start(WorldInput input) {
    for (int chunkX = 0; chunkX < world.sizeX() / 16; chunkX++) {
      for (int chunkZ = 0; chunkZ < world.sizeZ() / 16; chunkZ++) {
        world.loadChunk(chunkX, chunkZ);
        input.accept(new WorldEvent.ChunkLoaded(0, chunkX, chunkZ));
      }
    }
    for (int i = 0; i < mobCount; i++) {
      spawn(0, input);
    }
    input.accept(new WorldEvent.PlayerMoved(0, PLAYER_ID, randomStanding()));
  }

  private void changeBlocks(long tick, WorldInput input) {
    int changes = 1 + rng.nextInt(3);
    for (int i = 0; i < changes; i++) {
      int x = rng.nextInt(world.sizeX());
      int z = rng.nextInt(world.sizeZ());
      int y = world.groundAt(x, z) + 1;
      byte type = rng.nextBoolean() ? (byte) PathType.BLOCKED : (byte) PathType.WATER;
      world.set(x, y, z, type);
      input.accept(new WorldEvent.BlockChanged(tick, new Pos(x, y, z), type));
    }
  }

  private void churn(long tick, WorldInput input) {
    if (mobs.isEmpty() || rng.nextDouble() >= CHURN_CHANCE) {
      return;
    }
    List<Integer> ids = new ArrayList<>(mobs.keySet());
    int victim = ids.get(rng.nextInt(ids.size()));
    mobs.remove(victim);
    input.accept(new WorldEvent.MobRemoved(tick, victim));
    spawn(tick, input);
  }

  private void requestPaths(long tick, WorldInput input) {
    for (Map.Entry<Integer, Pos> mob : new ArrayList<>(mobs.entrySet())) {
      if (rng.nextDouble() >= requestChance) {
        continue;
      }
      requestsIssued++;
      input.accept(
          new WorldEvent.PathRequested(tick, mob.getKey(), mob.getValue(), randomStanding()));
    }
  }

  private void spawn(long tick, WorldInput input) {
    int mobId = nextMobId++;
    Pos pos = randomStanding();
    mobs.put(mobId, pos);
    input.accept(new WorldEvent.MobSpawned(tick, mobId, MOB_CLASS, pos));
  }

  private Pos randomStanding() {
    int x = rng.nextInt(world.sizeX());
    int z = rng.nextInt(world.sizeZ());
    return new Pos(x, world.groundAt(x, z) + 1, z);
  }
}
