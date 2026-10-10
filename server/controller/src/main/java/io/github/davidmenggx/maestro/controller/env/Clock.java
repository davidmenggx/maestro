package io.github.davidmenggx.maestro.controller.env;

/**
 * Time as the controller sees it. Production advances {@code tick} from the server tick loop. The
 * simulator advances both values itself, so a replayed run sees identical timestamps.
 */
public interface Clock {

  /** Server tick currently being handled. Increases by one per tick and never decreases. */
  long tick();

  /** Monotonic nanoseconds (not wall clock). */
  long nowNanos();
}
