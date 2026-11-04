package io.github.davidmenggx.maestro.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class WalkTest {

  private static final int STEPS = 20 * 60 * 10;

  @Test
  void startsAtTheCentre() {
    Walk walk = new Walk(new SplittableRandom(1), 10.5, -3.5, 12);

    assertEquals(10.5, walk.x());
    assertEquals(-3.5, walk.z());
  }

  @Test
  void staysInItsSquareAtWalkingSpeedAndCrossesIt() {
    Walk walk = new Walk(new SplittableRandom(2), 0.5, 0.5, 12);
    double low = 0;
    double high = 0;
    double walked = 0;
    for (int i = 0; i < STEPS; i++) {
      double x = walk.x();
      double z = walk.z();
      walk.step();
      double moved = Math.hypot(walk.x() - x, walk.z() - z);
      assertTrue(moved <= Walk.SPEED + 1e-9, "moved " + moved);
      assertTrue(Math.abs(walk.x() - 0.5) <= 12 && Math.abs(walk.z() - 0.5) <= 12);
      low = Math.min(low, walk.x());
      high = Math.max(high, walk.x());
      walked += moved;
    }

    assertTrue(low < -6 && high > 6, low + " to " + high);
    // Only the step that reaches a point is short.
    assertTrue(walked > 0.95 * STEPS * Walk.SPEED, "walked " + walked);
  }

  @Test
  void theSameSeedWalksTheSameWayAndAnotherDoesNot() {
    assertEquals(route(7), route(7));
    assertNotEquals(route(7), route(8));
  }

  private static List<Double> route(long seed) {
    Walk walk = new Walk(new SplittableRandom(seed), 0, 0, 8);
    List<Double> route = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      walk.step();
      route.add(walk.x());
      route.add(walk.z());
    }
    return route;
  }
}
