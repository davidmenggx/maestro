package io.github.davidmenggx.maestro.controller.env;

/**
 * Counters and distributions. Makes no timing calls; every value comes from {@link Clock}.
 * Production forwards to OpenTelemetry.
 *
 * <p>{@code labels} are alternating key and value strings. An array keeps the iteration order
 * fixed.
 */
public interface Metrics {

  void count(String name, long delta, String... labels);

  /** One observation of a distribution, such as ticks late or bytes per frame. */
  void record(String name, double value, String... labels);

  void gauge(String name, long value, String... labels);
}
