package io.github.davidmenggx.maestro.obs;

import io.github.davidmenggx.maestro.controller.env.Metrics;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.Meter;
import java.util.HashMap;
import java.util.Map;

/**
 * Production {@link Metrics}: each name is one OpenTelemetry instrument, made when first used. Used
 * on the server thread only.
 */
public final class OtelMetrics implements Metrics {

  private final Meter meter;
  private final Map<String, LongCounter> counters = new HashMap<>();
  private final Map<String, DoubleHistogram> histograms = new HashMap<>();
  private final Map<String, LongGauge> gauges = new HashMap<>();

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
        .computeIfAbsent(name, key -> meter.histogramBuilder(key).build())
        .record(value, attributes(labels));
  }

  @Override
  public void gauge(String name, long value, String... labels) {
    gauges
        .computeIfAbsent(name, key -> meter.gaugeBuilder(key).ofLongs().build())
        .set(value, attributes(labels));
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
