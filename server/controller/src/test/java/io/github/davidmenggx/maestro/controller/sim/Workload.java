package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.world.WorldInput;

/** Source of world events for a run. */
public interface Workload {

  void tick(long tick, WorldInput input);

  /**
   * Announces the world as it stands, as a starting server would to a new controller process: every
   * loaded chunk, mob and player. A workload that cannot refuses, so a restarted controller is
   * never left unaware of the world.
   */
  default void replayState(long tick, WorldInput input) {
    throw new UnsupportedOperationException("this workload cannot replay the world");
  }
}
