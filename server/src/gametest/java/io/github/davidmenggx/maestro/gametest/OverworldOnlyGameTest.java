package io.github.davidmenggx.maestro.gametest;

import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Only the overworld exists, and no portal out of it can be made. */
public final class OverworldOnlyGameTest {

  @GameTest
  public void theServerCreatesNoOtherDimension(GameTestHelper helper) {
    helper.assertValueEqual(
        Set.copyOf(helper.getLevel().getServer().levelKeys()), Set.of(Level.OVERWORLD), "levels");
    helper.succeed();
  }

  @GameTest(maxTicks = 40)
  public void fireInAnObsidianFrameLightsNoPortal(GameTestHelper helper) {
    // A frame four wide and five high, standing across x, open inside two by three.
    for (int x = 0; x < 4; x++) {
      for (int y = 0; y < 5; y++) {
        if (x == 0 || x == 3 || y == 0 || y == 4) {
          helper.setBlock(new BlockPos(1 + x, 1 + y, 3), Blocks.OBSIDIAN);
        }
      }
    }
    BlockPos inside = new BlockPos(2, 2, 3);
    helper.assertTrue(
        PortalShape.findEmptyPortalShape(
                helper.getLevel(), helper.absolutePos(inside), Direction.Axis.X)
            .isEmpty(),
        "the frame makes a portal shape");

    helper.setBlock(inside, Blocks.FIRE);

    helper.runAfterDelay(
        10,
        () -> {
          for (int x = 2; x <= 3; x++) {
            for (int y = 2; y <= 4; y++) {
              helper.assertBlockNotPresent(Blocks.NETHER_PORTAL, new BlockPos(x, y, 3));
            }
          }
          helper.succeed();
        });
  }

  @GameTest
  public void anEyeOfEnderIsNotSetInAnEndPortalFrame(GameTestHelper helper) {
    BlockPos frame = new BlockPos(3, 1, 3);
    helper.setBlock(frame, Blocks.END_PORTAL_FRAME);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    ItemStack eyes = new ItemStack(Items.ENDER_EYE, 2);
    player.setItemInHand(InteractionHand.MAIN_HAND, eyes);
    BlockPos at = helper.absolutePos(frame);

    InteractionResult result =
        eyes.useOn(
            new UseOnContext(
                player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));

    helper.assertValueEqual(result, InteractionResult.PASS, "result");
    helper.assertBlockProperty(frame, EndPortalFrameBlock.HAS_EYE, false);
    helper.assertValueEqual(eyes.getCount(), 2, "eyes held");
    helper.succeed();
  }
}
