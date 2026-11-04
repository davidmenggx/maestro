package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The requests a tick each node serves, as admission and placement take it: the assumed capacity,
 * or less if the node's heartbeats show its searches take longer. A heartbeat reports the searches
 * the node's workers finished and the worker time they took. Heartbeats are pooled until they hold
 * {@link #MIN_SEARCHES}, which makes one sample: the workers over the mean time of its searches.
 * The node's rate is the median of its last {@link #SAMPLES} samples, the higher of two middles, so
 * one sample slowed by a stall, such as a search the operating system paused, does not count.
 *
 * <p>A rate measured at low load can run high, as fewer busy workers then share the node's cores,
 * so the assumed capacity caps it. A new link starts over, since the node may be a new process.
 */
final class Capacity {

  /** Searches one sample needs, so that a few unusual searches cannot swing it. */
  static final int MIN_SEARCHES = 64;

  /** Samples the median is taken over. */
  static final int SAMPLES = 5;

  /** Microseconds in a server tick. */
  static final long TICK_MICROS = 50_000;

  /** One node's pooled heartbeats and latest samples. */
  private static final class Rate {
    private long searches;
    private long micros;

    /** Requests a tick, oldest first. */
    private final Deque<Double> samples = new ArrayDeque<>();

    /** The median of {@link #samples}, or NaN if there are none. */
    private double measured = Double.NaN;
  }

  private final int assumed;

  /** In fleet order. */
  private final Map<NodeId, Rate> rates = new LinkedHashMap<>();

  Capacity(int assumed, List<NodeId> fleet) {
    if (assumed < 1) {
      throw new IllegalArgumentException("a node serves at least one request a tick: " + assumed);
    }
    this.assumed = assumed;
    for (NodeId node : fleet) {
      rates.put(node, new Rate());
    }
  }

  /** Requests a tick the node serves: the assumed capacity, or its measured rate if lower. */
  double of(NodeId node) {
    double measured = rate(node).measured;
    return Double.isNaN(measured) ? assumed : Math.min(assumed, measured);
  }

  /** The node's measured rate in requests a tick, not capped, or NaN before its first sample. */
  double measured(NodeId node) {
    return rate(node).measured;
  }

  /**
   * A heartbeat: {@code workers} threads finished {@code searches} since the last one, taking
   * {@code micros} of worker time.
   */
  void heartbeat(NodeId node, long searches, long micros, int workers) {
    Rate rate = rate(node);
    rate.searches += Math.max(0, searches);
    rate.micros += Math.max(0, micros);
    if (rate.searches < MIN_SEARCHES || rate.micros == 0 || workers < 1) {
      return;
    }
    rate.samples.addLast((double) workers * TICK_MICROS * rate.searches / rate.micros);
    if (rate.samples.size() > SAMPLES) {
      rate.samples.removeFirst();
    }
    rate.searches = 0;
    rate.micros = 0;
    double[] sorted = rate.samples.stream().mapToDouble(Double::doubleValue).toArray();
    Arrays.sort(sorted);
    rate.measured = sorted[sorted.length / 2];
  }

  /** The link is gone, and with it what its heartbeats showed. */
  void linkLost(NodeId node) {
    Rate rate = rate(node);
    rate.searches = 0;
    rate.micros = 0;
    rate.samples.clear();
    rate.measured = Double.NaN;
  }

  private Rate rate(NodeId node) {
    Rate rate = rates.get(node);
    if (rate == null) {
      throw new IllegalStateException(node + " is not in the fleet");
    }
    return rate;
  }
}
