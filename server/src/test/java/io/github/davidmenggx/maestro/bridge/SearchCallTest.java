package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.world.MobClass;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SearchCallTest {

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

  private static double footprint(EntityType<?> type) {
    return MobClass.Shape.footprint(type.getWidth(), type.getHeight());
  }

  /** Whether {@link SearchCall#read} takes a mob of this size, at the default step height. */
  private static boolean fits(float width, float height) {
    return MobClass.Shape.fits(width, height, 0.6f);
  }
}
