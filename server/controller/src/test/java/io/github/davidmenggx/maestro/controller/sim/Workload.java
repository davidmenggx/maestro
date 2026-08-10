package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.world.WorldInput;

/**
 * Source of world events for a run. Minecraft is an input, not a participant, so this is either a
 * generator or a replayed trace; the controller cannot tell which.
 */
public interface Workload {

  void tick(long tick, WorldInput input);
}
