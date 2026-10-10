package io.github.davidmenggx.maestro.controller.world;

import java.util.Objects;

/**
 * One land-mob pathfinding profile: the {@code WalkNodeEvaluator} inputs. These change at runtime
 * per mob, so a profile is interned by content, and {@code classId} names that content, not a mob
 * type. A request names the profile its mob has at that moment.
 *
 * <p>{@code malus} is indexed by {@code PathType} ordinal and covers every ordinal, including those
 * a cell cannot carry, because the node resolves those types before applying a malus.
 */
public record MobClass(short classId, Shape shape, Abilities abilities, float[] malus) {

  /** Collision box, and the height the mob can step up without jumping. */
  public record Shape(float width, float height, float maxUpStep) {

    public Shape {
      if (width <= 0 || height <= 0) {
        throw new IllegalArgumentException("footprint must be positive: " + width + "x" + height);
      }
      if (maxUpStep < 0) {
        throw new IllegalArgumentException("step height must not be negative: " + maxUpStep);
      }
    }
  }

  /** Evaluator flags that decide which cells a mob may enter. */
  public record Abilities(
      boolean canPassDoors, boolean canOpenDoors, boolean canFloat, boolean canWalkOverFences) {}

  public MobClass {
    if (classId < 0) {
      throw new IllegalArgumentException("class id must not be negative: " + classId);
    }
    Objects.requireNonNull(shape, "shape");
    Objects.requireNonNull(abilities, "abilities");
    Objects.requireNonNull(malus, "malus");
    if (malus.length != PathTypes.count()) {
      throw new IllegalArgumentException(
          "malus must cover all " + PathTypes.count() + " path types, got " + malus.length);
    }
    malus = malus.clone();
  }

  /**
   * Copied on the way in and out, so the array cannot be modified through the record. Equality
   * compares the array by reference, so do not use a profile as a map key.
   */
  @Override
  public float[] malus() {
    return malus.clone();
  }
}
