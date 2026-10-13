package io.github.davidmenggx.maestro.controller.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Interns {@link MobClass} profiles by content. The first profile with given inputs gets the next
 * id, starting at 1, and every later profile with the same inputs gets the same one. Ids are never
 * reused.
 *
 * <p>A lookup of a known profile allocates nothing, since one is made for every request.
 */
public final class ProfileTable {

  /**
   * Largest id a profile can have. The wire field is unsigned, but the controller holds an id in a
   * signed {@code short}.
   */
  public static final int MAX_ID = Short.MAX_VALUE;

  private static final int INITIAL_SLOTS = 64;

  private final List<MobClass> byId = new ArrayList<>();

  /** Open-addressed with linear probing. A slot holds an id, or 0 if it is empty. */
  private int[] slots = new int[INITIAL_SLOTS];

  /**
   * The profile with these inputs, interned now if this is the first time they are seen. Null if
   * the ids are exhausted, so the caller can leave the search to vanilla. {@code malus} is copied
   * if it is kept.
   */
  public MobClass intern(MobClass.Shape shape, MobClass.Abilities abilities, float[] malus) {
    int hash = hash(shape, abilities, malus);
    int mask = slots.length - 1;
    int slot = hash & mask;
    while (slots[slot] != 0) {
      MobClass known = byId.get(slots[slot] - 1);
      if (known.matches(shape, abilities, malus)) {
        return known;
      }
      slot = (slot + 1) & mask;
    }
    if (byId.size() == MAX_ID) {
      return null;
    }
    MobClass profile = new MobClass((short) (byId.size() + 1), shape, abilities, malus);
    byId.add(profile);
    slots[slot] = profile.classId();
    if (2 * byId.size() > slots.length) {
      grow();
    }
    return profile;
  }

  /** The profile named by {@code classId}, or null if none has that id. */
  public MobClass get(short classId) {
    int index = classId - 1;
    return index >= 0 && index < byId.size() ? byId.get(index) : null;
  }

  private void grow() {
    slots = new int[slots.length * 2];
    int mask = slots.length - 1;
    for (MobClass profile : byId) {
      int slot = hash(profile.shape(), profile.abilities(), profile.malus()) & mask;
      while (slots[slot] != 0) {
        slot = (slot + 1) & mask;
      }
      slots[slot] = profile.classId();
    }
  }

  private static int hash(MobClass.Shape shape, MobClass.Abilities abilities, float[] malus) {
    int hash = 31 * shape.hashCode() + abilities.hashCode();
    hash = 31 * hash + Arrays.hashCode(malus);
    return hash ^ (hash >>> 16);
  }
}
