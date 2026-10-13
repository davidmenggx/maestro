package io.github.davidmenggx.maestro.controller.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
