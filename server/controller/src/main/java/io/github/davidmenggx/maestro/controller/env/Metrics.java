package io.github.davidmenggx.maestro.controller.env;

/**
 * Counters, distributions and gauges, in the shapes OpenTelemetry instruments take. Makes no timing
 * calls; every value comes from {@link Clock}.
 *
 * <p>{@code labels} are alternating key and value strings. An array keeps the iteration order
 * fixed.
 */
public interface Metrics {

  void count(String name, long delta, String... labels);

  /** One observation of a distribution, such as ticks late or bytes per frame. */
  void record(String name, double value, String... labels);

  void gauge(String name, double value, String... labels);
}
