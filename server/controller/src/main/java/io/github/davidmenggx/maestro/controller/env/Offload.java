package io.github.davidmenggx.maestro.controller.env;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Work moved off the server main thread, such as frame serialization. {@code work} may run on any
 * thread and must touch only what it captures. Exactly one of {@code completion} and {@code
 * failure} then runs, back on the controller thread at a tick boundary, which is the only place a
 * result may be published: {@code completion} with the result, or {@code failure} if the work
 * threw.
 */
public interface Offload {

  /** {@code name} labels the job in metrics and in simulation traces. */
  <T> void submit(String name, Supplier<T> work, Consumer<T> completion, Runnable failure);
}
