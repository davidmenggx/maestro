package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.world.WorldInput;

/** Source of world events for a run. */
public interface Workload {

  void tick(long tick, WorldInput input);
}
