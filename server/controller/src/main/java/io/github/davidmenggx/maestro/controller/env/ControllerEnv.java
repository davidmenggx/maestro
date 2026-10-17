package io.github.davidmenggx.maestro.controller.env;

import java.util.Objects;

/**
 * Every source of nondeterminism the controller is allowed to reach. {@code run} identifies this
 * controller process to the fleet, which keys region streams, profiles and request ids by it, so it
 * must differ between processes and is never 0. It is drawn from a source that does not repeat, not
 * from {@code rng}, whose seed may.
 */
public record ControllerEnv(
    Clock clock, Rng rng, Offload offload, Transport transport, Metrics metrics, long run) {

  public ControllerEnv {
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(rng, "rng");
    Objects.requireNonNull(offload, "offload");
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(metrics, "metrics");
    if (run == 0) {
      throw new IllegalArgumentException("run 0 means no run");
    }
  }
}
