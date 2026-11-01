package io.github.davidmenggx.maestro.obs;

import io.github.davidmenggx.maestro.controller.env.Metrics;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production {@link Metrics}: each name is one OpenTelemetry instrument, made on first use. Called
 * on the server thread only.
 *
 * <p>Histograms use the default buckets unless their name has its own below. Ticks late get one
 * bucket per tick, so the 3-tick SLO can be read off; sizes and node times double per bucket; tick
 * times are finest around the 50 ms budget.
 *
 * <p>A gauge named in {@link #PEAKS} exports the highest value it held since the last export, so a
 * spike shorter than the export interval still shows. Each collection starts the next interval, so
 * this assumes one metric reader: a second would split the peaks between them.
 */
public final class OtelMetrics implements Metrics {

  /** Bucket boundaries of the histograms whose values the default buckets would lump together. */
  static final Map<String, List<Double>> BUCKETS =
      Map.of(
          "path.latency.ticks", steps(0, 10),
          "frame.bytes", doublings(64, 1 << 22),
          "node.queue.reported", doublings(1, 1 << 16),
          "node.wait.micros", doublings(16, 1 << 20),
          "node.search.micros", doublings(16, 1 << 20),
          "server.maestro.micros", doublings(16, 1 << 20),
          "offload.job.micros", doublings(16, 1 << 20),
          "server.tick.millis",
              List.of(
                  1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 80.0, 100.0, 200.0, 500.0));

  /** Gauges whose short spikes matter, which one reading per export would miss. */
  static final Set<String> PEAKS = Set.of("node.phi", "world.lag");

  /** A gauge's value now and the highest it held since its last export. */
  private static final class Peak {

    private double now;
    private double highest;

    Peak(double value) {
      now = value;
      highest = value;
    }

    synchronized void set(double value) {
      now = value;
      highest = Math.max(highest, value);
    }

    /** The highest value since the last call. The next interval starts at the value now. */
    synchronized double take() {
      double taken = highest;
      highest = now;
      return taken;
    }
  }

  private final Meter meter;
  private final Map<String, LongCounter> counters = new HashMap<>();
  private final Map<String, DoubleHistogram> histograms = new HashMap<>();
  private final Map<String, DoubleGauge> gauges = new HashMap<>();

  /** By name, then labels. The inner maps are read by the exporting thread. */
  private final Map<String, Map<Attributes, Peak>> peaks = new HashMap<>();

  public OtelMetrics() {
    this(GlobalOpenTelemetry.getMeter(Telemetry.SERVICE_NAME));
  }

  OtelMetrics(Meter meter) {
    this.meter = meter;
  }

  @Override
  public void count(String name, long delta, String... labels) {
    counters
        .computeIfAbsent(name, key -> meter.counterBuilder(key).build())
        .add(delta, attributes(labels));
  }

  @Override
  public void record(String name, double value, String... labels) {
    histograms
        .computeIfAbsent(
            name,
            key -> {
              DoubleHistogramBuilder builder = meter.histogramBuilder(key);
              List<Double> buckets = BUCKETS.get(key);
              return buckets == null
                  ? builder.build()
                  : builder.setExplicitBucketBoundariesAdvice(buckets).build();
            })
        .record(value, attributes(labels));
  }

  @Override
  public void gauge(String name, double value, String... labels) {
    if (PEAKS.contains(name)) {
      peaks
          .computeIfAbsent(name, this::peakGauge)
          .computeIfAbsent(attributes(labels), key -> new Peak(value))
          .set(value);
      return;
    }
    gauges
        .computeIfAbsent(name, key -> meter.gaugeBuilder(key).build())
        .set(value, attributes(labels));
  }

  /** Registers {@code name} as an asynchronous gauge reading the peaks it returns. */
  private Map<Attributes, Peak> peakGauge(String name) {
    Map<Attributes, Peak> byLabels = new ConcurrentHashMap<>();
    meter
        .gaugeBuilder(name)
        .buildWithCallback(
            measurement ->
                byLabels.forEach((labels, peak) -> measurement.record(peak.take(), labels)));
    return byLabels;
  }

  /** {@code from}, {@code from + 1}, ..., {@code to}. */
  private static List<Double> steps(int from, int to) {
    List<Double> bounds = new ArrayList<>();
    for (int value = from; value <= to; value++) {
      bounds.add((double) value);
    }
    return List.copyOf(bounds);
  }

  /** {@code from}, {@code 2 * from}, ..., up to {@code to}. */
  private static List<Double> doublings(int from, int to) {
    List<Double> bounds = new ArrayList<>();
    for (long value = from; value <= to; value *= 2) {
      bounds.add((double) value);
    }
    return List.copyOf(bounds);
  }

  static Attributes attributes(String... labels) {
    if (labels.length % 2 != 0) {
      throw new IllegalArgumentException("labels come in key and value pairs");
    }
    if (labels.length == 0) {
      return Attributes.empty();
    }
    AttributesBuilder builder = Attributes.builder();
    for (int i = 0; i < labels.length; i += 2) {
      builder.put(labels[i], labels[i + 1]);
    }
    return builder.build();
  }
}
