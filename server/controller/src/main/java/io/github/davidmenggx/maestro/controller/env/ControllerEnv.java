package io.github.davidmenggx.maestro.controller.env;

import java.util.Objects;

/** Every source of nondeterminism the controller is allowed to reach. */
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
