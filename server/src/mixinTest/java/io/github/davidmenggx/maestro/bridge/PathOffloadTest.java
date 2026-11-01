package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.config.MaestroConfig.Offload;
import io.github.davidmenggx.maestro.controller.core.RequestTracker;
import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.Metrics;
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
import java.util.Locale;
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

  /** What one full search costs the server thread, by the test's clock. */
  private static final long SEARCH_NANOS = 400_000;

  private final TestClock clock = new TestClock();
  private final RecordedMetrics metrics = new RecordedMetrics();
  private final FakeController controller = new FakeController();
  private final Map<Integer, FakeNavigator> navigators = new LinkedHashMap<>();
  private final FakeNavigator navigator = new FakeNavigator(MOB);
  private final List<Path> vanillaCalls = new ArrayList<>();
  private final List<Double> vanillaShares = new ArrayList<>();
  private final List<Integer> laterSearches = new ArrayList<>();
  private final List<Vec3> players = new ArrayList<>();
  private long nanos;

  /** Whether the mob of the next call has a target. */
  private boolean hunting = true;

  /** Whether the next calls' vanilla searches, in the call and later, find nothing. */
  private boolean vanillaFindsNothing;

  private boolean laterFindsNothing;

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
    navigator.follow(live, TARGET);

    // It walked on to x = 2 while it waited.
    navigator.position = new Vec3(2.5, 64, 0.5);
    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    assertEquals(2, navigator.path.getNextNodeIndex());
    assertEquals(TARGET, navigator.target);
  }

  @Test
  void aRefusedRequestIsSearchedByVanillaInTheSameCall() {
    controller.refuseWith = DropReason.NO_NODE;

    Path path = search(START, TARGET);

    assertEquals(1, vanillaCalls.size());
    assertSame(vanillaCalls.get(0), path);
    assertEquals(1, offload.outcomes("REFUSED"));
    // Nothing waits, so there is nothing to cancel.
    offload.tickEnding();
    assertEquals(0, controller.cancels());
  }

  @Test
  void aShedMobKeepsTheLivePathItFollows() {
    Path live = path(line(-4, 0));
    navigator.follow(live, START);
    controller.refuseWith = DropReason.SHED;

    assertSame(live, search(START, TARGET));

    assertEquals(List.of(), vanillaCalls);
    assertEquals(1, offload.outcomes("SHED"));
    offload.tickEnding();
    assertEquals(0, controller.cancels());
  }

  @Test
  void aShedMobWithNoPathIdles() {
    controller.refuseWith = DropReason.SHED;

    assertNull(search(START, TARGET));

    assertEquals(List.of(), vanillaCalls);
    assertEquals(1, offload.outcomes("SHED"));
  }

  @Test
  void aShedRepeatEndsTheHoldOfTheRequestItReplacedAndIdles() {
    Path held = search(START, TARGET);
    navigator.follow(held, TARGET);
    controller.refuseWith = DropReason.SHED;

    assertNull(search(START, new BlockPos(0, 64, 9)));

    assertFalse(HeldPaths.isHeld(held));
    assertEquals(List.of(), vanillaCalls);
  }

  @Test
  void aRequestSaysWhetherItsMobIsHunting() {
    search(START, TARGET);
    hunting = false;
    search(START, START.east());

    List<Boolean> sent =
        controller.events.stream()
            .filter(WorldEvent.PathRequested.class::isInstance)
            .map(event -> ((WorldEvent.PathRequested) event).hunting())
            .toList();
    assertEquals(List.of(true, false), sent);
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
  void aMobAskingAgainWhereItsHoldEndedIsHeldAgainAndKeepsItsRequest() {
    Path ended = search(START, TARGET);
    navigator.follow(ended, TARGET);
    offload.pathDropped(MOB, DropReason.TIMEOUT);
    assertSame(ended, navigator.path);

    // The new hold has the ended one's node, which the navigation would keep in its place.
    Path placeholder = search(START, TARGET);
    navigator.follow(placeholder, TARGET);
    offload.tickEnding();

    assertSame(placeholder, navigator.path);
    assertTrue(HeldPaths.isHeld(navigator.path));
    assertEquals(0, controller.cancels());
    offload.pathComputed(answer(START, TARGET, line(0, 6)));
    assertEquals(1, offload.outcomes("APPLIED"));
  }

  @Test
  void aFinishedPathAtTheMobsBlockDoesNotKeepItFromBeingHeld() {
    Path arrived = path(List.of(START));
    navigator.follow(arrived, START);
    arrived.advance();
    assertTrue(arrived.isDone());

    Path placeholder = search(START, TARGET);
    navigator.follow(placeholder, TARGET);
    offload.tickEnding();

    assertSame(placeholder, navigator.path);
    assertEquals(0, controller.cancels());
  }

  @Test
  void aGoalThatStopsTheNavigationAndHandsItThePathBackKeepsItsRequest() {
    // The attack goal's `canUse` gets the stroll path, the selector stops the stroll (and the
    // navigation), then the attack goal follows that path again.
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
  void aCallerThatDoesNotTakeItsPlaceholderHasItsRequestCanceled() {
    // `canUse` searches and declines; the mob strolls on along the same path, so only what the
    // navigation was handed shows it.
    Path stroll = path(line(-4, 0));
    navigator.follow(stroll, START);
    assertSame(stroll, search(START, TARGET));

    offload.tickEnding();

    assertEquals(1, controller.cancels());
    offload.pathComputed(answer(START, TARGET, line(0, 6)));
    assertSame(stroll, navigator.path);
    assertEquals(0, offload.outcomes("APPLIED"));
  }

  @Test
  void aCallerThatDeclinesWhileTheMobIsHeldEndsTheHold() {
    Path held = search(START, TARGET);
    navigator.follow(held, TARGET);
    // The new request replaces the one the hold was for, and the caller does not take it.
    assertSame(held, search(START, new BlockPos(0, 64, 9)));

    offload.tickEnding();

    assertEquals(1, controller.cancels());
    assertTrue(held.isDone());
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
  void anAnswerAfterTheMobMovedOnIsDroppedAndALaterCallAsksAgain() {
    Path held = search(START, TARGET);
    navigator.follow(held, TARGET);
    Path other = path(line(0, -3));
    navigator.follow(other, START);

    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    assertSame(other, navigator.path);
    assertEquals(1, offload.outcomes("MOVED_ON"));
    search(START, TARGET);
    assertEquals(2, controller.requests());
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
  void shadowModeAsksOnceForARepeatedCallButRunsVanillaEachTime() {
    offload = offload(Offload.SHADOW);

    search(START, TARGET);
    search(START, TARGET);

    assertEquals(2, vanillaCalls.size());
    assertEquals(1, controller.requests());
  }

  @Test
  void shadowModeComparesReachAndEveryNode() {
    offload = offload(Offload.SHADOW);

    search(START, TARGET);
    List<BlockPos> unreached = line(0, 6);
    offload.pathComputed(answer(START, TARGET, unreached, false));
    search(START, TARGET);
    List<BlockPos> stepped = line(0, 6);
    stepped.set(3, stepped.get(3).above());
    offload.pathComputed(answer(START, TARGET, stepped, true));
    search(START, TARGET);
    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    assertEquals(2, offload.outcomes("SHADOW_DIFFERED"));
    assertEquals(1, offload.outcomes("SHADOW_MATCHED"));
  }

  @Test
  void shadowModeMatchesNoPathOnlyWithNoPath() {
    offload = offload(Offload.SHADOW);
    vanillaFindsNothing = true;

    search(START, TARGET);
    offload.pathComputed(answer(START, TARGET, List.of()));
    search(START, TARGET);
    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    assertEquals(1, offload.outcomes("SHADOW_MATCHED"));
    assertEquals(1, offload.outcomes("SHADOW_DIFFERED"));
  }

  @Test
  void shadowModeCountsEveryMismatchPastThoseItLogs() {
    offload = offload(Offload.SHADOW);

    for (int i = 0; i <= PathOffload.MAX_LOGGED_MISMATCHES; i++) {
      search(START, TARGET);
      offload.pathComputed(answer(START, TARGET, line(0, 5)));
    }

    assertEquals(PathOffload.MAX_LOGGED_MISMATCHES + 1, offload.outcomes("SHADOW_DIFFERED"));
  }

  @Test
  void shadowModeComparesNothingForARefusedCall() {
    offload = offload(Offload.SHADOW);
    controller.refuseWith = DropReason.NO_NODE;

    Path vanilla = search(START, TARGET);
    offload.pathComputed(answer(START, TARGET, line(0, 6)));

    assertSame(vanillaCalls.get(0), vanilla);
    assertEquals(0, offload.outcomes("ANSWERED"));
  }

  @Test
  void anAnswerOrADropForAMobWithNoRequestIsIgnored() {
    offload.pathComputed(answer(START, TARGET, line(0, 6)));
    offload.pathDropped(MOB, DropReason.TIMEOUT);
    navigator.follow(search(START, TARGET), TARGET);
    offload.pathComputed(answer(START, TARGET, line(0, 6)));
    offload.pathDropped(MOB, DropReason.TIMEOUT);

    assertEquals(1, offload.outcomes("ANSWERED"));
    assertEquals(0, offload.outcomes("DROPPED"));
  }

  @Test
  void aDropForAMobThatLeftIsCountedAndEndsNothing() {
    navigator.follow(search(START, TARGET), TARGET);
    navigators.remove(MOB);

    offload.pathDropped(MOB, DropReason.TIMEOUT);

    assertEquals(1, offload.outcomes("DROPPED"));
    assertTrue(HeldPaths.isHeld(navigator.path));
  }

  @Test
  void theSummaryCountsEachOutcomeAndHowLateEachAnswerWas() {
    navigator.follow(search(START, TARGET), TARGET);
    WorldOutput.PathResult answer = answer(START, TARGET, line(0, 6));
    clock.tick += 2;
    offload.pathComputed(answer);

    String summary = offload.summary();

    assertTrue(summary.startsWith("requested=1 refused=0 answered=1 applied=1 "), summary);
    assertTrue(summary.contains(" failover_full=0 "), summary);
    assertTrue(summary.endsWith(" ticks_late=2:1"), summary);
  }

  @Test
  void aMobWhoseNodeCannotTakeItKeepsAPathMadeRecently() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    Path recent = path(line(-4, 0));
    navigator.follow(recent, START);
    clock.tick += Failover.RECENT_TICKS;

    assertSame(recent, search(START, TARGET));

    assertEquals(List.of(), vanillaCalls);
    assertEquals(1, offload.failover().outcomes(Failover.Outcome.RECENT));
  }

  @Test
  void aMobWhoseNodeCannotTakeItIsSearchedInFullWhileNoOtherWaits() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    Path old = path(line(-4, 0));
    navigator.follow(old, START);
    clock.tick += Failover.RECENT_TICKS + 1;

    Path path = search(START, TARGET);

    assertSame(vanillaCalls.get(0), path);
    assertEquals(List.of(1.0), vanillaShares);
    assertEquals(1, offload.failover().outcomes(Failover.Outcome.FULL));
  }

  @Test
  void pastTheBudgetAMobGetsACheapPathAndItsFullSearchWhenTheBudgetRefills() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    int searches = (int) (Failover.BUDGET_NANOS / SEARCH_NANOS) + 1;
    for (int mob = 0; mob < searches; mob++) {
      search(mob, navigator(mob), START, TARGET);
    }
    // The last full search ran past the budget, so the next mob gets a cheap path and waits.
    Path cheap = search(searches, navigator(searches), START, TARGET);
    navigator(searches).follow(cheap, TARGET);
    assertEquals(Failover.CHEAP_SHARE, vanillaShares.get(vanillaShares.size() - 1), 1e-6);
    assertTrue(offload.failover().waits(searches));

    // Nothing is left of this tick's budget.
    offload.tickEnding();
    assertEquals(List.of(), laterSearches);

    clock.tick++;
    offload.tickEnding();
    assertEquals(List.of(searches), laterSearches);
    assertFalse(offload.failover().waits(searches));
    assertNotSame(cheap, navigator(searches).path);
    assertEquals(1, offload.failover().outcomes(Failover.Outcome.LATER));
  }

  @Test
  void theMobNearestAPlayerHasTheFirstTurn() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    FakeNavigator far = navigator(1);
    FakeNavigator near = navigator(2);
    near.position = new Vec3(200.5, 64, 0.5);
    players.add(new Vec3(210, 64, 0));
    far.follow(search(1, far, START, TARGET), TARGET);
    near.follow(search(2, near, START, TARGET), TARGET);

    clock.tick++;
    offload.tickEnding();

    assertEquals(List.of(2, 1), laterSearches);
  }

  @Test
  void aMobThatHasWaitedLongerThanItsDistanceCostsGoesFirstWhereverItIs() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    FakeNavigator far = navigator(1);
    FakeNavigator near = navigator(2);
    near.position = new Vec3(200.5, 64, 0.5);
    players.add(new Vec3(210, 64, 0));
    // The far mob is about 13 turns' worth of blocks further, and asked 14 ticks sooner.
    far.follow(search(1, far, START, TARGET), TARGET);
    clock.tick += 14;
    near.follow(search(2, near, START, TARGET), TARGET);

    clock.tick++;
    offload.tickEnding();

    assertEquals(List.of(1, 2), laterSearches);
  }

  @Test
  void aMobThatStopsFollowingItsCheapPathLosesItsTurn() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    navigator.follow(search(START, TARGET), TARGET);
    assertTrue(offload.failover().waits(MOB));
    navigator.stop();

    clock.tick += 2;
    offload.tickEnding();

    assertEquals(List.of(), laterSearches);
    assertFalse(offload.failover().waits(MOB));
    assertEquals(1, offload.failover().outcomes(Failover.Outcome.MOVED_ON));
  }

  @Test
  void aMobWhoseNodeTakesItsNextRequestNoLongerWaitsForAFullSearch() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    navigator.follow(search(START, TARGET), TARGET);
    assertTrue(offload.failover().waits(MOB));

    // The node is back: the cheap path is the placeholder, and the answer replaces it.
    controller.refuseWith = null;
    Path placeholder = search(START, TARGET);
    navigator.follow(placeholder, TARGET);
    assertFalse(offload.failover().waits(MOB));

    clock.tick++;
    offload.tickEnding();
    assertEquals(List.of(), laterSearches);
    assertSame(placeholder, navigator.path);
    offload.pathComputed(answer(START, TARGET, line(0, 6)));
    assertEquals(1, offload.outcomes("APPLIED"));
  }

  @Test
  void aMobSearchedInFullAfterARefusalNoLongerWaitsForAFullSearch() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    navigator.follow(search(START, TARGET), TARGET);
    assertTrue(offload.failover().waits(MOB));

    controller.refuseWith = DropReason.NO_NODE;
    search(START, TARGET);

    assertFalse(offload.failover().waits(MOB));
  }

  @Test
  void aShedMobNoLongerWaitsForAFullSearch() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    navigator.follow(search(START, TARGET), TARGET);
    assertTrue(offload.failover().waits(MOB));

    controller.refuseWith = DropReason.SHED;
    search(START, TARGET);

    assertFalse(offload.failover().waits(MOB));
  }

  @Test
  void otherRefusalsAreSearchedByVanillaWithoutTheFailover() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    controller.refuseWith = DropReason.NO_NODE;

    search(START, TARGET);

    assertEquals(1.0, vanillaShares.get(vanillaShares.size() - 1));
    assertFalse(offload.failover().waits(MOB));
  }

  @Test
  void aHeldOrFinishedPathIsNotKeptAsARecentOne() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    navigator.follow(HeldPaths.hold(START, TARGET), TARGET);
    search(START, TARGET);
    Path finished = path(line(-1, 0));
    finished.advance();
    finished.advance();
    navigator.path = finished;

    search(START, TARGET);

    assertEquals(0, offload.failover().outcomes(Failover.Outcome.RECENT));
    assertEquals(2, offload.failover().outcomes(Failover.Outcome.FULL));
  }

  @Test
  void aCheapSearchThatFindsNothingLeavesTheMobIdleAndNotWaiting() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    vanillaFindsNothing = true;

    assertNull(search(START, TARGET));

    assertFalse(offload.failover().waits(MOB));
    assertEquals(1, offload.failover().outcomes(Failover.Outcome.CHEAP));
  }

  @Test
  void aFullSearchThatFindsNothingLaterStopsTheNavigation() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    navigator.follow(search(START, TARGET), TARGET);
    laterFindsNothing = true;

    clock.tick++;
    offload.tickEnding();

    assertEquals(List.of(MOB), laterSearches);
    assertNull(navigator.path);
  }

  @Test
  void aMobThatLeftWhileWaitingLosesItsTurn() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    navigator.follow(search(START, TARGET), TARGET);
    navigators.remove(MOB);

    clock.tick++;
    offload.tickEnding();

    assertEquals(List.of(), laterSearches);
    assertEquals(1, offload.failover().outcomes(Failover.Outcome.MOVED_ON));
  }

  @Test
  void aWaitingMobThatAsksAgainKeepsItsPlaceInTheQueue() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    spendTheBudget();
    FakeNavigator first = navigator(2);
    FakeNavigator second = navigator(1);
    first.follow(search(2, first, START, TARGET), TARGET);
    // Past the time its cheap path counts as recent, so it asks the failover again.
    clock.tick += Failover.RECENT_TICKS + 2;
    second.follow(search(1, second, START, TARGET), TARGET);
    first.follow(search(2, first, START, TARGET), TARGET);

    clock.tick++;
    offload.tickEnding();

    assertEquals(List.of(2, 1), laterSearches);
  }

  @Test
  void theFailoverCountsNameEachOutcomeInOrder() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    search(START, TARGET);

    assertEquals(
        "{failover_recent=0, failover_full=1, failover_cheap=0, failover_later=0,"
            + " failover_moved_on=0}",
        offload.failover().counts().toString());
  }

  @Test
  void theStatsCountEachOutcomeAndHowLateEachAnswerWas() {
    navigator.follow(search(START, TARGET), TARGET);
    WorldOutput.PathResult answer = answer(START, TARGET, line(0, 6));
    clock.tick += 2;
    offload.pathComputed(answer);

    OffloadStats stats = offload.stats();

    assertEquals(1, stats.counts().get("requested"));
    assertEquals(1, stats.counts().get("applied"));
    assertEquals(0, stats.counts().get("failover_full"));
    assertEquals(RequestTracker.MAX_TICKS_LATE + 1, stats.ticksLate().size());
    assertEquals(1, stats.ticksLate().get(2));
  }

  @Test
  void eachOutcomeAndEachDropIsCountedByName() {
    navigator.follow(search(START, TARGET), TARGET);
    offload.pathDropped(MOB, DropReason.TIMEOUT);

    assertEquals(1, metrics.counter("path.offload{outcome=REQUESTED}"));
    assertEquals(1, metrics.counter("path.offload{outcome=DROPPED}"));
    assertEquals(1, metrics.counter("path.offload.dropped{reason=TIMEOUT}"));
  }

  @Test
  void theFailoverCountsEachOutcomeAndGaugesTheMobsWaitingForAFullSearch() {
    controller.refuseWith = DropReason.UNAVAILABLE;
    int searches = (int) (Failover.BUDGET_NANOS / SEARCH_NANOS) + 1;
    for (int mob = 0; mob < searches; mob++) {
      search(mob, navigator(mob), START, TARGET);
    }
    navigator(searches).follow(search(searches, navigator(searches), START, TARGET), TARGET);

    offload.tickEnding();
    assertEquals(searches, metrics.counter("path.failover{outcome=FULL}"));
    assertEquals(1, metrics.counter("path.failover{outcome=CHEAP}"));
    assertEquals(1.0, metrics.gauges.get("failover.waiting"));
    // Only full searches spend the budget, so only they are timed.
    assertEquals(searches * SEARCH_NANOS / 1000, metrics.counter("failover.search.micros"));

    clock.tick++;
    offload.tickEnding();
    assertEquals(1, metrics.counter("path.failover{outcome=LATER}"));
    assertEquals(0.0, metrics.gauges.get("failover.waiting"));
    assertEquals((searches + 1) * SEARCH_NANOS / 1000, metrics.counter("failover.search.micros"));
  }

  @Test
  void theStatsNamesDoNotDependOnTheDefaultLocale() {
    Locale before = Locale.getDefault();
    // Turkish lower-cases `I` to a dotless `ı`.
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    try {
      Set<String> names = offload.stats().counts().keySet();

      assertTrue(names.contains("applied"), names.toString());
      assertTrue(names.contains("shadow_differed"), names.toString());
      names.forEach(name -> assertTrue(name.matches("[a-z_]+"), name));
    } finally {
      Locale.setDefault(before);
    }
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
            new RegionLayout(1),
            navigators::get,
            clock,
            () -> nanos,
            () -> players,
            metrics,
            new ProfileTable(),
            mode);
    built.bind(controller);
    controller.offload = built;
    return built;
  }

  /** The navigation of another mob, which follows nothing yet. */
  private FakeNavigator navigator(int mob) {
    return navigators.computeIfAbsent(mob, FakeNavigator::new);
  }

  /**
   * Full searches in the failover, by mobs that never wait, until this tick's budget is gone; the
   * last runs past it.
   */
  private void spendTheBudget() {
    long before = offload.failover().outcomes(Failover.Outcome.FULL);
    long searches = Failover.BUDGET_NANOS / SEARCH_NANOS + 1;
    for (int i = 0; i < searches; i++) {
      int mob = 100 + (int) before + i;
      search(mob, navigator(mob), START, TARGET);
    }
    assertEquals(before + searches, offload.failover().outcomes(Failover.Outcome.FULL));
  }

  private Path search(BlockPos start, BlockPos target) {
    return search(MOB, navigator, start, target);
  }

  /**
   * A call from {@code start} to {@code target}, whose vanilla search finds a straight line and
   * costs {@link #SEARCH_NANOS} in full.
   */
  private Path search(int mob, FakeNavigator from, BlockPos start, BlockPos target) {
    SearchCall call =
        new SearchCall(PROFILE, start, target, new WorldEvent.Search(1, 16f, 256, 3), hunting);
    return offload.search(
        mob,
        from,
        call,
        new Searches.Vanilla(
            share -> {
              Path vanilla = vanillaFindsNothing ? null : path(line(start.getX(), target.getX()));
              vanillaCalls.add(vanilla);
              vanillaShares.add(share);
              nanos += (long) (SEARCH_NANOS * share);
              return vanilla;
            },
            () -> {
              laterSearches.add(mob);
              nanos += SEARCH_NANOS;
              return laterFindsNothing ? null : path(line(start.getX(), target.getX() + 1));
            }));
  }

  private WorldOutput.PathResult answer(BlockPos start, BlockPos target, List<BlockPos> nodes) {
    return answer(
        start, target, nodes, !nodes.isEmpty() && nodes.get(nodes.size() - 1).equals(target));
  }

  private WorldOutput.PathResult answer(
      BlockPos start, BlockPos target, List<BlockPos> nodes, boolean reached) {
    List<WorldOutput.Node> answered = new ArrayList<>();
    for (BlockPos node : nodes) {
      answered.add(
          new WorldOutput.Node(
              new Pos(node.getX(), node.getY(), node.getZ()), (byte) PathType.WALKABLE.ordinal()));
    }
    return new WorldOutput.PathResult(
        MOB,
        new Pos(target.getX(), target.getY(), target.getZ()),
        reached,
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

  /**
   * A navigation that takes a path the way vanilla's does: one with the same nodes is kept. As the
   * navigation mixin does, it tells the offload what it was handed, and a held path replaces one
   * that would swallow it.
   */
  private final class FakeNavigator implements PathOffload.Navigator {
    final int mob;
    Path path;
    BlockPos target;
    Vec3 position = Vec3.atBottomCenterOf(START);

    FakeNavigator(int mob) {
      this.mob = mob;
    }

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
      if (offload != null) {
        offload.handed(mob, next);
      }
      this.target = target;
      if (HeldPaths.swallows(path, next)) {
        path = null;
      }
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

  /** Counter sums and each gauge's last value, by name and labels, such as {@code a{k=v}}. */
  private static final class RecordedMetrics implements Metrics {
    final Map<String, Long> counters = new LinkedHashMap<>();
    final Map<String, Double> gauges = new LinkedHashMap<>();

    @Override
    public void count(String name, long delta, String... labels) {
      counters.merge(key(name, labels), delta, Long::sum);
    }

    @Override
    public void record(String name, double value, String... labels) {}

    @Override
    public void gauge(String name, double value, String... labels) {
      gauges.put(key(name, labels), value);
    }

    long counter(String key) {
      return counters.getOrDefault(key, 0L);
    }

    private static String key(String name, String... labels) {
      StringBuilder key = new StringBuilder(name);
      for (int i = 0; i + 1 < labels.length; i += 2) {
        key.append(i == 0 ? "{" : ",").append(labels[i]).append('=').append(labels[i + 1]);
      }
      return labels.length == 0 ? key.toString() : key.append('}').toString();
    }
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
