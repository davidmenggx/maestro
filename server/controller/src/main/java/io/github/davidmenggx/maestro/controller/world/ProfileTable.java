package io.github.davidmenggx.maestro.controller.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interns {@link MobClass} profiles by content. The first profile with given inputs gets the next
 * id, starting at 1, and every later profile with the same inputs gets the same one. Ids are never
 * reused.
 */
public final class ProfileTable {

  /** Largest id a profile can have, since a request carries it in a signed short. */
  public static final int MAX_ID = Short.MAX_VALUE;

  private record Content(MobClass.Shape shape, MobClass.Abilities abilities, List<Float> malus) {}

  private final Map<Content, MobClass> byContent = new HashMap<>();
  private final List<MobClass> byId = new ArrayList<>();

  /** The profile with these inputs, interned now if this is the first time they are seen. */
  public MobClass intern(MobClass.Shape shape, MobClass.Abilities abilities, float[] malus) {
    List<Float> boxed = new ArrayList<>(malus.length);
    for (float value : malus) {
      boxed.add(value);
    }
    Content content = new Content(shape, abilities, List.copyOf(boxed));
    MobClass known = byContent.get(content);
    if (known != null) {
      return known;
    }
    if (byId.size() == MAX_ID) {
      throw new IllegalStateException("profile ids exhausted at " + MAX_ID);
    }
    MobClass profile = new MobClass((short) (byId.size() + 1), shape, abilities, malus);
    byContent.put(content, profile);
    byId.add(profile);
    return profile;
  }

  /** The profile named by {@code classId}, or null if none has that id. */
  public MobClass get(short classId) {
    int index = classId - 1;
    return index >= 0 && index < byId.size() ? byId.get(index) : null;
  }

  public int size() {
    return byId.size();
  }
}
