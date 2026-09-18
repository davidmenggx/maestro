package io.github.davidmenggx.maestro.controller.world;

import java.util.Objects;

/**
 * One land-mob pathfinding profile, the {@code WalkNodeEvaluator} input set. Registered once per
 * mob type and referenced by {@code classId} in every request, so the count is bounded by the
 * number of mob types rather than the number of mobs.
 *
 * <p>{@code malus} is indexed by {@code PathType} ordinal and covers every ordinal, including the
 * ones a cell can never carry, because the node resolves those before charging a malus.
 */
public record MobClass(short classId, Shape shape, Abilities abilities, float[] malus) {

  /** Collision box, and the height the mob clears without jumping. */
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

  /** The evaluator toggles that decide which cells a mob may enter at all. */
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
   * Copied on the way out as well as in, so the array cannot be written through. Equality stays
   * reference-based on it, so do not use a profile as a map key.
   */
  @Override
  public float[] malus() {
    return malus.clone();
  }
}
