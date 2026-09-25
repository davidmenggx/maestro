package io.github.davidmenggx.maestro.controller.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import maestro.fbs.PathType;
import org.junit.jupiter.api.Test;

/** The boundary rejects values the wire contract forbids, rather than passing them to a node. */
class BoundaryContractTest {

  private static final Pos SOMEWHERE = new Pos(1, 2, 3);
  private static final WorldEvent.Search SEARCH = new WorldEvent.Search(0, 16f, 256);

  /** The number of types `getPathTypeFromState` can return, pinned against vanilla. */
  private static final int PER_BLOCK_TYPES = 17;

  @Test
  void onlyPerBlockPathTypesAreCarriedByACell() {
    // The fuzzer draws a cell type by index, so the enumeration and the predicate
    // are two views of one set and have to name the same ordinals.
    Set<Byte> enumerated = new LinkedHashSet<>();
    for (int i = 0; i < PathTypes.perBlockCount(); i++) {
      byte ordinal = PathTypes.perBlock(i);
      assertTrue(PathTypes.isPerBlock(ordinal), PathTypes.name(ordinal));
      assertTrue(enumerated.add(ordinal), "listed twice: " + PathTypes.name(ordinal));
    }

    int matching = 0;
    for (int ordinal = 0; ordinal < PathTypes.count(); ordinal++) {
      if (PathTypes.isPerBlock((byte) ordinal)) {
        matching++;
      }
    }
    assertEquals(PER_BLOCK_TYPES, matching);
    assertEquals(PER_BLOCK_TYPES, enumerated.size());
  }

  @Test
  void derivedPathTypesAreNotPerBlock() {
    // Each of these needs the cell below or the surrounding cells to decide.
    int[] derived = {
      PathType.WALKABLE,
      PathType.WALKABLE_DOOR,
      PathType.ON_TOP_OF_POWDER_SNOW,
      PathType.ON_TOP_OF_TRAPDOOR,
      PathType.WATER_BORDER,
      PathType.UNPASSABLE_RAIL,
      PathType.FIRE_IN_NEIGHBOR,
      PathType.DAMAGING_IN_NEIGHBOR,
      PathType.BREACH,
      PathType.BIG_MOBS_CLOSE_TO_DANGER
    };
    for (int ordinal : derived) {
      assertFalse(PathTypes.isPerBlock((byte) ordinal), PathTypes.name((byte) ordinal));
    }
  }

  @Test
  void aBlockChangeCarryingADerivedTypeIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.BlockChanged(0, SOMEWHERE, (byte) PathType.WALKABLE, 16));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.BlockChanged(0, SOMEWHERE, (byte) PathTypes.count(), 16));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.BlockChanged(0, SOMEWHERE, (byte) -1, 16));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.BlockChanged(0, SOMEWHERE, (byte) PathType.BLOCKED, 256));
  }

  @Test
  void eventsRejectImpossibleFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.PathRequested(-1, 1, (short) 1, SOMEWHERE, SOMEWHERE, SEARCH));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.PathRequested(0, 1, (short) -1, SOMEWHERE, SOMEWHERE, SEARCH));
    assertThrows(
        NullPointerException.class,
        () -> new WorldEvent.PathRequested(0, 1, (short) 1, null, SOMEWHERE, SEARCH));
    assertThrows(IllegalArgumentException.class, () -> new WorldEvent.Search(-1, 16f, 256));
    assertThrows(IllegalArgumentException.class, () -> new WorldEvent.Search(0, 0f, 256));
    assertThrows(IllegalArgumentException.class, () -> new WorldEvent.Search(0, Float.NaN, 256));
    assertThrows(IllegalArgumentException.class, () -> new WorldEvent.Search(0, 16f, 0));
  }

  @Test
  void aMobClassCoversEveryPathTypeOrIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> profile(new float[3]));
    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(0f, 1f, 0.5f));
    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(0.6f, 1.9f, -1f));

    MobClass accepted = profile(new float[PathTypes.count()]);
    assertEquals(PathTypes.count(), accepted.malus().length);

    // The array is copied both ways, so neither the caller's array nor a handed-out
    // one can reach the profile's own.
    float[] mutable = new float[PathTypes.count()];
    MobClass copied = profile(mutable);
    mutable[0] = 99f;
    copied.malus()[1] = 99f;
    assertEquals(0f, copied.malus()[0]);
    assertEquals(0f, copied.malus()[1]);
  }

  @Test
  void aPathNodeCarriesAKnownPathType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldOutput.Node(SOMEWHERE, (byte) PathTypes.count()));
    assertThrows(IllegalArgumentException.class, () -> new WorldOutput.Node(SOMEWHERE, (byte) -1));
  }

  private static MobClass profile(float[] malus) {
    return new MobClass(
        (short) 1,
        new MobClass.Shape(0.6f, 1.9f, 0.5f),
        new MobClass.Abilities(true, false, false, false),
        malus);
  }
}
