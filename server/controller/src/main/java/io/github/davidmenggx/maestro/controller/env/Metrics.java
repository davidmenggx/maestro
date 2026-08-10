package io.github.davidmenggx.maestro.controller.env;

/**
 * Counters and distributions. Deliberately free of timing calls of its own: every value is derived
 * from {@link Clock}, so a replayed run reports the same numbers. Production forwards to
 * OpenTelemetry; the simulator records the values and asserts on them.
 *
 * <p>{@code labels} are alternating key and value strings, kept as an array so iteration order is
 * fixed.
 */
public interface Metrics {

  void count(String name, long delta, String... labels);

  /** One observation of a distribution, for example ticks late or bytes per frame. */
  void record(String name, double value, String... labels);

  void gauge(String name, long value, String... labels);

  Metrics NOOP =
      new Metrics() {
        @Override
        public void count(String name, long delta, String... labels) {}

        @Override
        public void record(String name, double value, String... labels) {}

        @Override
        public void gauge(String name, long value, String... labels) {}
      };
}
