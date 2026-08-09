package com.smpler.maestro.controller.env;

import java.util.Objects;

/**
 * Every source of nondeterminism the controller is allowed to reach. Production wires the real
 * implementations, the simulator wires deterministic ones; controller code takes this record and
 * nothing else, which is what keeps a run replayable from its seed.
 */
public record ControllerEnv(
    Clock clock, Rng rng, Offload offload, Transport transport, Metrics metrics) {

  public ControllerEnv {
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(rng, "rng");
    Objects.requireNonNull(offload, "offload");
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(metrics, "metrics");
  }
}
