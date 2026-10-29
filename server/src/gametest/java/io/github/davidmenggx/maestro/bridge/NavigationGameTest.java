package io.github.davidmenggx.maestro.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** The navigation hooks, on real mobs in the overworld. */
public final class NavigationGameTest {

  /** Ticks between the navigation's stuck checks, past which a mob that stood still is stuck. */
  private static final int STUCK_CHECK_TICKS = 100;

  @GameTest(maxTicks = 200)
  public void aHeldMobIsNeitherStuckNorWalkedToTheCentreOfItsBlock(GameTestHelper helper) {
    GameTests.fleet(helper);
    GameTests.floor(helper);
    Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(2.2, 1, 2.2));
    PathNavigation navigation = husk.getNavigation();
    Path held = HeldPaths.hold(husk.blockPosition(), helper.absolutePos(new BlockPos(6, 1, 6)));
    navigation.moveTo(held, 1.0);
    // As a mob held mid-walk: the stuck check scales with the speed it last moved at.
    husk.setSpeed(0.3F);
    Vec3 start = husk.position();

    helper
        .startSequence()
        // A hold whose answer is late is renewed by each repeat call, here by hand.
        .thenExecuteFor(STUCK_CHECK_TICKS + 20, () -> HeldPaths.renew(held))
        .thenExecute(
            () -> {
              helper.assertTrue(navigation.getPath() == held, "the hold was dropped");
              helper.assertTrue(!navigation.isStuck(), "a held mob was found stuck");
              helper.assertTrue(!navigation.isDone(), "a held path is done");
              double moved = husk.position().subtract(start).horizontalDistance();
              helper.assertTrue(moved < 0.01, "a held mob moved " + moved + " blocks");
            })
        .thenSucceed();
  }

  @GameTest(maxTicks = 60)
  public void anAttackGoalsSearchMayBeOffloaded(GameTestHelper helper) {
    GameTests.floor(helper);
    Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(1, 1, 1));
    Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(6, 1, 6));
    List<Boolean> marks = new ArrayList<>();
    GameTests.watch(
        helper,
        husk,
        mob -> {
          if (inAttackGoalCanUse()) {
            marks.add(Searches.offloadable());
          }
          return false;
        });
    husk.setTarget(villager);

    helper.succeedWhen(
        () -> {
          helper.assertTrue(!marks.isEmpty(), "the attack goal has not searched");
          helper.assertTrue(
              !marks.contains(false), "the attack goal searched without marking its search");
        });
  }

  @GameTest
  public void onlyAMobInsideTheSquareSearchingInAMoveToIsWanted(GameTestHelper helper) {
    PathOffload offload = GameTests.offload(helper);
    Husk inside = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(2, 1, 2));
    // Never added to the level: the offload reads only its level and where it stands.
    Husk outside = EntityType.HUSK.create(helper.getLevel(), EntitySpawnReason.COMMAND);
    outside.snapTo(4800.5, inside.getY(), 0.5, 0, 0);

    helper.assertTrue(!offload.wants(inside), "a search outside a move to is wanted");
    Searches.enter();
    try {
      helper.assertTrue(offload.wants(inside), "a mob inside the square is not wanted");
      helper.assertTrue(!offload.wants(outside), "a mob outside the square is wanted");
    } finally {
      Searches.leave();
    }
    helper.succeed();
  }

  @GameTest
  public void theFailoversFullSearchFindsWhatTheCallFound(GameTestHelper helper) {
    GameTests.floor(helper);
    // A wall two blocks high across the structure, open at z = 6.
    for (int z = 0; z < GameTests.SIZE; z++) {
      if (z != 6) {
        helper.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 2, z), Blocks.STONE);
      }
    }
    Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(1, 1, 1));
    BlockPos target = helper.absolutePos(new BlockPos(6, 1, 1));
    List<Path> found = new ArrayList<>();
    List<Supplier<Path>> later = new ArrayList<>();
    GameTests.watch(
        helper,
        husk,
        new GameTests.Watch() {
          @Override
          public boolean wants(Mob mob) {
            return Searches.offloadable();
          }

          @Override
          public Path search(Mob mob, SearchCall call, Searches.Vanilla vanilla) {
            found.add(vanilla.search());
            later.add(vanilla.later());
            return found.get(0);
          }
        });

    helper
        .startSequence()
        .thenWaitUntil(() -> landed(helper, husk))
        .thenExecute(
            () -> {
              husk.getNavigation()
                  .moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
              helper.assertValueEqual(found.size(), 1, "calls");
              Path call = found.get(0);
              // Run as the failover runs it: after the call, outside any move to.
              Path full = later.get(0).get();

              helper.assertTrue(call != null && call.canReach(), "the call found no path");
              helper.assertTrue(full != null, "the full search found no path");
              helper.assertTrue(full.sameAs(call), "the full search found another path");
              helper.assertTrue(full.canReach() && target.equals(full.getTarget()), "wrong target");
              // Six nodes would go straight through the wall.
              helper.assertTrue(
                  full.getNodeCount() > 6, "the path does not go around the wall: " + nodes(full));
            })
        .thenSucceed();
  }

  @GameTest
  public void anAnswerIsFollowedTowardItsTargetAtTheSpeedTheMobMoves(GameTestHelper helper) {
    GameTests.floor(helper);
    Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(1, 1, 1));
    PathNavigation navigation = husk.getNavigation();
    BlockPos first = helper.absolutePos(new BlockPos(6, 1, 1));
    BlockPos target = helper.absolutePos(new BlockPos(1, 1, 6));

    helper
        .startSequence()
        .thenWaitUntil(() -> landed(helper, husk))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  navigation.moveTo(first.getX() + 0.5, first.getY(), first.getZ() + 0.5, 1.3),
                  "the mob does not move");
              Path answer = navigation.createPath(target, 0);
              helper.assertTrue(answer != null, "no path to the answer's target");

              new PathOffload.MobNavigator(husk).follow(answer, target);

              helper.assertTrue(navigation.getPath() == answer, "the answer is not followed");
              helper.assertValueEqual(navigation.getTargetPos(), target, "target");
              helper.assertValueEqual(
                  ((PathOffload.NavigationAccess) navigation).maestro$speedModifier(),
                  1.3,
                  "speed");
            })
        .thenSucceed();
  }

  private static String nodes(Path path) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < path.getNodeCount(); i++) {
      Node node = path.getNode(i);
      out.append(" (")
          .append(node.x)
          .append(',')
          .append(node.y)
          .append(',')
          .append(node.z)
          .append(')');
    }
    return out.toString();
  }

  /** A ground navigation searches only for a mob standing on something. */
  private static void landed(GameTestHelper helper, Mob mob) {
    helper.assertTrue(mob.onGround(), "the mob has not landed");
  }

  /** Whether the running search was made by {@code MeleeAttackGoal.canUse}. */
  private static boolean inAttackGoalCanUse() {
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames.anyMatch(
                    frame ->
                        frame.getClassName().equals(MeleeAttackGoal.class.getName())
                            && frame.getMethodName().equals("canUse")));
  }
}
