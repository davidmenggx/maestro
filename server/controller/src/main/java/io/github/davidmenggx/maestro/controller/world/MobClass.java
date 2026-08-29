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
public record MobClass(
    short classId,
    float width,
    float height,
    float[] malus,
    float maxUpStep,
    boolean canPassDoors,
    boolean canOpenDoors,
    boolean canFloat,
    boolean canWalkOverFences,
    int maxFollowRange) {

  public MobClass {
    if (classId < 0) {
      throw new IllegalArgumentException("class id must not be negative: " + classId);
    }
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("footprint must be positive: " + width + "x" + height);
    }
    if (maxFollowRange <= 0) {
      throw new IllegalArgumentException("follow range must be positive: " + maxFollowRange);
    }
    Objects.requireNonNull(malus, "malus");
    if (malus.length != PathTypes.count()) {
      throw new IllegalArgumentException(
          "malus must cover all " + PathTypes.count() + " path types, got " + malus.length);
    }
    // Defensive copy: the array is the one mutable part of an otherwise immutable
    // profile. Equality stays reference-based on it, so do not use this as a key.
    malus = malus.clone();
  }
}
