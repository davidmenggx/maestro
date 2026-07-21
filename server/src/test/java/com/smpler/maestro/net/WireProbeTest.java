package com.smpler.maestro.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class WireProbeTest {

  @Test
  void pingRoundTrips() {
    ByteBuffer buf = WireProbe.buildPing(42L, 1_234_567L);
    assertEquals(42L, WireProbe.readSequence(buf));
  }
}
