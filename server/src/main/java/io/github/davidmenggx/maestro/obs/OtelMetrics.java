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

/**
 * Production {@link Metrics}: each name is one OpenTelemetry instrument, made when first used. Used
 * on the server thread only.
 *
 * <p>A histogram takes OpenTelemetry's default buckets, which start at 0, 5 and 10, unless its name
 * has buckets of its own below. Ticks late are counted one tick per bucket, so the SLO of 3 ticks
 * can be read from them, and sizes double from bucket to bucket.
 */
public final class OtelMetrics implements Metrics {

  /** Bucket boundaries of the histograms whose values the default buckets would lump together. */
  static final Map<String, List<Double>> BUCKETS =
      Map.of(
          "path.latency.ticks", steps(0, 10),
          "frame.bytes", doublings(64, 1 << 22),
          "node.queue.reported", doublings(1, 1 << 16));

  private final Meter meter;
  private final Map<String, LongCounter> counters = new HashMap<>();
  private final Map<String, DoubleHistogram> histograms = new HashMap<>();
  private final Map<String, DoubleGauge> gauges = new HashMap<>();

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
    gauges
        .computeIfAbsent(name, key -> meter.gaugeBuilder(key).build())
        .set(value, attributes(labels));
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

  private static Attributes attributes(String... labels) {
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
