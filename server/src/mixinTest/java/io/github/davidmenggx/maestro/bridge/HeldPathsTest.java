package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.Clock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeldPathsTest {

  private static final BlockPos AT = new BlockPos(3, 64, -7);
  private static final BlockPos TARGET = new BlockPos(20, 64, 9);

  private final TestClock clock = new TestClock();

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @BeforeEach
  void useTheTestClock() {
    HeldPaths.clock(clock);
  }

  @Test
  void aHeldPathIsOneNodeAtTheMobThatIsNeverDoneAndNeverAdvances() {
    Path held = HeldPaths.hold(AT, TARGET);

    assertEquals(1, held.getNodeCount());
    assertEquals(AT, held.getNodePos(0));
    assertEquals(TARGET, held.getTarget());
    assertFalse(held.canReach());
    held.advance();
    held.advance();
    assertTrue(HeldPaths.isHeld(held));
    assertFalse(held.isDone());
    assertEquals(0, held.getNextNodeIndex());
  }

  @Test
  void aReleasedPathIsDone() {
    Path held = HeldPaths.hold(AT, TARGET);

    HeldPaths.release(held);

    assertFalse(HeldPaths.isHeld(held));
    assertTrue(held.isDone());
  }

  @Test
  void aHoldEndsOnItsOwnOnceItOutlivesAnyRequest() {
    Path held = HeldPaths.hold(AT, TARGET);

    clock.tick += HeldPaths.MAX_HOLD_TICKS;
    assertFalse(held.isDone());
    clock.tick++;
    assertTrue(held.isDone(), "a hold past its last tick is released");
    assertFalse(HeldPaths.isHeld(held));
  }

  @Test
  void aRenewedHoldLastsAsLongAsANewOne() {
    Path held = HeldPaths.hold(AT, TARGET);
    clock.tick += HeldPaths.MAX_HOLD_TICKS;

    HeldPaths.renew(held);
    clock.tick += HeldPaths.MAX_HOLD_TICKS;

    assertTrue(HeldPaths.isHeld(held));
  }

  @Test
  void aHoldWhoseNodeWasTrimmedAwayEnds() {
    // The navigation of a mob that avoids the sun trims nodes that see the sky.
    Path held = HeldPaths.hold(AT, TARGET);

    held.truncateNodes(0);

    assertFalse(HeldPaths.isHeld(held));
    assertTrue(held.isDone());
  }

  @Test
  void aHoldWherePathWithItsNodeEndedWouldBeSwallowed() {
    Path ended = HeldPaths.hold(AT, TARGET);
    HeldPaths.release(ended);
    Path finished = new Path(new ArrayList<>(List.of(new Node(3, 64, -7))), AT, true);
    finished.advance();
    Path hold = HeldPaths.hold(AT, TARGET);

    assertTrue(HeldPaths.swallows(ended, hold));
    assertTrue(HeldPaths.swallows(finished, hold));
  }

  @Test
  void onlyAHoldCanBeSwallowedAndOnlyByAnotherPathWithItsNodeThatIsNotHeld() {
    Path hold = HeldPaths.hold(AT, TARGET);
    Path stillHeld = HeldPaths.hold(AT, TARGET);
    Path elsewhere = HeldPaths.hold(AT.east(), TARGET);
    HeldPaths.release(elsewhere);
    Path answer = new Path(new ArrayList<>(List.of(new Node(3, 64, -7))), AT, true);
    Path ended = HeldPaths.hold(AT, TARGET);
    HeldPaths.release(ended);

    assertFalse(HeldPaths.swallows(stillHeld, hold));
    assertFalse(HeldPaths.swallows(hold, hold));
    assertFalse(HeldPaths.swallows(elsewhere, hold));
    assertFalse(HeldPaths.swallows(null, hold));
    assertFalse(HeldPaths.swallows(ended, answer));
    assertFalse(HeldPaths.swallows(ended, null));
  }

  @Test
  void anOrdinaryPathIsNotHeld() {
    List<Node> nodes = new ArrayList<>(List.of(new Node(0, 64, 0), new Node(1, 64, 0)));
    Path path = new Path(nodes, TARGET, true);

    assertFalse(HeldPaths.isHeld(path));
    path.advance();
    path.advance();
    assertTrue(path.isDone());
  }

  @Test
  void everyPathKnowsTheTickItWasMadeOn() {
    Path path = new Path(new ArrayList<>(List.of(new Node(0, 64, 0))), TARGET, true);

    clock.tick += 10;
    assertTrue(HeldPaths.madeWithin(path, 10));
    clock.tick++;
    assertFalse(HeldPaths.madeWithin(path, 10));
  }

  @Test
  void aPathMadeWithNoClockIsNeverRecent() {
    HeldPaths.clock(null);
    Path path = new Path(new ArrayList<>(List.of(new Node(0, 64, 0))), TARGET, true);
    HeldPaths.clock(clock);

    assertFalse(HeldPaths.madeWithin(path, Integer.MAX_VALUE));
  }

  private static final class TestClock implements Clock {
    long tick = 100;

    @Override
    public long tick() {
      return tick;
    }

    @Override
    public long nowNanos() {
      return 0;
    }
  }
}
