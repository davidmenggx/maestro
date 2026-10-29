package io.github.davidmenggx.maestro.controller.env;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SplitMix64Test {

  /** Trace ids and trace sampling are built on it, so its outputs must never change. */
  @Test
  void theOutputsAreSplitMix64s() {
    assertEquals(0xe220a8397b1dcdafL, SplitMix64.nth(0, 1));
    assertEquals(0x6e789e6aa1b965f4L, SplitMix64.nth(0, 2));
    assertEquals(0x06c45d188009454fL, SplitMix64.nth(0, 3));
    assertEquals(SplitMix64.nth(0, 1), SplitMix64.mix(0x9e3779b97f4a7c15L));
  }
}
