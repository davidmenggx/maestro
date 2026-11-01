package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Replays a fixed list of events, for tests where a random workload would not reach the case. */
public final class ScriptedWorkload implements Workload {

  // Looked up by tick and never iterated, so hash order cannot affect a run.
  private final Map<Long, List<WorldEvent>> byTick = new HashMap<>();
  private final SimWorld world;

  /** The mobs and players placed and not yet removed, in placement order. */
  private final Map<Integer, Pos> mobs = new LinkedHashMap<>();

  private final Map<Integer, Pos> players = new LinkedHashMap<>();

  /**
   * Chunks, as {@link Chunks#key}, that cannot be read from each tick, and those that can again.
   */
  private final Map<Long, List<Long>> unreadableFrom = new HashMap<>();

  private final Map<Long, List<Long>> readableFrom = new HashMap<>();

  public ScriptedWorkload(SimWorld world, List<WorldEvent> events) {
    this.world = Objects.requireNonNull(world, "world");
    for (WorldEvent event : events) {
      byTick.computeIfAbsent(event.tick(), tick -> new ArrayList<>()).add(event);
    }
  }

  /**
   * The chunk, loaded by then, cannot be read from {@code from} until {@code until}, as one about
   * to leave, and can be again then unless it left meanwhile. Nothing is reported for either.
   */
  public ScriptedWorkload unreadable(int chunkX, int chunkZ, long from, long until) {
    long key = Chunks.key(chunkX, chunkZ);
    unreadableFrom.computeIfAbsent(from, tick -> new ArrayList<>()).add(key);
    readableFrom.computeIfAbsent(until, tick -> new ArrayList<>()).add(key);
    return this;
  }

  @Override
  public void tick(long tick, WorldInput input) {
    for (WorldEvent event : byTick.getOrDefault(tick, List.of())) {
      apply(event);
      input.accept(event);
    }
    for (long key : unreadableFrom.getOrDefault(tick, List.of())) {
      world.beginLeaving((int) (key >> 32), (int) key);
    }
    for (long key : readableFrom.getOrDefault(tick, List.of())) {
      world.stayLoaded((int) (key >> 32), (int) key, tick);
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

  private void apply(WorldEvent event) {
    switch (event) {
      case WorldEvent.MobSpawned spawned -> mobs.put(spawned.mobId(), spawned.pos());
      case WorldEvent.MobRemoved removed -> mobs.remove(removed.mobId());
      case WorldEvent.PlayerMoved moved -> players.put(moved.playerId(), moved.pos());
      case WorldEvent.PlayerLeft left -> players.remove(left.playerId());
      case WorldEvent.ChunkLoaded loaded ->
          world.loadChunk(loaded.chunkX(), loaded.chunkZ(), loaded.tick());
      case WorldEvent.ChunkUnloaded unloaded ->
          world.unloadChunk(unloaded.chunkX(), unloaded.chunkZ());
      case WorldEvent.BlockChanged changed ->
          world.set(
              changed.pos().x(),
              changed.pos().y(),
              changed.pos().z(),
              changed.pathType(),
              changed.floor());
      default -> {}
    }
  }
}
