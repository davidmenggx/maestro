package io.github.davidmenggx.maestro.controller.env;

import java.util.Objects;

/**
 * Every source of nondeterminism the controller may reach. {@code run} identifies this process to
 * the fleet, so it must differ between processes and is never 0. It does not come from {@code rng},
 * whose seed may repeat.
 */
public record ControllerEnv(
    Clock clock,
    Rng rng,
    Offload offload,
    Transport transport,
    Metrics metrics,
    Tracing tracing,
    long run) {

  public ControllerEnv {
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(rng, "rng");
    Objects.requireNonNull(offload, "offload");
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(tracing, "tracing");
    if (run == 0) {
      throw new IllegalArgumentException("run 0 means no run");
    }
  }
}
