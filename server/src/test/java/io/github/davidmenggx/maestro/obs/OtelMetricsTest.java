package io.github.davidmenggx.maestro.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OtelMetricsTest {

  private final Reader reader = new Reader();

  private final SdkMeterProvider provider =
      SdkMeterProvider.builder().registerMetricReader(reader).build();

  private final OtelMetrics metrics = new OtelMetrics(provider.get("test"));

  @AfterEach
  void close() {
    provider.close();
  }

  @Test
  void ticksLateAreCountedOneTickPerBucketSoTheSloCanBeRead() {
    for (double ticks : new double[] {0, 1, 1, 2, 3, 3, 3, 10, 12}) {
      metrics.record("path.latency.ticks", ticks);
    }

    HistogramPointData point = histogram("path.latency.ticks");
    assertEquals(OtelMetrics.BUCKETS.get("path.latency.ticks"), point.getBoundaries());
    // Buckets hold (-inf, 0], (0, 1], (1, 2], ..., (9, 10], then the rest.
    assertEquals(List.of(1L, 2L, 1L, 3L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 1L), point.getCounts());
  }

  @Test
  void sizesAreBucketedByDoubling() {
    metrics.record("frame.bytes", 100);
    metrics.record("frame.bytes", 40_000);

    HistogramPointData point = histogram("frame.bytes");
    assertEquals(64.0, point.getBoundaries().get(0));
    assertEquals(128.0, point.getBoundaries().get(1));
    assertEquals((double) (1 << 22), point.getBoundaries().getLast());
  }

  @Test
  void aHistogramWithoutBucketsOfItsOwnTakesTheDefaults() {
    metrics.record("something.else", 7);

    assertEquals(5.0, histogram("something.else").getBoundaries().get(1));
  }

  @Test
  void aGaugeKeepsItsLastValuePerLabelAndTakesFractions() {
    metrics.gauge("node.capacity", 8.25, "node", "node-0");
    metrics.gauge("node.capacity", 0.5, "node", "node-0");
    metrics.gauge("node.capacity", 1.0, "node", "node-1");

    assertEquals(0.5, gauge("node.capacity", "node-0"));
    assertEquals(2, metric("node.capacity").getDoubleGaugeData().getPoints().size());
  }

  @Test
  void aPeakGaugeExportsTheHighestValueSinceTheLastExport() {
    metrics.gauge("node.phi", 0.5, "node", "node-0");
    metrics.gauge("node.phi", 9.0, "node", "node-0");
    metrics.gauge("node.phi", 1.0, "node", "node-0");
    metrics.gauge("node.phi", 2.0, "node", "node-1");

    assertEquals(9.0, gauge("node.phi", "node-0"));
    // The next interval starts at the value held now.
    assertEquals(1.0, gauge("node.phi", "node-0"));
    metrics.gauge("node.phi", 3.0, "node", "node-0");
    metrics.gauge("node.phi", 0.25, "node", "node-0");
    assertEquals(3.0, gauge("node.phi", "node-0"));
    assertEquals(2.0, gauge("node.phi", "node-1"));
  }

  @Test
  void aCounterSumsItsDeltasPerLabel() {
    metrics.count("path.offload", 1, "outcome", "APPLIED");
    metrics.count("path.offload", 2, "outcome", "APPLIED");
    metrics.count("path.offload", 1, "outcome", "SHED");
    metrics.count("path.answered", 5);

    assertEquals(3, sum("path.offload", "outcome", "APPLIED"));
    assertEquals(1, sum("path.offload", "outcome", "SHED"));
    assertEquals(
        5, metric("path.answered").getLongSumData().getPoints().iterator().next().getValue());
  }

  @Test
  void labelsMustComeInKeyAndValuePairs() {
    assertThrows(IllegalArgumentException.class, () -> metrics.count("path.offload", 1, "outcome"));
    assertThrows(IllegalArgumentException.class, () -> metrics.gauge("node.phi", 1.0, "node"));
  }

  /** The gauge's value for one node, from a fresh collection. */
  private double gauge(String name, String node) {
    return metric(name).getDoubleGaugeData().getPoints().stream()
        .filter(point -> node.equals(point.getAttributes().get(AttributeKey.stringKey("node"))))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private long sum(String name, String key, String value) {
    return metric(name).getLongSumData().getPoints().stream()
        .filter(point -> value.equals(point.getAttributes().get(AttributeKey.stringKey(key))))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private HistogramPointData histogram(String name) {
    return metric(name).getHistogramData().getPoints().iterator().next();
  }

  private MetricData metric(String name) {
    return reader.collect().stream()
        .filter(data -> data.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  /** Collects on demand. */
  private static final class Reader implements MetricReader {
    private CollectionRegistration registration = CollectionRegistration.noop();

    @Override
    public void register(CollectionRegistration registration) {
      this.registration = registration;
    }

    List<MetricData> collect() {
      return List.copyOf(registration.collectAllMetrics());
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType type) {
      return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public CompletableResultCode forceFlush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
