package io.github.davidmenggx.maestro.controller.world;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import maestro.fbs.PathType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The boundary rejects values the wire contract forbids instead of passing them to a node. */
class BoundaryContractTest {

  private static final Pos SOMEWHERE = new Pos(1, 2, 3);
  private static final WorldEvent.Search SEARCH = new WorldEvent.Search(0, 16f, 256, 3);

  /** The number of types `getPathTypeFromState` can return, checked against vanilla. */
  private static final int PER_BLOCK_TYPES = 17;

  @Test
  void onlyPerBlockPathTypesAreCarriedByACell() {
    int matching = 0;
    for (int ordinal = 0; ordinal < PathTypes.count(); ordinal++) {
      if (PathTypes.isPerBlock((byte) ordinal)) {
        matching++;
      }
    }
    assertEquals(PER_BLOCK_TYPES, matching);
  }

  @Test
  void derivedPathTypesAreNotPerBlock() {
    // Each of these depends on the cell below or the surrounding cells.
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
    assertThrows(IllegalArgumentException.class, () -> new WorldEvent.Search(-1, 16f, 256, 3));
    // Each bound is the largest value its wire field holds. A silent cast would wrap it.
    new WorldEvent.Search(
        WorldEvent.Search.MAX_REACH_RANGE, 16f, WorldEvent.Search.MAX_VISITED_NODES, 3);
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.Search(WorldEvent.Search.MAX_REACH_RANGE + 1, 16f, 256, 3));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.Search(0, 16f, WorldEvent.Search.MAX_VISITED_NODES + 1, 3));
    assertThrows(IllegalArgumentException.class, () -> new WorldEvent.Search(0, 0f, 256, 3));
    assertThrows(IllegalArgumentException.class, () -> new WorldEvent.Search(0, Float.NaN, 256, 3));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.Search(0, Float.POSITIVE_INFINITY, 256, 3));
    assertThrows(IllegalArgumentException.class, () -> new WorldEvent.Search(0, 16f, 0, 3));
    assertThrows(IllegalArgumentException.class, () -> new WorldEvent.Search(0, 16f, 256, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.Search(0, 16f, 256, WorldEvent.Search.MAX_FALL_DISTANCE + 1));
  }

  @Test
  void aSearchFitsExactlyWhenItCanBeMadeAndItsReachIsKnown() {
    int[] reaches = {
      -1, 0, WorldEvent.Search.MAX_REACH_RANGE, WorldEvent.Search.MAX_REACH_RANGE + 1
    };
    float[] lengths = {
      Float.NaN,
      Float.NEGATIVE_INFINITY,
      -1f,
      0f,
      Float.MIN_VALUE,
      16f,
      WorldEvent.Search.MAX_PATH_LENGTH,
      Math.nextUp(WorldEvent.Search.MAX_PATH_LENGTH),
      Float.MAX_VALUE,
      Float.POSITIVE_INFINITY
    };
    int[] budgets = {
      0, 1, WorldEvent.Search.MAX_VISITED_NODES, WorldEvent.Search.MAX_VISITED_NODES + 1
    };
    int[] falls = {
      -1, 0, WorldEvent.Search.MAX_FALL_DISTANCE, WorldEvent.Search.MAX_FALL_DISTANCE + 1
    };
    int fitting = 0;
    for (int reach : reaches) {
      for (float length : lengths) {
        for (int budget : budgets) {
          for (int fall : falls) {
            boolean fits = WorldEvent.Search.fits(reach, length, budget, fall);
            Executable make = () -> new WorldEvent.Search(reach, length, budget, fall);
            if (fits) {
              fitting++;
              assertDoesNotThrow(make);
              // Admission derives how far the search reads from the length bound.
              assertDoesNotThrow(() -> SearchReach.blocks(length, 0.6f));
            } else {
              assertThrows(IllegalArgumentException.class, make);
            }
          }
        }
      }
    }
    assertEquals(2 * 3 * 2 * 2, fitting);
  }

  @Test
  void aMobClassCoversEveryPathTypeOrIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> profile(new float[3]));
    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(0f, 1f, 0.5f));
    assertThrows(IllegalArgumentException.class, () -> new MobClass.Shape(0.6f, 1.9f, -1f));

    MobClass accepted = profile(new float[PathTypes.count()]);
    assertEquals(PathTypes.count(), accepted.malus().length);

    // The array is copied on the way in and out, so the profile's own array cannot
    // be modified through either.
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

  @Test
  void floorSpansOneByte() {
    new WorldEvent.BlockChanged(0, SOMEWHERE, (byte) PathType.OPEN, 0);
    new WorldEvent.BlockChanged(0, SOMEWHERE, (byte) PathType.FENCE, Cells.MAX_FLOOR);
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldEvent.BlockChanged(0, SOMEWHERE, (byte) PathType.OPEN, -1));
  }

  @Test
  void everyEventRejectsANegativeTickOrMissingPosition() {
    List<Executable> rejected =
        List.of(
            () -> new WorldEvent.TickBegan(-1),
            () -> new WorldEvent.TickEnded(-1),
            () -> new WorldEvent.BlockChanged(-1, SOMEWHERE, (byte) PathType.OPEN, 0),
            () -> new WorldEvent.ChunkLoaded(-1, 0, 0),
            () -> new WorldEvent.ChunkUnloaded(-1, 0, 0),
            () -> new WorldEvent.MobSpawned(-1, 1, SOMEWHERE),
            () -> new WorldEvent.MobRemoved(-1, 1),
            () -> new WorldEvent.PathCanceled(-1, 1),
            () -> new WorldEvent.PlayerMoved(-1, 1, SOMEWHERE),
            () -> new WorldEvent.PlayerLeft(-1, 1));
    for (Executable event : rejected) {
      assertThrows(IllegalArgumentException.class, event);
    }

    List<Executable> missing =
        List.of(
            () -> new WorldEvent.BlockChanged(0, null, (byte) PathType.OPEN, 0),
            () -> new WorldEvent.MobSpawned(0, 1, null),
            () -> new WorldEvent.PlayerMoved(0, 1, null),
            () -> new WorldEvent.PathRequested(0, 1, (short) 1, SOMEWHERE, null, SEARCH),
            () -> new WorldEvent.PathRequested(0, 1, (short) 1, SOMEWHERE, SOMEWHERE, null));
    for (Executable event : missing) {
      assertThrows(NullPointerException.class, event);
    }
  }

  @Test
  void aPathResultOwnsItsNodes() {
    List<WorldOutput.Node> nodes = new ArrayList<>();
    nodes.add(new WorldOutput.Node(SOMEWHERE, (byte) PathType.WALKABLE));
    WorldOutput.PathResult result = new WorldOutput.PathResult(1, SOMEWHERE, true, nodes, 1f, 0);

    nodes.clear();
    assertEquals(1, result.nodes().size());
    assertThrows(
        UnsupportedOperationException.class,
        () -> result.nodes().add(new WorldOutput.Node(SOMEWHERE, (byte) PathType.OPEN)));
    assertThrows(
        NullPointerException.class,
        () -> new WorldOutput.PathResult(1, null, false, List.of(), 0f, 0));
  }

  @Test
  void anOutOfRangeOrdinalHasAPrintableName() {
    assertEquals("OPEN", PathTypes.name((byte) PathType.OPEN));
    assertEquals("invalid(-1)", PathTypes.name((byte) -1));
    assertEquals("invalid(" + PathTypes.count() + ")", PathTypes.name((byte) PathTypes.count()));
  }

  private static MobClass profile(float[] malus) {
    return new MobClass(
        (short) 1,
        new MobClass.Shape(0.6f, 1.9f, 0.5f),
        new MobClass.Abilities(true, false, false, false),
        malus);
  }
}
