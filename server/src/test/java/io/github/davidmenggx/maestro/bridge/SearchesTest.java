package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
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
  void anInterceptorReplacedForAWhileIsRestored() {
    Searches.Interceptor outer = interceptor();
    Searches.Interceptor inner = interceptor();
    Searches.Interceptor before = Searches.intercept(outer);
    try {
      Searches.Interceptor replaced = Searches.intercept(inner);
      assertSame(outer, replaced);
      assertSame(inner, Searches.intercept(replaced));
      assertSame(outer, Searches.interceptor());
    } finally {
      Searches.intercept(before);
    }
  }

  @Test
  void onlyCallsInsideACallerThatFollowsItsPathMayBeOffloaded() {
    assertFalse(Searches.offloadable());
    Searches.enter();
    Searches.enter();
    Searches.leave();
    assertTrue(Searches.offloadable(), "a nested call left the outer one");
    Searches.leave();
    assertFalse(Searches.offloadable());
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

  @Test
  void vanillasSearchRunsOnceInTheCallInFullOrOnAShareAndAgainLaterAsOftenAsAsked() {
    List<Double> shares = new ArrayList<>();
    int[] later = new int[1];
    Searches.Vanilla full =
        new Searches.Vanilla(
            share -> {
              shares.add(share);
              return null;
            },
            () -> {
              later[0]++;
              return null;
            });
    full.search();
    assertThrows(IllegalStateException.class, full::search);
    assertThrows(IllegalStateException.class, () -> full.cheap(0.5f));
    full.later().get();
    full.later().get();

    Searches.Vanilla cheap =
        new Searches.Vanilla(
            share -> {
              shares.add(share);
              return null;
            },
            () -> null);
    cheap.cheap(0.25f);
    assertThrows(IllegalStateException.class, cheap::search);

    assertEquals(List.of(1.0, 0.25), shares);
    assertEquals(2, later[0]);
  }

  private static Searches.Interceptor interceptor() {
    ProfileTable profiles = new ProfileTable();
    return new Searches.Interceptor() {
      @Override
      public ProfileTable profiles() {
        return profiles;
      }

      @Override
      public boolean wants(Mob mob) {
        return true;
      }

      @Override
      public Path search(Mob mob, SearchCall call, Searches.Vanilla vanilla) {
        return vanilla.search();
      }
    };
  }
}
