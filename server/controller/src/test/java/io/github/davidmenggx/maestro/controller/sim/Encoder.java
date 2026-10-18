package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.Offload;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs encodes off the controller thread, and runs a failed one again at the next tick boundary, at
 * most {@link #MAX_ATTEMPTS} times in all before giving up. The work captured everything it reads,
 * so a retry builds the same bytes. A world frame keeps its sequence number, because skipping a
 * number would leave a gap for the node.
 */
final class Encoder {

  /**
   * Tries at one encode. A batch of requests given up on goes back to its node's next batch. A
   * world frame given up on leaves a gap no later frame can cross, so its region's stream starts
   * over.
   */
  static final int MAX_ATTEMPTS = 5;

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
