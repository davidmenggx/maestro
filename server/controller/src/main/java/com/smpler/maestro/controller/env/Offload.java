package com.smpler.maestro.controller.env;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Work moved off the server main thread, mainly frame serialization. {@code work} may run on any
 * thread and must touch only what it captures; {@code completion} runs back on the controller
 * thread at a tick boundary, which is the only place a result may be published.
 */
public interface Offload {

  /** {@code name} labels the job in metrics and in simulation traces. */
  <T> void submit(String name, Supplier<T> work, Consumer<T> completion);
}
