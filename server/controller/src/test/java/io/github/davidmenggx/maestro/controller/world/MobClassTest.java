package io.github.davidmenggx.maestro.controller.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MobClassTest {

  private static final MobClass.Shape ADULT = new MobClass.Shape(0.6f, 1.95f, 0.6f);
  private static final MobClass.Abilities WALKER = new MobClass.Abilities(true, false, true, false);

  @Test
  void profilesWithTheSameContentAreEqualAndHashAlike() {
    MobClass first = new MobClass((short) 3, ADULT, WALKER, malus(-1.0f));
    MobClass second = new MobClass((short) 3, ADULT, WALKER, malus(-1.0f));

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertEquals(first.toString(), second.toString());
    assertTrue(Set.of(first).contains(second));
  }

  @Test
  void theIdAndEveryInputTakePartInEquality() {
    MobClass profile = new MobClass((short) 3, ADULT, WALKER, malus(-1.0f));

    assertNotEquals(profile, new MobClass((short) 4, ADULT, WALKER, malus(-1.0f)));
    assertNotEquals(profile, new MobClass((short) 3, ADULT, WALKER, malus(8.0f)));
    assertNotEquals(
        profile,
        new MobClass((short) 3, new MobClass.Shape(0.3f, 0.975f, 0.6f), WALKER, malus(-1.0f)));
    assertNotEquals(
        profile,
        new MobClass(
            (short) 3, ADULT, new MobClass.Abilities(true, true, true, false), malus(-1.0f)));
  }

  @Test
  void aShapeHoldsEveryLandMobVanillaCanMakeAndNoMore() {
    // The giant at the largest scale, with the highest step height.
    new MobClass.Shape(3.6f * 16, 12.0f * 16, 10.0f);
    new MobClass.Shape(
        MobClass.Shape.MAX_WIDTH, MobClass.Shape.MAX_HEIGHT, MobClass.Shape.MAX_UP_STEP);
    new MobClass.Shape(0.6f, 1.8f, 0.0f);

    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(0.0f, 1.8f, 0.6f));
    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(0.6f, -1.0f, 0.6f));
    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(0.6f, 1.8f, -0.1f));
    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(2000.0f, 1.8f, 0.6f));
    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(0.6f, 300.0f, 0.6f));
    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(0.6f, 1.8f, 20.0f));
    // NaN fails every comparison, so it must not slip past a check written as a rejection.
    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(Float.NaN, 1.8f, 0.6f));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MobClass.Shape(0.6f, Float.POSITIVE_INFINITY, 0.6f));
    assertFalse(MobClass.Shape.fits(0.6f, 1.8f, Float.NaN));
  }

  @Test
  void theDescriptionShowsTheMalusValues() {
    // A description that named the array instance would differ between two runs of one seed.
    assertTrue(
        new MobClass((short) 1, ADULT, WALKER, malus(-1.0f)).toString().contains("-1.0"),
        "the malus values are not described");
  }

  private static float[] malus(float lava) {
    float[] values = new float[PathTypes.count()];
    values[maestro.fbs.PathType.LAVA] = lava;
    return values;
  }
}
