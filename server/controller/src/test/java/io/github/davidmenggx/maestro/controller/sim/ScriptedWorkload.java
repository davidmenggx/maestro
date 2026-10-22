package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Replays a fixed list of events, applying the world changes they describe. A recorded server trace
 * will have this form. Focused tests use it when a random workload would not reliably produce the
 * situation under test.
 */
public final class ScriptedWorkload implements Workload {

  // Looked up by tick and never iterated, so hash order cannot affect a run.
  private final Map<Long, List<WorldEvent>> byTick = new HashMap<>();
  private final SimWorld world;

  /**
   * The mobs and players the script has placed and not yet removed, in the order it placed them.
   */
  private final Map<Integer, Pos> mobs = new LinkedHashMap<>();

  private final Map<Integer, Pos> players = new LinkedHashMap<>();

  public ScriptedWorkload(SimWorld world, List<WorldEvent> events) {
    this.world = Objects.requireNonNull(world, "world");
    for (WorldEvent event : events) {
      byTick.computeIfAbsent(event.tick(), tick -> new ArrayList<>()).add(event);
    }
  }

  @Override
  public void tick(long tick, WorldInput input) {
    for (WorldEvent event : byTick.getOrDefault(tick, List.of())) {
      apply(event);
      input.accept(event);
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
