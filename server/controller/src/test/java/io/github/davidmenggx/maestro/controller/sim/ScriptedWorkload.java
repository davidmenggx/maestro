package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a fixed list of events, applying the world mutations they describe as it goes. This is
 * the shape a recorded server trace will take, and it is what focused tests use when a random
 * workload would not reliably produce the situation under test.
 */
public final class ScriptedWorkload implements Workload {

  // Looked up by tick and never iterated, so hash order cannot reach a run.
  private final Map<Long, List<WorldEvent>> byTick = new HashMap<>();
  private final SimWorld world;

  public ScriptedWorkload(SimWorld world, List<WorldEvent> events) {
    this.world = java.util.Objects.requireNonNull(world, "world");
    for (WorldEvent event : events) {
      byTick.computeIfAbsent(event.tick(), k -> new ArrayList<>()).add(event);
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
      case WorldEvent.ChunkLoaded e -> world.loadChunk(e.chunkX(), e.chunkZ());
      case WorldEvent.BlockChanged e ->
          world.set(e.pos().x(), e.pos().y(), e.pos().z(), e.pathType());
      default -> {}
    }
  }
}
