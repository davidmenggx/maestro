package io.github.davidmenggx.maestro.controller.world;

import java.util.Arrays;
import java.util.Objects;

/**
 * One land-mob pathfinding profile: the {@code WalkNodeEvaluator} inputs. A profile is interned by
 * content, and {@code classId} names that content, not a mob type.
 *
 * <p>{@code malus} is indexed by {@code PathType} ordinal and covers every ordinal, including those
 * a cell cannot carry, since the node resolves types before applying a malus.
 */
public record MobClass(short classId, Shape shape, Abilities abilities, float[] malus) {

  /**
   * Collision box and step height. Search cost grows with the footprint, so a shape is at most
   * {@link #MAX_FOOTPRINT} cells and a larger mob's search stays on the server. The node refuses a
   * profile past either bound. Step height reaches 10.
   */
  public record Shape(float width, float height, float maxUpStep) {

    /** Largest {@link #footprint}, in cells. */
    public static final int MAX_FOOTPRINT = 512;

    public static final float MAX_UP_STEP = 16.0f;

    public Shape {
      if (!fits(width, height, maxUpStep)) {
        throw new IllegalArgumentException(
            "not a mob shape: " + width + "x" + height + ", step " + maxUpStep);
      }
    }

    /** Whether a shape with these values may be made. False for NaN and infinity. */
    public static boolean fits(float width, float height, float maxUpStep) {
      return width > 0
          && height > 0
          && Float.isFinite(width)
          && Float.isFinite(height)
          && footprint(width, height) <= MAX_FOOTPRINT
          && maxUpStep >= 0
          && maxUpStep <= MAX_UP_STEP;
    }

    /**
     * Cells the evaluator tests at each node, sized as {@code NodeEvaluator.prepare} does. A
     * double, so a huge width cannot overflow.
     */
    public static double footprint(float width, float height) {
      double side = Math.floor(width + 1.0f);
      return side * Math.floor(height + 1.0f) * side;
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

  /** Copied on the way in and out. */
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
