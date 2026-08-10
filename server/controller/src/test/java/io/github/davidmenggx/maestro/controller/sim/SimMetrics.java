package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Metrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Records what the controller reports so invariants can assert on it. Backed by sorted maps, so a
 * dump reads the same way every run.
 */
public final class SimMetrics implements Metrics {

  private final Map<String, Long> counters = new TreeMap<>();
  private final Map<String, Long> gauges = new TreeMap<>();
  private final Map<String, List<Double>> distributions = new TreeMap<>();

  @Override
  public void count(String name, long delta, String... labels) {
    counters.merge(key(name, labels), delta, Long::sum);
  }

  @Override
  public void record(String name, double value, String... labels) {
    distributions.computeIfAbsent(key(name, labels), k -> new ArrayList<>()).add(value);
  }

  @Override
  public void gauge(String name, long value, String... labels) {
    gauges.put(key(name, labels), value);
  }

  public long counter(String name, String... labels) {
    return counters.getOrDefault(key(name, labels), 0L);
  }

  /** Nearest-rank percentile. Returns {@code NaN} when nothing was recorded. */
  public double percentile(String name, double p, String... labels) {
    List<Double> values = distributions.get(key(name, labels));
    if (values == null || values.isEmpty()) {
      return Double.NaN;
    }
    List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int rank = (int) Math.ceil(p * sorted.size());
    return sorted.get(Math.clamp(rank - 1, 0, sorted.size() - 1));
  }

  public Map<String, Long> counters() {
    return Map.copyOf(counters);
  }

  private static String key(String name, String... labels) {
    if (labels.length == 0) {
      return name;
    }
    if (labels.length % 2 != 0) {
      throw new IllegalArgumentException("labels must be key/value pairs: " + labels.length);
    }
    StringBuilder key = new StringBuilder(name).append('{');
    for (int i = 0; i < labels.length; i += 2) {
      if (i > 0) {
        key.append(',');
      }
      key.append(labels[i]).append('=').append(labels[i + 1]);
    }
    return key.append('}').toString();
  }
}
