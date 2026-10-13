package io.github.davidmenggx.maestro.controller.world;

import java.util.Arrays;
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

  /**
   * Collision box, and the height the mob can step up without jumping. The bounds hold every land
   * mob vanilla can make: the giant is 3.6 by 12 at scale 1, the scale attribute reaches 16, and
   * the step height attribute reaches 10. The node refuses a profile outside them, since its search
   * cost grows with the footprint.
   */
  public record Shape(float width, float height, float maxUpStep) {

    public static final float MAX_WIDTH = 64.0f;
    public static final float MAX_HEIGHT = 256.0f;
    public static final float MAX_UP_STEP = 16.0f;

    public Shape {
      if (!fits(width, height, maxUpStep)) {
        throw new IllegalArgumentException(
            "not a mob shape: " + width + "x" + height + ", step " + maxUpStep);
      }
    }

    /** Whether a shape with these values may be made. False for NaN. */
    public static boolean fits(float width, float height, float maxUpStep) {
      return width > 0
          && width <= MAX_WIDTH
          && height > 0
          && height <= MAX_HEIGHT
          && maxUpStep >= 0
          && maxUpStep <= MAX_UP_STEP;
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

  /** Whether the profile has exactly these inputs, whatever its id. Does not allocate. */
  public boolean matches(Shape otherShape, Abilities otherAbilities, float[] otherMalus) {
    return shape.equals(otherShape)
        && abilities.equals(otherAbilities)
        && Arrays.equals(malus, otherMalus);
  }

  /** Copied on the way in and out, so the array cannot be modified through the record. */
  @Override
  public float[] malus() {
    return malus.clone();
  }

  /** Compares {@code malus} by content, as the other components are. */
  @Override
  public boolean equals(Object other) {
    return other instanceof MobClass profile
        && classId == profile.classId
        && matches(profile.shape, profile.abilities, profile.malus);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hash(classId, shape, abilities) + Arrays.hashCode(malus);
  }

  @Override
  public String toString() {
    return "MobClass[classId="
        + classId
        + ", shape="
        + shape
        + ", abilities="
        + abilities
        + ", malus="
        + Arrays.toString(malus)
        + "]";
  }
}
