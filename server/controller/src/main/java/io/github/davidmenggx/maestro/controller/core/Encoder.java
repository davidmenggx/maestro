package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.Offload;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs encodes off the controller thread and retries a failed one at the next tick boundary, up to
 * {@link #MAX_ATTEMPTS} in all. A retry builds the same bytes, and a world frame keeps its sequence
 * so the node sees no gap.
 */
public final class Encoder {

  /**
   * Tries at one encode. A given-up request batch returns to its node's next batch. A given-up
   * world frame leaves an unfillable gap, so its region's stream starts over.
   */
  public static final int MAX_ATTEMPTS = 5;

  private final ControllerEnv env;

  Encoder(ControllerEnv env) {
    this.env = env;
  }

  <T> void encode(
      String job, Offload.Lane lane, Supplier<T> work, Consumer<T> completion, Runnable gaveUp) {
    encode(job, lane, work, completion, gaveUp, 1);
  }

  private <T> void encode(
      String job,
      Offload.Lane lane,
      Supplier<T> work,
      Consumer<T> completion,
      Runnable gaveUp,
      int attempt) {
    env.offload()
        .submit(
            job,
            lane,
            work,
            completion,
            () -> {
              if (attempt >= MAX_ATTEMPTS) {
                env.metrics().count("offload.abandoned", 1, "job", job);
                gaveUp.run();
                return;
              }
              env.metrics().count("offload.retried", 1, "job", job);
              encode(job, lane, work, completion, gaveUp, attempt + 1);
            });
  }
}
