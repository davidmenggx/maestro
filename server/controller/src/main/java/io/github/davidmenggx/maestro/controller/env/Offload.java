package io.github.davidmenggx.maestro.controller.env;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Work moved off the server main thread, such as frame serialization. {@code work} may run on any
 * thread and must use only what it captures. Afterwards exactly one of {@code completion} (with the
 * result) or {@code failure} (if the work threw) runs on the controller thread at a tick boundary.
 * That is the only place a result may be published.
 */
public interface Offload {

  /**
   * Which queue a job waits in. Request batches carry the latency budget, so a worker takes them
   * before world frames, which a resend of a whole region can queue by the hundred.
   */
  enum Lane {
    REQUESTS,
    WORLD
  }

  /** {@code name} identifies the job in metrics and simulation traces. */
  <T> void submit(
      String name, Lane lane, Supplier<T> work, Consumer<T> completion, Runnable failure);
}
