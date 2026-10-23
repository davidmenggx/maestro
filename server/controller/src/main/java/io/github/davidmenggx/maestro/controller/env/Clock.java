package io.github.davidmenggx.maestro.controller.env;

/** Time as the controller sees it. */
public interface Clock {

  /** Server tick currently being handled. Increases by one per tick and never decreases. */
  long tick();

  /** Monotonic nanoseconds (not wall clock). */
  long nowNanos();
}
