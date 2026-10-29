package io.github.davidmenggx.maestro.controller.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import org.junit.jupiter.api.Test;

class ControllerSettingsTest {

  private static final RegionLayout LAYOUT = new RegionLayout(1);

  @Test
  void settingsTakeWholeFleetsAndATraceShareOfZeroToOne() {
    for (double rate : new double[] {0, 0.01, 1}) {
      assertDoesNotThrow(() -> settings(0, 1, rate));
    }
    assertThrows(NullPointerException.class, () -> settings(null));
    assertThrows(IllegalArgumentException.class, () -> settings(-1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> settings(0, 0, 0));
    for (double rate : new double[] {-0.01, 1.01, Double.NaN}) {
      assertThrows(IllegalArgumentException.class, () -> settings(0, 1, rate));
    }
  }

  private static Controller.Settings settings(int spares, int capacity, double rate) {
    return new Controller.Settings(
        LAYOUT, spares, capacity, Dispatch.BATCH, Placement.Policy.DEFAULT, rate);
  }

  private static Controller.Settings settings(RegionLayout layout) {
    return new Controller.Settings(layout, 0, 1, Dispatch.BATCH, Placement.Policy.DEFAULT, 0);
  }
}
