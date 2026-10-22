package io.github.davidmenggx.maestro.controller.env;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Work moved off the main thread, such as frame serialization. {@code work} may run on any thread
 * and must use only what it captures. Then exactly one of {@code completion} or {@code failure}
 * runs on the controller thread at a tick boundary.
 */
public interface Offload {

  /**
   * Which queue a job waits in. Workers take request batches before world frames, since requests
   * carry the latency budget.
   */
  enum Lane {
    REQUESTS,
    WORLD
  }

  /** {@code name} identifies the job in metrics and simulation traces. */
  <T> void submit(
      String name, Lane lane, Supplier<T> work, Consumer<T> completion, Runnable failure);
}
