package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    // Each side is the size plus one, rounded down, so a whole size adds a whole cell.
    assertEquals(2 * 2 * 2, SearchCall.footprint(1.0f, 1.0f));
    assertEquals(1 * 1 * 1, SearchCall.footprint(0.99f, 0.5f));
  }

  @Test
  void everyLandMobAtItsDefaultSizeIsOffloaded() {
    // The giant is the largest, at 4 by 13 by 4 cells.
    assertEquals(4 * 13 * 4, footprint(EntityType.GIANT));
    assertTrue(footprint(EntityType.GIANT) <= SearchCall.MAX_FOOTPRINT);
  }

  @Test
  void aMobLargerThanTheBoundStaysVanilla() {
    assertEquals(SearchCall.MAX_FOOTPRINT, SearchCall.footprint(7.0f, 7.0f));
    assertTrue(SearchCall.footprint(7.0f, 8.0f) > SearchCall.MAX_FOOTPRINT);
    // A zombie at the largest scale, and a giant at twice its size.
    assertTrue(SearchCall.footprint(0.6f * 16, 1.95f * 16) > SearchCall.MAX_FOOTPRINT);
    assertTrue(SearchCall.footprint(3.6f * 2, 12.0f * 2) > SearchCall.MAX_FOOTPRINT);
  }

  private static long footprint(EntityType<?> type) {
    return SearchCall.footprint(type.getWidth(), type.getHeight());
  }
}
