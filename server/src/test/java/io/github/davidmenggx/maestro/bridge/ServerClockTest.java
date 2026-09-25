package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerClockTest {

  @Test
  void ticksAdvanceByOneFromZero() {
    ServerClock clock = new ServerClock();
    assertEquals(0, clock.tick());
    clock.advance();
    clock.advance();
    assertEquals(2, clock.tick());
  }

  @Test
  void nanosNeverRunBackwards() {
    ServerClock clock = new ServerClock();
    long previous = clock.nowNanos();
    for (int i = 0; i < 1000; i++) {
      long now = clock.nowNanos();
      assertTrue(now >= previous);
      previous = now;
    }
  }
}
