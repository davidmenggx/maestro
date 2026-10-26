package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SearchCallTest {

  private static final Node START = new Node(3, 64, -2);

  private static final BlockPos TARGET = new BlockPos(9, 65, 4);

  private static final float MAX_PATH_LENGTH = 35.0f;

  private static final int REACH_RANGE = 1;

  private static final int BUDGET = 560;

  /** The overworld's floor. */
  private static final int MIN_Y = -64;

  private final ProfileTable profiles = new ProfileTable();

  private final float[] scratch = new float[PathTypes.count()];

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @Test
  void theFootprintIsTheBoxTheEvaluatorTestsAtEachNode() {
    assertEquals(1 * 2 * 1, footprint(EntityType.ZOMBIE));
    assertEquals(2 * 3 * 2, footprint(EntityType.IRON_GOLEM));
    assertEquals(2 * 3 * 2, footprint(EntityType.RAVAGER));
  }

  @Test
  void everyLandMobAtItsDefaultSizeIsOffloaded() {
    // The giant is the largest, at 4 by 13 by 4 cells.
    assertEquals(4 * 13 * 4, footprint(EntityType.GIANT));
    assertTrue(fits(EntityType.GIANT.getWidth(), EntityType.GIANT.getHeight()));
  }

  @Test
  void aMobLargerThanTheBoundStaysVanilla() {
    // A zombie at the largest scale, and a giant at twice its size.
    assertFalse(fits(EntityType.ZOMBIE.getWidth() * 16, EntityType.ZOMBIE.getHeight() * 16));
    assertFalse(fits(EntityType.GIANT.getWidth() * 2, EntityType.GIANT.getHeight() * 2));
  }

  @Test
  void theCallIsReadFromTheMobItsEvaluatorAndTheSearchInputs() {
    Zombie zombie = new Zombie(EntityType.ZOMBIE, null);
    zombie.setPathfindingMalus(PathType.WATER, 4.0f);
    WalkNodeEvaluator evaluator = evaluator(true);
    evaluator.setCanOpenDoors(true);
    BlockPos.MutableBlockPos target = TARGET.mutable();

    SearchCall call = read(zombie, evaluator, START, target);

    assertNotNull(call);
    MobClass profile = call.profile();
    assertEquals(new MobClass.Shape(0.6f, 1.95f, 0.6f), profile.shape());
    assertEquals(new MobClass.Abilities(true, true, true, false), profile.abilities());
    assertEquals(4.0f, profile.malus()[PathType.WATER.ordinal()]);
    assertEquals(PathType.LAVA.getMalus(), profile.malus()[PathType.LAVA.ordinal()]);
    assertEquals(new BlockPos(3, 64, -2), call.start());
    assertEquals(TARGET, call.target());
    assertNotSame(target, call.target());
    assertEquals(
        new WorldEvent.Search(REACH_RANGE, MAX_PATH_LENGTH, BUDGET, zombie.getMaxFallDistance()),
        call.search());
    assertFalse(call.hunting());
  }

  @Test
  void aMobWithATargetIsHuntingAndItsFallDistanceIsReadPerCall() {
    Zombie prey = new Zombie(EntityType.ZOMBIE, null);
    Mob hunter =
        new Zombie(EntityType.ZOMBIE, null) {
          @Override
          public LivingEntity getTarget() {
            return prey;
          }

          // Vanilla's reads the level's difficulty when the mob has a target.
          @Override
          public int getMaxFallDistance() {
            return 9;
          }
        };

    SearchCall call = read(hunter, evaluator(true), START, TARGET);

    assertTrue(call.hunting());
    assertEquals(9, call.search().maxFallDistance());
  }

  @Test
  void mobsWithTheSameInputsShareAProfileAndAChangedMalusMakesAnother() {
    Zombie first = new Zombie(EntityType.ZOMBIE, null);
    Zombie second = new Zombie(EntityType.ZOMBIE, null);

    MobClass profile = read(first, evaluator(true), START, TARGET).profile();
    assertSame(profile, read(second, evaluator(true), START, TARGET).profile());

    second.setPathfindingMalus(PathType.WATER, 0.0f);
    MobClass changed = read(second, evaluator(true), START, TARGET).profile();
    assertNotSame(profile, changed);
    assertEquals(PathType.WATER.getMalus(), profile.malus()[PathType.WATER.ordinal()]);
  }

  @Test
  void aSearchTheWireCannotCarryStaysVanilla() {
    Zombie zombie = new Zombie(EntityType.ZOMBIE, null);
    WalkNodeEvaluator evaluator = evaluator(true);

    assertNull(read(zombie, evaluator, WorldEvent.Search.MAX_REACH_RANGE + 1, BUDGET));
    assertNull(read(zombie, evaluator, -1, BUDGET));
    assertNull(read(zombie, evaluator, REACH_RANGE, 0));
    assertNull(read(zombie, evaluator, REACH_RANGE, WorldEvent.Search.MAX_VISITED_NODES + 1));
    assertNotNull(read(zombie, evaluator, REACH_RANGE, WorldEvent.Search.MAX_VISITED_NODES));
  }

  @Test
  void aMobOutsideTheShapeBoundStaysVanilla() {
    // Past the step height a profile can carry. The attribute stops at 10.
    Zombie zombie =
        new Zombie(EntityType.ZOMBIE, null) {
          @Override
          public float maxUpStep() {
            return MobClass.Shape.MAX_UP_STEP + 1.0f;
          }
        };

    assertNull(read(zombie, evaluator(true), START, TARGET));
  }

  @Test
  void aSearchWhoseNodesMayShareVanillasHashStaysVanilla() {
    Zombie zombie = new Zombie(EntityType.ZOMBIE, null);
    Node high = new Node(0, 200, 0);
    BlockPos ahead = new BlockPos(6, 200, 0);

    // A mob that cannot float sinks through water to the floor, 256 or more below the top it
    // can climb to.
    assertNull(read(zombie, evaluator(false), high, ahead));
    assertNotNull(read(zombie, evaluator(true), high, ahead));
    assertNotNull(
        SearchCall.read(
            zombie,
            evaluator(false),
            high,
            ahead,
            MAX_PATH_LENGTH,
            REACH_RANGE,
            BUDGET,
            0,
            profiles,
            scratch));
  }

  @Test
  void aMobWhoseProfileCannotBeNamedStaysVanilla() {
    float[] malus = new float[PathTypes.count()];
    MobClass.Shape shape = new MobClass.Shape(1.0f, 1.0f, 0.5f);
    MobClass.Abilities abilities = new MobClass.Abilities(false, false, false, false);
    for (int i = 0; i < ProfileTable.MAX_ID; i++) {
      malus[0] = i;
      assertNotNull(profiles.intern(shape, abilities, malus));
    }

    assertNull(read(new Zombie(EntityType.ZOMBIE, null), evaluator(true), START, TARGET));
  }

  private SearchCall read(Mob mob, WalkNodeEvaluator evaluator, Node start, BlockPos target) {
    return SearchCall.read(
        mob,
        evaluator,
        start,
        target,
        MAX_PATH_LENGTH,
        REACH_RANGE,
        BUDGET,
        MIN_Y,
        profiles,
        scratch);
  }

  private SearchCall read(Mob mob, WalkNodeEvaluator evaluator, int reachRange, int budget) {
    return SearchCall.read(
        mob,
        evaluator,
        START,
        TARGET,
        MAX_PATH_LENGTH,
        reachRange,
        budget,
        MIN_Y,
        profiles,
        scratch);
  }

  /** An evaluator that passes doors, and floats if {@code canFloat}. */
  private static WalkNodeEvaluator evaluator(boolean canFloat) {
    WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
    evaluator.setCanPassDoors(true);
    evaluator.setCanFloat(canFloat);
    return evaluator;
  }

  private static double footprint(EntityType<?> type) {
    return MobClass.Shape.footprint(type.getWidth(), type.getHeight());
  }

  /** Whether {@link SearchCall#read} takes a mob of this size, at the default step height. */
  private static boolean fits(float width, float height) {
    return MobClass.Shape.fits(width, height, 0.6f);
  }
}
