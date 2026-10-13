package io.github.davidmenggx.maestro.controller.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ProfileTableTest {

  private static final MobClass.Shape ADULT = new MobClass.Shape(0.6f, 1.95f, 0.6f);
  private static final MobClass.Shape BABY = new MobClass.Shape(0.3f, 0.975f, 0.6f);
  private static final MobClass.Abilities WALKER = new MobClass.Abilities(true, false, true, false);

  @Test
  void theSameInputsGetTheSameId() {
    ProfileTable table = new ProfileTable();
    MobClass first = table.intern(ADULT, WALKER, malus());
    MobClass again = table.intern(ADULT, WALKER, malus());

    assertSame(first, again);
    assertEquals(1, first.classId());
  }

  @Test
  void anyDifferentInputGetsTheNextId() {
    ProfileTable table = new ProfileTable();
    float[] cautious = malus();
    cautious[maestro.fbs.PathType.WATER] = 8.0f;

    assertEquals(1, table.intern(ADULT, WALKER, malus()).classId());
    assertEquals(2, table.intern(BABY, WALKER, malus()).classId());
    assertEquals(
        3, table.intern(ADULT, new MobClass.Abilities(true, true, true, false), malus()).classId());
    assertEquals(4, table.intern(ADULT, WALKER, cautious).classId());
  }

  @Test
  void manyProfilesKeepTheirIdsAcrossGrowth() {
    ProfileTable table = new ProfileTable();
    MobClass[] profiles = new MobClass[500];
    for (int i = 0; i < profiles.length; i++) {
      float[] values = malus();
      values[maestro.fbs.PathType.WATER] = i;
      profiles[i] = table.intern(ADULT, WALKER, values);
      assertEquals(i + 1, profiles[i].classId());
    }
    for (int i = 0; i < profiles.length; i++) {
      float[] values = malus();
      values[maestro.fbs.PathType.WATER] = i;
      assertSame(profiles[i], table.intern(ADULT, WALKER, values));
    }
  }

  @Test
  void exhaustedIdsGiveNoProfileButKnownOnesStillResolve() {
    ProfileTable table = new ProfileTable();
    MobClass first = null;
    for (int i = 0; i < ProfileTable.MAX_ID; i++) {
      float[] values = malus();
      values[maestro.fbs.PathType.WATER] = i;
      MobClass profile = table.intern(ADULT, WALKER, values);
      first = first == null ? profile : first;
    }
    float[] extra = malus();
    extra[maestro.fbs.PathType.WATER] = -5.0f;
    assertNull(table.intern(ADULT, WALKER, extra));

    float[] again = malus();
    again[maestro.fbs.PathType.WATER] = 0;
    assertSame(first, table.intern(ADULT, WALKER, again));
  }

  @Test
  void aProfileIsLookedUpByItsId() {
    ProfileTable table = new ProfileTable();
    MobClass adult = table.intern(ADULT, WALKER, malus());
    MobClass baby = table.intern(BABY, WALKER, malus());

    assertSame(adult, table.get((short) 1));
    assertSame(baby, table.get((short) 2));
    assertNull(table.get((short) 0));
    assertNull(table.get((short) 3));
    assertNull(table.get((short) -1));
  }

  @Test
  void changingTheCallersArrayLaterChangesNothing() {
    ProfileTable table = new ProfileTable();
    float[] values = malus();
    MobClass profile = table.intern(ADULT, WALKER, values);
    values[maestro.fbs.PathType.LAVA] = 0.0f;

    assertEquals(-1.0f, profile.malus()[maestro.fbs.PathType.LAVA]);
    assertSame(profile, table.intern(ADULT, WALKER, malus()));
  }

  private static float[] malus() {
    float[] values = new float[PathTypes.count()];
    values[maestro.fbs.PathType.LAVA] = -1.0f;
    return values;
  }
}
