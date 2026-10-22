package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.config.MaestroConfig.Offload;
import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.Metrics;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PathOffloadTest {

  private static final int MOB = 7;

  private static final BlockPos START = new BlockPos(0, 64, 0);

  private static final BlockPos TARGET = new BlockPos(6, 64, 0);

  private static final MobClass PROFILE =
      new MobClass(
          (short) 0,
          new MobClass.Shape(0.6f, 1.95f, 0.6f),
          new MobClass.Abilities(false, false, true, false),
          new float[PathTypes.count()]);

  private final TestClock clock = new TestClock();
  private final FakeController controller = new FakeController();
  private final Map<Integer, FakeNavigator> navigators = new LinkedHashMap<>();
  private final FakeNavigator navigator = new FakeNavigator();
  private final List<Path> vanillaCalls = new ArrayList<>();

  private PathOffload offload;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @BeforeEach
  void setUp() {
    HeldPaths.clock(clock);
    navigators.put(MOB, navigator);
    offload = offload(Offload.ON);
  }

  @Test
  void aMobFollowingNothingIsHeldUntilItsAnswerReplacesTheHold() {
    Path placeholder = search(START, TARGET);

    assertTrue(HeldPaths.isHeld(placeholder));
    assertEquals(START, placeholder.getNodePos(0));
    assertEquals(1, controller.requests());
    assertEquals(List.of(), vanillaCalls);
    navigator.follow(placeholder, TARGET);

    clock.tick++;
    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    assertEquals(7, navigator.path.getNodeCount());
    assertFalse(HeldPaths.isHeld(navigator.path));
    assertEquals(TARGET, navigator.target);
    assertEquals(1, offload.outcomes("APPLIED"));
  }

  @Test
  void aMobFollowingItsLivePathKeepsItAndTheAnswerStartsFromTheNodeItHasReached() {
    Path live = path(line(-4, 0));
    navigator.follow(live, START);

    assertSame(live, search(START, TARGET));

    // It walked on to x = 2 while it waited.
    navigator.position = new Vec3(2.5, 64, 0.5);
    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    assertEquals(2, navigator.path.getNextNodeIndex());
    assertEquals(TARGET, navigator.target);
  }

  @Test
  void aRefusedRequestIsSearchedByVanillaInTheSameCall() {
    controller.refuseWith = DropReason.QUEUE_DEPTH;

    Path path = search(START, TARGET);

    assertEquals(1, vanillaCalls.size());
    assertSame(vanillaCalls.get(0), path);
    assertEquals(1, offload.outcomes("REFUSED"));
    // Nothing waits, so there is nothing to cancel.
    offload.tickEnding();
    assertEquals(0, controller.cancels());
  }

  @Test
  void aRefusedRepeatEndsTheHoldOfTheRequestItReplaced() {
    Path held = search(START, TARGET);
    navigator.follow(held, TARGET);
    controller.refuseWith = DropReason.NO_NODE;

    search(START, new BlockPos(0, 64, 9));

    assertFalse(HeldPaths.isHeld(held));
    assertTrue(held.isDone());
  }

  @Test
  void aRepeatCallForTheSameSearchWaitsOnTheSameRequest() {
    Path held = search(START, TARGET);
    navigator.follow(held, TARGET);

    assertSame(held, search(START, TARGET));
    assertEquals(1, controller.requests());
  }

  @Test
  void aDropAfterAdmissionEndsTheHoldSoTheGoalAsksAgain() {
    Path held = search(START, TARGET);
    navigator.follow(held, TARGET);

    offload.pathDropped(MOB, DropReason.TIMEOUT);

    assertTrue(navigator.path.isDone());
    assertEquals(1, offload.outcomes("DROPPED"));
  }

  @Test
  void aGoalThatStopsTheNavigationAndHandsItThePathBackKeepsItsRequest() {
    // A strolling mob spots a target. The attack goal's `canUse` searches and gets the
    // stroll path, then the goal selector stops the stroll goal, which stops the
    // navigation, and starts the attack goal, which follows that path again.
    Path stroll = path(line(-4, 0));
    navigator.follow(stroll, START);
    Path placeholder = search(START, TARGET);
    navigator.stop();
    navigator.follow(placeholder, TARGET);

    offload.tickEnding();

    assertEquals(0, controller.cancels());
    offload.pathComputed(answer(START, TARGET, line(0, 6)));
    assertEquals(1, offload.outcomes("APPLIED"));
  }

  @Test
  void aHeldMobWhoseGoalStopsAndRestartsInOneTickKeepsItsRequest() {
    Path held = search(START, TARGET);
    navigator.follow(held, TARGET);
    navigator.stop();
    navigator.follow(held, TARGET);

    offload.tickEnding();

    assertEquals(0, controller.cancels());
  }

  @Test
  void aMobThatNoLongerFollowsItsPlaceholderWhenTheTickEndsCancelsItsRequest() {
    Path held = search(START, TARGET);
    navigator.follow(held, TARGET);
    // The goal ended, and another moved the mob along a path of its own.
    navigator.stop();
    navigator.follow(path(line(0, -3)), START);

    offload.tickEnding();

    assertEquals(1, controller.cancels());
    assertEquals(1, offload.outcomes("CANCELED"));
    // Only once, and an answer that still arrives is not applied.
    offload.tickEnding();
    assertEquals(1, controller.cancels());
    Path other = navigator.path;
    offload.pathComputed(answer(START, TARGET, line(0, 6)));
    assertSame(other, navigator.path);
  }

  @Test
  void aMobThatStoppedCancelsItsRequest() {
    Path held = search(START, TARGET);
    navigator.follow(held, TARGET);
    navigator.stop();

    offload.tickEnding();

    assertEquals(1, controller.cancels());
  }

  @Test
  void aMobThatLeftTheLevelCancelsItsRequest() {
    navigator.follow(search(START, TARGET), TARGET);
    navigators.remove(MOB);

    offload.tickEnding();

    assertEquals(1, controller.cancels());
  }

  @Test
  void aOneNodeAnswerAtTheHeldBlockReplacesTheHold() {
    // The target is next to the mob, so vanilla's path is the block it stands on.
    Path held = search(START, START.east());
    navigator.follow(held, START.east());

    offload.pathComputed(answer(START, START.east(), line(0, 0)));

    assertNotSame(held, navigator.path);
    assertFalse(HeldPaths.isHeld(navigator.path));
    assertFalse(navigator.path.isDone());
    assertEquals(1, navigator.path.getNodeCount());
  }

  @Test
  void anAnswerAfterTheMobMovedOnServesOneLaterCallForTheSameSearch() {
    Path held = search(START, TARGET);
    navigator.follow(held, TARGET);
    Path other = path(line(0, -3));
    navigator.follow(other, START);

    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    assertSame(other, navigator.path);
    assertEquals(1, offload.outcomes("MOVED_ON"));
    clock.tick += PathOffload.FRESH_TICKS;
    Path reused = search(START, TARGET);
    assertEquals(7, reused.getNodeCount());
    assertEquals(1, offload.outcomes("REUSED"));
    assertEquals(1, controller.requests());
    // Once only.
    search(START, TARGET);
    assertEquals(2, controller.requests());
  }

  @Test
  void aKeptAnswerServesNeitherAnotherSearchNorALateOne() {
    navigator.follow(search(START, TARGET), TARGET);
    navigator.follow(path(line(0, -3)), START);
    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    search(START.west(), TARGET);
    assertEquals(0, offload.outcomes("REUSED"));

    // It moves on again, and asks once its answer is stale.
    navigator.follow(path(line(0, -5)), START);
    offload.pathComputed(answer(START.west(), TARGET, line(-1, 6)));
    assertEquals(2, offload.outcomes("MOVED_ON"));
    clock.tick += PathOffload.FRESH_TICKS + 1;
    search(START.west(), TARGET);
    assertEquals(0, offload.outcomes("REUSED"));
  }

  @Test
  void anAnswerWithNoNodesStopsTheNavigationAsVanillasNullWould() {
    navigator.follow(search(START, TARGET), TARGET);

    offload.pathComputed(answer(START, TARGET, List.of()));

    assertNull(navigator.path);
  }

  @Test
  void anAnswerForAMobThatLeftIsDropped() {
    navigator.follow(search(START, TARGET), TARGET);
    navigators.remove(MOB);

    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    assertEquals(0, offload.outcomes("APPLIED"));
    // Its state goes with it, so nothing is left to cancel.
    offload.mobRemoved(MOB);
    offload.tickEnding();
    assertEquals(0, controller.cancels());
  }

  @Test
  void shadowModeKeepsVanillasPathAndComparesWithItAsItsSearchReturnedIt() {
    offload = offload(Offload.SHADOW);
    Path vanilla = search(START, TARGET);
    assertSame(vanillaCalls.get(0), vanilla);
    navigator.follow(vanilla, TARGET);
    // A mob that avoids the sun has its path cut short where it sees the sky.
    vanilla.truncateNodes(3);

    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    assertEquals(1, offload.outcomes("SHADOW_MATCHED"));
    assertSame(vanilla, navigator.path);
    // Shadow mode never changes what the mob follows, so nothing waits for a cancel.
    offload.tickEnding();
    assertEquals(0, controller.cancels());
  }

  @Test
  void shadowModeCountsAnAnswerThatDiffersFromVanillas() {
    offload = offload(Offload.SHADOW);
    search(START, TARGET);

    offload.pathComputed(answer(START, TARGET, line(0, 5)));

    assertEquals(1, offload.outcomes("SHADOW_DIFFERED"));
  }

  @Test
  void anAnswerBecomesThePathVanillasSearchWouldHaveReturned() {
    WorldOutput.PathResult answer =
        new WorldOutput.PathResult(
            7,
            new Pos(3, 64, 0),
            true,
            List.of(
                new WorldOutput.Node(new Pos(0, 64, 0), (byte) PathType.WALKABLE.ordinal()),
                new WorldOutput.Node(new Pos(1, 64, 0), (byte) PathType.DOOR_OPEN.ordinal()),
                new WorldOutput.Node(new Pos(3, 64, 0), (byte) PathType.WALKABLE.ordinal())),
            2.5f,
            40);

    Path path = PathOffload.toPath(answer);

    assertEquals(3, path.getNodeCount());
    assertEquals(new BlockPos(1, 64, 0), path.getNodePos(1));
    assertEquals(PathType.DOOR_OPEN, path.getNode(1).type);
    assertEquals(new BlockPos(3, 64, 0), path.getTarget());
    assertTrue(path.canReach());
    assertEquals(0f, path.getDistToTarget());
    // The navigation trims a path it follows, so its nodes must be mutable.
    path.truncateNodes(1);
    assertEquals(1, path.getNodeCount());
  }

  @Test
  void anAnswerWithNoNodesIsNoPath() {
    WorldOutput.PathResult answer =
        new WorldOutput.PathResult(7, new Pos(3, 64, 0), false, List.of(), 0f, 40);

    assertNull(PathOffload.toPath(answer));
  }

  private PathOffload offload(Offload mode) {
    PathOffload built =
        new PathOffload(
            null,
            RegionLayout.spiral(List.of(NodeId.of(0))),
            navigators::get,
            clock,
            new NoMetrics(),
            new ProfileTable(),
            mode);
    built.bind(controller);
    controller.offload = built;
    return built;
  }

  /** A call from {@code start} to {@code target}, whose vanilla search finds a straight line. */
  private Path search(BlockPos start, BlockPos target) {
    SearchCall call = new SearchCall(PROFILE, start, target, new WorldEvent.Search(1, 16f, 256, 3));
    return offload.search(
        MOB,
        navigator,
        call,
        () -> {
          Path vanilla = path(line(start.getX(), target.getX()));
          vanillaCalls.add(vanilla);
          return vanilla;
        });
  }

  private WorldOutput.PathResult answer(BlockPos start, BlockPos target, List<BlockPos> nodes) {
    List<WorldOutput.Node> answered = new ArrayList<>();
    for (BlockPos node : nodes) {
      answered.add(
          new WorldOutput.Node(
              new Pos(node.getX(), node.getY(), node.getZ()), (byte) PathType.WALKABLE.ordinal()));
    }
    return new WorldOutput.PathResult(
        MOB,
        new Pos(target.getX(), target.getY(), target.getZ()),
        !nodes.isEmpty() && nodes.get(nodes.size() - 1).equals(target),
        answered,
        1f,
        clock.tick);
  }

  /** Blocks along x at y 64 and z 0, from {@code fromX} to {@code toX}, both included. */
  private static List<BlockPos> line(int fromX, int toX) {
    List<BlockPos> nodes = new ArrayList<>();
    int step = toX >= fromX ? 1 : -1;
    for (int x = fromX; x != toX + step; x += step) {
      nodes.add(new BlockPos(x, 64, 0));
    }
    return nodes;
  }

  private static Path path(List<BlockPos> blocks) {
    List<Node> nodes = new ArrayList<>();
    for (BlockPos block : blocks) {
      nodes.add(new Node(block.getX(), block.getY(), block.getZ()));
    }
    return new Path(nodes, blocks.get(blocks.size() - 1), true);
  }

  /** A navigation that takes a path the way vanilla's does: one with the same nodes is kept. */
  private static final class FakeNavigator implements PathOffload.Navigator {
    Path path;
    BlockPos target;
    Vec3 position = Vec3.atBottomCenterOf(START);

    @Override
    public Path path() {
      return path;
    }

    @Override
    public void stop() {
      path = null;
    }

    @Override
    public void follow(Path next, BlockPos target) {
      this.target = target;
      if (!next.sameAs(path)) {
        path = next;
      }
    }

    @Override
    public Vec3 position() {
      return position;
    }
  }

  /**
   * Takes requests as the controller does: a mob's repeat drops its older request as superseded,
   * and {@code refuseWith}, if set, refuses each new one at once.
   */
  private static final class FakeController implements WorldInput {
    final List<WorldEvent> events = new ArrayList<>();
    final Set<Integer> live = new HashSet<>();
    PathOffload offload;
    DropReason refuseWith;

    @Override
    public void accept(WorldEvent event) {
      events.add(event);
      if (event instanceof WorldEvent.PathRequested requested) {
        if (live.remove(requested.mobId())) {
          offload.pathDropped(requested.mobId(), DropReason.SUPERSEDED);
        }
        if (refuseWith != null) {
          offload.pathDropped(requested.mobId(), refuseWith);
          return;
        }
        live.add(requested.mobId());
      } else if (event instanceof WorldEvent.PathCanceled canceled) {
        live.remove(canceled.mobId());
      }
    }

    long requests() {
      return events.stream().filter(WorldEvent.PathRequested.class::isInstance).count();
    }

    long cancels() {
      return events.stream().filter(WorldEvent.PathCanceled.class::isInstance).count();
    }
  }

  private static final class NoMetrics implements Metrics {
    @Override
    public void count(String name, long delta, String... labels) {}

    @Override
    public void record(String name, double value, String... labels) {}

    @Override
    public void gauge(String name, double value, String... labels) {}
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
