package io.github.davidmenggx.maestro.controller.env;

/**
 * Counters, distributions and gauges. {@code labels} are alternating key and value strings, in a
 * fixed order.
 */
public interface Metrics {

  void count(String name, long delta, String... labels);

  /** One observation of a distribution, such as ticks late or bytes per frame. */
  void record(String name, double value, String... labels);

  void gauge(String name, double value, String... labels);
}
