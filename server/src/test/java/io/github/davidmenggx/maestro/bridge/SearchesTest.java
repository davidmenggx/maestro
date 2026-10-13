package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SearchesTest {

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @Test
  void onlyVanillasOwnSearchToOneTargetIsTakenOver() {
    WalkNodeEvaluator walk = new WalkNodeEvaluator();
    assertTrue(Searches.intercepted(new PathFinder(walk, 1), walk, 1));
    assertFalse(Searches.intercepted(new PathFinder(walk, 1), walk, 2), "several targets");
  }

  @Test
  void aSubclassChangesTheSearchSoItStaysVanilla() {
    // As the Warden's path finder and the Creaking's evaluator do.
    WalkNodeEvaluator walk = new WalkNodeEvaluator();
    PathFinder ownFinder = new PathFinder(walk, 1) {};
    WalkNodeEvaluator ownEvaluator = new WalkNodeEvaluator() {};
    assertFalse(Searches.intercepted(ownFinder, walk, 1));
    assertFalse(Searches.intercepted(new PathFinder(ownEvaluator, 1), ownEvaluator, 1));
  }
}
