package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.world.WorldInput;

/** Source of world events for a run. */
public interface Workload {

  void tick(long tick, WorldInput input);

  /** Announces the world as it stands to a new controller process; refuses if it cannot. */
  default void replayState(long tick, WorldInput input) {
    throw new UnsupportedOperationException("this workload cannot replay the world");
  }
}
