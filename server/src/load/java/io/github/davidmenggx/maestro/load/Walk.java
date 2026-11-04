package io.github.davidmenggx.maestro.load;

import java.util.SplittableRandom;

/**
 * A walk at walking speed around a square: from its centre to a random point in it, then to
 * another, a tick at a time.
 */
final class Walk {

  /** A player's walking speed, in blocks a tick. */
  static final double SPEED = 4.317 / 20;

  private final SplittableRandom random;
  private final double centerX;
  private final double centerZ;
  private final int radius;
  private double x;
  private double z;
  private double toX;
  private double toZ;

  /** A walk within {@code radius} blocks of {@code x, z} on either axis, starting there. */
  Walk(SplittableRandom random, double x, double z, int radius) {
    this.random = random;
    this.centerX = x;
    this.centerZ = z;
    this.radius = radius;
    this.x = x;
    this.z = z;
    pick();
  }

  double x() {
    return x;
  }

  double z() {
    return z;
  }

  /** Moves on a tick. */
  void step() {
    double dx = toX - x;
    double dz = toZ - z;
    double left = Math.hypot(dx, dz);
    if (left <= SPEED) {
      x = toX;
      z = toZ;
      pick();
    } else {
      x += dx / left * SPEED;
      z += dz / left * SPEED;
    }
  }

  private void pick() {
    toX = centerX + (2 * random.nextDouble() - 1) * radius;
    toZ = centerZ + (2 * random.nextDouble() - 1) * radius;
  }
}
