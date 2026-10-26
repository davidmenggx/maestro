package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Which navigation calls mark their searches as ones that may be offloaded. The mobs have no level,
 * so each navigation's own search is replaced by one that records the mark.
 */
class OffloadableSearchesTest {

  private static final BlockPos TARGET = new BlockPos(6, 64, 0);

  /** {@link Searches#offloadable()} as each search saw it. */
  private final List<Boolean> marks = new ArrayList<>();

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @AfterEach
  void removeTheInterceptor() {
    Searches.intercept(null);
  }

  @Test
  void eachMoveToThatSearchesMarksItsSearch() {
    Zombie zombie = new Zombie(EntityType.ZOMBIE, null);
    PathNavigation navigation = ground(zombie);

    assertFalse(navigation.moveTo(TARGET.getX(), TARGET.getY(), TARGET.getZ(), 1.0));
    assertFalse(navigation.moveTo(TARGET.getX(), TARGET.getY(), TARGET.getZ(), 2, 1.0));
    assertFalse(navigation.moveTo(zombie, 1.0));

    assertEquals(List.of(true, true, true), marks);
    assertFalse(Searches.offloadable());
  }

  @Test
  void aCallerThatOnlyReadsAPathDoesNotMarkItsSearch() {
    Zombie zombie = new Zombie(EntityType.ZOMBIE, null);
    PathNavigation navigation = ground(zombie);

    assertNull(navigation.createPath(TARGET, 1));
    assertNull(navigation.createPath(zombie, 1));

    assertEquals(List.of(false, false), marks);
  }

  @Test
  void aSpiderChasingAnEntityMarksItsSearchAndHeadsStraightForItWithoutAPath() {
    Spider spider = new Spider(EntityType.SPIDER, null);
    WallClimberNavigation navigation =
        new WallClimberNavigation(spider, null) {
          @Override
          public Path createPath(Entity target, int reachRange) {
            marks.add(Searches.offloadable());
            return null;
          }
        };

    // With no path, the spider heads for the target directly, as in vanilla.
    assertTrue(navigation.moveTo(spider, 1.0));

    assertEquals(List.of(true), marks);
    assertFalse(Searches.offloadable());
  }

  @Test
  void theMarkEndsWhenTheSearchThrows() {
    Zombie zombie = new Zombie(EntityType.ZOMBIE, null);
    PathNavigation navigation =
        new GroundPathNavigation(zombie, null) {
          @Override
          public Path createPath(BlockPos pos, int reachRange) {
            throw new IllegalStateException("search failed");
          }
        };

    assertThrows(
        IllegalStateException.class,
        () -> navigation.moveTo(TARGET.getX(), TARGET.getY(), TARGET.getZ(), 1.0));

    assertFalse(Searches.offloadable());
  }

  @Test
  void aPathHandedToTheNavigationReachesTheInterceptor() {
    Zombie zombie = new Zombie(EntityType.ZOMBIE, null);
    PathNavigation navigation = ground(zombie);
    List<Path> handed = new ArrayList<>();
    Searches.intercept(new Handed(zombie, handed));
    // A finished path is not followed, so the navigation does not read the missing level.
    Path finished = new Path(List.of(new Node(0, 64, 0)), TARGET, false);
    finished.advance();

    assertFalse(navigation.moveTo(finished, 1.0));
    assertFalse(navigation.moveTo((Path) null, 1.0));

    assertEquals(List.of(finished), handed);
  }

  /** A navigation for {@code mob} whose searches record the mark and find nothing. */
  private PathNavigation ground(Mob mob) {
    return new GroundPathNavigation(mob, null) {
      @Override
      public Path createPath(BlockPos pos, int reachRange) {
        marks.add(Searches.offloadable());
        return null;
      }

      @Override
      public Path createPath(Entity target, int reachRange) {
        marks.add(Searches.offloadable());
        return null;
      }
    };
  }

  /** Records the paths handed to {@code mob}'s navigation, and takes no call. */
  private record Handed(Mob mob, List<Path> paths) implements Searches.Interceptor {

    @Override
    public ProfileTable profiles() {
      return new ProfileTable();
    }

    @Override
    public boolean wants(Mob caller) {
      return false;
    }

    @Override
    public Path search(Mob caller, SearchCall call, Searches.Vanilla vanilla) {
      throw new AssertionError("no call is taken");
    }

    @Override
    public void handed(Mob to, Path path) {
      assertEquals(mob, to);
      paths.add(path);
    }
  }
}
