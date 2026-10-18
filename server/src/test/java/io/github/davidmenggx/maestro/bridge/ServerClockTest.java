package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
