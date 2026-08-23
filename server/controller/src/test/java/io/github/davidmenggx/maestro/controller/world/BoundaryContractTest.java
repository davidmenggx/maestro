package io.github.davidmenggx.maestro.controller.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import maestro.fbs.PathType;
import org.junit.jupiter.api.Test;

/** The boundary rejects values the wire contract forbids, rather than passing them to a node. */
class BoundaryContractTest {

  private static final Pos SOMEWHERE = new Pos(1, 2, 3);

  @Test
  void onlyPerBlockPathTypesAreCarriedByACell() {
    // The seventeen `getPathTypeFromState` can return, and no others.
    assertEquals(17, countPerBlock());
    assertTrue(PathTypes.isPerBlock((byte) PathType.BLOCKED));
    assertTrue(PathTypes.isPerBlock((byte) PathType.WATER));
    assertTrue(PathTypes.isPerBlock((byte) PathType.DOOR_IRON_CLOSED));
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
        () -> new WorldEvent.BlockChanged(0, SOMEWHERE, (byte) PathType.WALKABLE));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.BlockChanged(0, SOMEWHERE, (byte) PathTypes.count()));
    assertThrows(
        IllegalArgumentException.class, () -> new WorldEvent.BlockChanged(0, SOMEWHERE, (byte) -1));
  }

  @Test
  void eventsRejectImpossibleFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.PathRequested(-1, 1, SOMEWHERE, SOMEWHERE));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.MobSpawned(0, 1, (short) -1, SOMEWHERE));
    assertThrows(
        NullPointerException.class, () -> new WorldEvent.PathRequested(0, 1, null, SOMEWHERE));
  }

  @Test
  void aMobClassCoversEveryPathTypeOrIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> profile(new float[3]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MobClass(
                (short) 1,
                0f,
                1f,
                new float[PathTypes.count()],
                0.5f,
                true,
                false,
                false,
                false,
                16));

    MobClass ok = profile(new float[PathTypes.count()]);
    assertEquals(PathTypes.count(), ok.malus().length);

    // The array is copied in, so a later write cannot reach the profile.
    float[] mutable = new float[PathTypes.count()];
    MobClass copied = profile(mutable);
    mutable[0] = 99f;
    assertEquals(0f, copied.malus()[0]);
  }

  @Test
  void aResultCannotPredateItsRequest() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldOutput.PathResult(1, true, List.of(), 1f, 5, 9));
  }

  private static MobClass profile(float[] malus) {
    return new MobClass((short) 1, 0.6f, 1.9f, malus, 0.5f, true, false, false, false, 16);
  }

  private static int countPerBlock() {
    int count = 0;
    for (int ordinal = 0; ordinal < PathTypes.count(); ordinal++) {
      if (PathTypes.isPerBlock((byte) ordinal)) {
        count++;
      }
    }
    return count;
  }
}
