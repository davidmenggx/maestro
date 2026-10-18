package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.ArrayList;
import java.util.HashMap;
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

  private void apply(WorldEvent event) {
    switch (event) {
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
