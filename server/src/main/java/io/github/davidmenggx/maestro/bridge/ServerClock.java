package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.env.Clock;

/**
 * Production {@link Clock}. The mod owns the tick counter, advanced once per server tick, so it
 * survives a world reload. Nanoseconds come from the JVM's monotonic timer.
 */
public final class ServerClock implements Clock {

  private long tick;

  @Override
  public long tick() {
    return tick;
  }

  @Override
  public long nowNanos() {
    return System.nanoTime();
  }

  /** Called once per server tick, from the server thread. */
  public void advance() {
    tick++;
  }
}
