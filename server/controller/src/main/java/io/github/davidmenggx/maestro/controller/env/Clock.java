package io.github.davidmenggx.maestro.controller.env;

/**
 * Time as the controller sees it. Production advances {@code tick} from the server tick loop; the
 * simulator advances both values itself, so a replayed run reads back identical timestamps.
 */
public interface Clock {

  /** Server tick currently being serviced. Advances by one per tick and never runs backwards. */
  long tick();

  /**
   * Monotonic nanoseconds. Only differences carry meaning: this is not a wall clock, and it does
   * not survive a restart.
   */
  long nowNanos();
}
