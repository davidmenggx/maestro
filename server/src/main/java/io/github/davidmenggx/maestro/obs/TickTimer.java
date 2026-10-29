package io.github.davidmenggx.maestro.obs;

import io.github.davidmenggx.maestro.controller.env.Metrics;
import java.util.function.LongSupplier;

/** Records how long each server tick took as {@code server.tick.millis}. Server thread only. */
public final class TickTimer {

  private final Metrics metrics;
  private final LongSupplier nanos;

  /** When the tick now running started. */
  private long startedNanos;

  /** {@code nanos} is a monotonic clock in nanoseconds. */
  public TickTimer(Metrics metrics, LongSupplier nanos) {
    this.metrics = metrics;
    this.nanos = nanos;
  }

  public void tickStarted() {
    startedNanos = nanos.getAsLong();
  }

  public void tickEnded() {
    metrics.record("server.tick.millis", (nanos.getAsLong() - startedNanos) / 1e6);
  }
}
