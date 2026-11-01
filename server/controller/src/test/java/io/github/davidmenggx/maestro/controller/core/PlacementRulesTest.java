package io.github.davidmenggx.maestro.controller.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Offload;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.env.Tracing;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.sim.SimEvents;
import io.github.davidmenggx.maestro.controller.sim.SimMetrics;
import io.github.davidmenggx.maestro.controller.world.Pos;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The timing and order of {@link Placement}'s rules, one tick at a time. */
class PlacementRulesTest {

  private static final int GRACE = 30;
  private static final int HOT = 2 * Placement.WINDOW_TICKS;
  private static final int COLD = 3 * Placement.WINDOW_TICKS;
  private static final int CAPACITY = 4;
  private static final long RUN = 1;

  private final SimMetrics metrics = new SimMetrics();
  private final SimEvents events = new SimEvents();
  private final List<String> changes = new ArrayList<>();
  private List<NodeId> fleet;
  private RegionMap regions;
  private NodeLinks links;
  private NodeHealth health;
  private Capacity capacity;
  private Placement placement;
  private long tick;

  /** Regions of {@code cells} by {@code cells} cells for the first nodes, the rest spare. */
  private void start(int cells, int nodes, int spares) {
    fleet = IntStream.range(0, nodes).mapToObj(NodeId::of).toList();
    regions = new RegionLayout(cells).initial(fleet, spares);
    links = new NodeLinks(env(), 1, fleet);
    health = new NodeHealth(metrics, fleet);
    for (NodeId node : fleet) {
      links.greeted(node, 0);
      health.answered(node, 0);
    }
    capacity = new Capacity(CAPACITY, fleet);
    placement =
        new Placement(
            regions,
            fleet,
            links,
            health,
            new Placement.Policy(GRACE, HOT, COLD),
            capacity,
            new Recorder(),
            env());
  }

  @Test
  void aHolderDownForItsGraceHandsItsRegionToTheFirstSpareThatIsUp() {
    start(2, 4, 2);
    NodeId holder = NodeId.of(0);
    links.disconnected(holder);
    // The first spare is down too, so the next one takes the region.
    links.disconnected(NodeId.of(2));

    run(GRACE);
    assertEquals(1, regions.regionOf(holder));
    assertEquals(List.of(), changes);

    run(1);
    assertEquals(Regions.NONE, regions.regionOf(holder));
    assertEquals(List.of(NodeId.of(3)), regions.holders(1));
    assertEquals(List.of("released 1 node-0", "held 1 node-3"), changes);
    assertEquals(2, placement.version());
    assertEquals(
        List.of(NodeId.of(0), NodeId.of(2)),
        fleet.stream().filter(node -> regions.regionOf(node) == Regions.NONE).toList());
  }

  @Test
  void aHolderBackBeforeItsGraceRunsOutKeepsItsRegion() {
    start(1, 2, 1);
    NodeId holder = NodeId.of(0);
    links.disconnected(holder);
    run(GRACE - 1);
    links.greeted(holder, 0);
    run(3 * GRACE);

    assertEquals(1, regions.regionOf(holder));
    assertEquals(List.of(), changes);
  }

  @Test
  void aDownHolderWithNoSpareKeepsItsRegionUntilOneIsUp() {
    start(1, 2, 1);
    NodeId holder = NodeId.of(0);
    NodeId spare = NodeId.of(1);
    links.disconnected(holder);
    links.disconnected(spare);

    run(3 * GRACE);
    assertEquals(1, regions.regionOf(holder));

    links.greeted(spare, 0);
    run(1);
    assertEquals(List.of(spare), regions.holders(1));
    assertEquals(1, events.named("holder.added", "handoff").size());
  }

  @Test
  void aHolderThatComesBackBrieflyKeepsCountingItsTimeDownFromWhenItFirstWent() {
    start(1, 2, 1);
    NodeId holder = NodeId.of(0);
    links.disconnected(holder);
    run(5);
    // Back for less than the time that shows it is stable, then down again.
    links.greeted(holder, 0);
    run(Placement.STABLE_TICKS - 1);
    links.disconnected(holder);
    run(GRACE - 5 - (Placement.STABLE_TICKS - 1));
    assertEquals(List.of(), changes);
    run(1);
    assertEquals(List.of("released 1 node-0", "held 1 node-1"), changes);
  }

  @Test
  void aHolderBackLongEnoughStartsItsTimeDownOver() {
    start(1, 2, 1);
    NodeId holder = NodeId.of(0);
    links.disconnected(holder);
    run(GRACE - 1);
    links.greeted(holder, 0);
    run(Placement.STABLE_TICKS + 1);
    links.disconnected(holder);
    run(GRACE);
    assertEquals(List.of(), changes);
    run(1);
    assertEquals(List.of("released 1 node-0", "held 1 node-1"), changes);
  }

  @Test
  void aNodeWhoseRequestPathIsUnprovenIsNoSpare() {
    start(1, 3, 2);
    NodeId holder = NodeId.of(0);
    // The first spare's link came back, but nothing has shown its path works since.
    health.disconnected(NodeId.of(1));
    links.disconnected(holder);
    run(GRACE + 1);
    assertEquals(List.of("released 1 node-0", "held 1 node-2"), changes);
  }

  @Test
  void aRegionSheddingInEveryWindowOfItsHotTimeIsHalvedAndTheSpareTakesTheUpperHalf() {
    start(2, 2, 1);
    Region whole = regions.region(1);

    shedEveryWindow(whole, HOT / Placement.WINDOW_TICKS - 1);
    assertEquals(List.of(), changes);
    shedEveryWindow(whole, 1);

    // The holder keeps the lower half, which shares the corner, so it is not sent it again.
    assertEquals(List.of("split 1 into 2 [node-0]", "held 3 node-1"), changes);
    assertEquals(
        new Region(2, whole.minX(), whole.minZ(), RegionLayout.CELL, 2 * RegionLayout.CELL),
        regions.region(2));
    assertEquals(
        new Region(
            3, whole.minX() + RegionLayout.CELL, whole.minZ(), RegionLayout.CELL, whole.sizeZ()),
        regions.region(3));
    assertEquals(1, events.named("region.removed", "split").size());
  }

  @Test
  void aHotRegionIsCutWhereItsRequestsBalance() {
    start(3, 2, 1);
    Region whole = regions.region(1);
    // A tenth of the requests in each of the first two columns, the rest in the third.
    for (int window = 0; window < HOT / Placement.WINDOW_TICKS; window++) {
      for (int count = 0; count < 10; count++) {
        placement.shed(whole, cell(whole, 0, 0));
        placement.shed(whole, cell(whole, 1, 1));
      }
      for (int count = 0; count < 30; count++) {
        placement.sent(whole, cell(whole, 2, count % 3));
      }
      placement.shed(whole, cell(whole, 2, 2));
      run(Placement.WINDOW_TICKS);
    }

    // Two columns against one leaves the busier side 31 of 51 requests.
    assertEquals(List.of("split 1 into 2 [node-0]", "held 3 node-1"), changes);
    int cell = RegionLayout.CELL;
    assertEquals(new Region(2, whole.minX(), whole.minZ(), 2 * cell, 3 * cell), regions.region(2));
    assertEquals(
        new Region(3, whole.minX() + 2 * cell, whole.minZ(), cell, 3 * cell), regions.region(3));
  }

  @Test
  void aHotRegionWhoseRequestsNoCutBalancesTakesAReplica() {
    start(2, 2, 1);
    Region whole = regions.region(1);
    for (int window = 0; window < HOT / Placement.WINDOW_TICKS; window++) {
      placement.shed(whole, cell(whole, 1, 1));
      placement.sent(whole, cell(whole, 0, 0));
      for (int count = 0; count < 3; count++) {
        placement.sent(whole, cell(whole, 1, 1));
      }
      run(Placement.WINDOW_TICKS);
    }

    assertEquals(List.of("held 1 node-1"), changes);
    assertEquals(List.of(NodeId.of(0), NodeId.of(1)), regions.holders(1));
    List<SimEvents.Event> replicas = events.named("holder.added", "replica");
    assertEquals(1, replicas.size());
    assertEquals("node-1", replicas.get(0).get("node"));
    assertEquals(4, events.cells(replicas.get(0)));
  }

  @Test
  void theBalancedCutPrefersTheMiddleThenAcrossX() {
    int cell = RegionLayout.CELL;
    Region square = new Region(1, 0, 0, 4 * cell, 4 * cell);
    long[] even = new long[16];
    java.util.Arrays.fill(even, 1);
    assertEquals(new RegionLayout.Cut(true, 2), Placement.balancedCut(square, even));
    assertEquals(new RegionLayout.Cut(true, 2), Placement.balancedCut(square, new long[16]));
    // One column of cells holds it all, so only a cut across z divides it.
    long[] column = new long[16];
    for (int cz = 0; cz < 4; cz++) {
      column[4 + cz] = 5;
    }
    assertEquals(new RegionLayout.Cut(false, 2), Placement.balancedCut(square, column));
    // Exactly two thirds on the busier side still cuts; more replicates.
    Region pair = new Region(1, 0, 0, 2 * cell, cell);
    assertEquals(new RegionLayout.Cut(true, 1), Placement.balancedCut(pair, new long[] {1, 2}));
    assertEquals(null, Placement.balancedCut(pair, new long[] {1, 3}));
  }

  @Test
  void aWindowWithNoSheddingStartsTheHotTimeOver() {
    start(1, 2, 1);
    Region region = regions.region(1);
    shedEveryWindow(region, 1);
    run(Placement.WINDOW_TICKS);
    shedEveryWindow(region, 1);
    assertEquals(List.of(), changes);

    shedEveryWindow(region, 1);
    assertEquals(List.of("held 1 node-1"), changes);
    assertEquals(List.of(NodeId.of(0), NodeId.of(1)), regions.holders(1));
    assertEquals(1, events.named("holder.added", "replica").size());
  }

  @Test
  void aHotRegionWithNoSpareStaysAsItIs() {
    start(1, 1, 0);
    shedEveryWindow(regions.region(1), 10);

    assertEquals(List.of(), changes);
    assertEquals(List.of(NodeId.of(0)), regions.holders(1));
  }

  @Test
  void aReplicaReturnsOnceItsRegionsLoadWouldFitOneHolderFewerAtHalfItsCapacity() {
    start(1, 3, 2);
    Region region = regions.region(1);
    shedEveryWindow(region, HOT / Placement.WINDOW_TICKS);
    shedEveryWindow(region, HOT / Placement.WINDOW_TICKS);
    assertEquals(List.of(NodeId.of(0), NodeId.of(1), NodeId.of(2)), regions.holders(1));
    changes.clear();

    // Two holders fewer would fit, but only one returns at a time, the newest.
    int lowLoad =
        (int) (Placement.COLD_SHARE * Priority.MIN_SHARE * CAPACITY * Placement.WINDOW_TICKS);
    sendEveryWindow(region, lowLoad, COLD / Placement.WINDOW_TICKS - 1);
    assertEquals(List.of(), changes);
    sendEveryWindow(region, lowLoad, 1);
    assertEquals(List.of("released 1 node-2"), changes);

    // Half a holder's capacity and one more request is not cold for the one replica left.
    sendEveryWindow(region, lowLoad + 1, 2 * COLD / Placement.WINDOW_TICKS);
    assertEquals(List.of(NodeId.of(0), NodeId.of(1)), regions.holders(1));
    sendEveryWindow(region, lowLoad, COLD / Placement.WINDOW_TICKS);
    assertEquals(List.of(NodeId.of(0)), regions.holders(1));
    assertEquals(2, events.named("holder.removed", "return").size());
  }

  @Test
  void aColdRegionReturnsAHolderThatIsDownBeforeItsNewestOne() {
    start(1, 2, 1);
    Region region = regions.region(1);
    shedEveryWindow(region, HOT / Placement.WINDOW_TICKS);
    assertEquals(List.of(NodeId.of(0), NodeId.of(1)), regions.holders(1));
    changes.clear();

    // The first holder dies with no spare to take its place, and its mobs go to the replica.
    links.disconnected(NodeId.of(0));
    sendEveryWindow(region, 0, COLD / Placement.WINDOW_TICKS);

    assertEquals(List.of("released 1 node-0"), changes);
    assertEquals(List.of(NodeId.of(1)), regions.holders(1));
  }

  @Test
  void aHolderReturnedWhileDownIsAPlainSpareOnceBack() {
    start(1, 2, 1);
    NodeId first = NodeId.of(0);
    NodeId replica = NodeId.of(1);
    Region region = regions.region(1);
    shedEveryWindow(region, HOT / Placement.WINDOW_TICKS);
    links.disconnected(first);
    sendEveryWindow(region, 0, COLD / Placement.WINDOW_TICKS);
    assertEquals(List.of(replica), regions.holders(1));
    changes.clear();

    // Its time down ended with its region, so its grace running out moves nothing.
    run(2 * GRACE);
    assertEquals(List.of(), changes);

    // Back, it takes the region of the next holder down past its grace.
    links.greeted(first, 0);
    run(1);
    links.disconnected(replica);
    run(GRACE);
    assertEquals(List.of(), changes);
    run(1);
    assertEquals(List.of("released 1 node-1", "held 1 node-0"), changes);
    assertEquals(List.of(), topology());
  }

  @Test
  void aHotRegionLeavesASpareForEachHolderThatIsDown() {
    start(2, 3, 1);
    Region hot = regions.region(2);
    shedEveryWindow(hot, HOT / Placement.WINDOW_TICKS - 1);

    // The holder of region 1 goes down just before region 2 turns hot. It is still within its
    // grace then, but needs the one spare once the grace runs out.
    links.disconnected(NodeId.of(0));
    shedEveryWindow(hot, 1);
    assertEquals(List.of(), changes);

    run(GRACE);
    assertEquals(List.of("released 1 node-0", "held 1 node-2"), changes);
    assertEquals(List.of(), events.named("region.removed", "split"));
  }

  @Test
  void aRegionLeftWithNoHolderUpTakesTheNewestReplicaOfAnotherRegion() {
    start(2, 5, 1);
    shedEveryWindow(regions.region(1), HOT / Placement.WINDOW_TICKS);
    assertEquals(List.of(NodeId.of(0), NodeId.of(4)), regions.holders(1));
    changes.clear();

    // No spare is left when the holder of region 2 dies.
    links.disconnected(NodeId.of(1));
    run(GRACE);
    assertEquals(List.of(), changes);
    run(1);

    assertEquals(List.of("released 1 node-4", "released 2 node-1", "held 2 node-4"), changes);
    assertEquals(List.of(NodeId.of(0)), regions.holders(1));
    assertEquals(1, events.named("holder.added", "reclaim").size());
  }

  @Test
  void aDownReplicaOfARegionStillServedKeepsItsPlaceWithNoSpare() {
    start(1, 3, 2);
    Region region = regions.region(1);
    shedEveryWindow(region, HOT / Placement.WINDOW_TICKS);
    shedEveryWindow(region, HOT / Placement.WINDOW_TICKS);
    changes.clear();

    // Busy enough that no replica returns, past the dead replica's grace.
    links.disconnected(NodeId.of(2));
    int busy =
        (int) (Placement.COLD_SHARE * Priority.MIN_SHARE * 2 * CAPACITY * Placement.WINDOW_TICKS)
            + 1;
    sendEveryWindow(region, busy, 2 * GRACE / Placement.WINDOW_TICKS + 1);

    assertEquals(List.of(), changes);
    assertEquals(List.of(NodeId.of(0), NodeId.of(1), NodeId.of(2)), regions.holders(1));
  }

  @Test
  void aReplicaStaysWhileItsLoadWouldNotFitTheRateItsHoldersSearchAt() {
    start(1, 2, 1);
    Region region = regions.region(1);
    shedEveryWindow(region, HOT / Placement.WINDOW_TICKS);
    assertEquals(List.of(NodeId.of(0), NodeId.of(1)), regions.holders(1));
    changes.clear();
    // The holder that would stay searches at one request a tick, against the four assumed.
    searchAt(NodeId.of(0), 1);

    // Ten a window would fit the assumed capacity at a quarter, but not the measured one.
    sendEveryWindow(region, 10, 2 * COLD / Placement.WINDOW_TICKS);
    assertEquals(List.of(), changes);
    int fits = (int) (Placement.COLD_SHARE * Priority.MIN_SHARE * Placement.WINDOW_TICKS);
    sendEveryWindow(region, fits, COLD / Placement.WINDOW_TICKS);
    assertEquals(List.of("released 1 node-1"), changes);
  }

  @Test
  void aReplicaReturnsAtLightLoadWhenItsHoldersSearchFast() {
    start(1, 2, 1);
    Region region = regions.region(1);
    shedEveryWindow(region, HOT / Placement.WINDOW_TICKS);
    changes.clear();
    // Fast searches measure above the assumed capacity, which caps the rate.
    searchAt(NodeId.of(0), 10 * CAPACITY);
    assertEquals(CAPACITY, capacity.of(NodeId.of(0)));

    int light =
        (int) (Placement.COLD_SHARE * Priority.MIN_SHARE * CAPACITY * Placement.WINDOW_TICKS);
    sendEveryWindow(region, light, COLD / Placement.WINDOW_TICKS);
    assertEquals(List.of("released 1 node-1"), changes);
  }

  @Test
  void theStartingLayoutIsReportedAtOnceAndTheNodesUpAtTheFirstEvaluation() {
    start(2, 2, 1);
    assertEquals(
        List.of(
            "0 region.added " + bounds(regions.region(1)) + " reason=initial",
            "0 holder.added region=1 node=node-0 reason=initial"),
        reported(-1));

    run(3);
    // Up from the start, so the last check each failed is the link a new controller had not seen.
    assertEquals(
        List.of("1 node.up node=node-0 reason=linked", "1 node.up node=node-1 reason=linked"),
        reported(0));
    assertEquals(1, metrics.gauge("region.holder", "run", "1", "region", "1", "node", "node-0"));
    assertEquals(1, metrics.gauge("node.up", "node", "node-1"));
    assertEquals(List.of(), topology());
  }

  @Test
  void aHandoffIsReportedAfterTheHolderWentDown() {
    start(1, 2, 1);
    run(1);
    links.disconnected(NodeId.of(0));
    run(GRACE + 1);

    long moved = 2 + GRACE;
    assertEquals(
        List.of(
            "2 node.down node=node-0 reason=not_linked",
            moved + " holder.removed region=1 node=node-0 reason=handoff",
            moved + " holder.added region=1 node=node-1 reason=handoff"),
        reported(1));
    assertEquals(0, metrics.gauge("node.up", "node", "node-0"));
    assertEquals(0, metrics.gauge("region.holder", "run", "1", "region", "1", "node", "node-0"));
    assertEquals(1, metrics.gauge("region.holder", "run", "1", "region", "1", "node", "node-1"));
    assertEquals(List.of(), topology());
  }

  @Test
  void aCutIsReportedAsTheRegionReplacedByItsSides() {
    start(2, 2, 1);
    Region whole = regions.region(1);
    shedEveryWindow(whole, HOT / Placement.WINDOW_TICKS);

    // The cut comes as the last window of the hot time closes.
    String at = HOT + " ";
    assertEquals(
        List.of(
            at + "holder.removed region=1 node=node-0 reason=split",
            at + "region.removed " + bounds(whole) + " reason=split",
            at + "region.added " + bounds(regions.region(2)) + " reason=split",
            at + "region.added " + bounds(regions.region(3)) + " reason=split",
            at + "holder.added region=2 node=node-0 reason=split",
            at + "holder.added region=3 node=node-1 reason=split"),
        reported(1));
    assertEquals(0, metrics.gauge("region.holder", "run", "1", "region", "1", "node", "node-0"));
    assertEquals(List.of(), topology());
  }

  @Test
  void eachHolderChangeIsReportedWithTheReasonForIt() {
    start(2, 5, 1);
    shedEveryWindow(regions.region(1), HOT / Placement.WINDOW_TICKS);
    assertEquals(List.of("holder.added 1 node-4 replica"), holderChanges());

    // No spare is left when the holder of region 2 dies, so region 1's replica takes it.
    links.disconnected(NodeId.of(1));
    run(GRACE + 1);
    assertEquals(
        List.of(
            "holder.added 1 node-4 replica",
            "holder.removed 1 node-4 reclaim",
            "holder.removed 2 node-1 reclaim",
            "holder.added 2 node-4 reclaim"),
        holderChanges());
    assertEquals(List.of(), topology());
  }

  @Test
  void aReplicaReturningIsReportedWithItsReason() {
    start(1, 2, 1);
    Region region = regions.region(1);
    shedEveryWindow(region, HOT / Placement.WINDOW_TICKS);
    sendEveryWindow(region, 0, COLD / Placement.WINDOW_TICKS);

    assertEquals(
        List.of("holder.added 1 node-1 replica", "holder.removed 1 node-1 return"),
        holderChanges());
    assertEquals(List.of(), topology());
  }

  @Test
  void aNodeGoesDownForTheFirstCheckItFailsAndComesUpForTheLastOne() {
    start(1, 2, 1);
    NodeId spare = NodeId.of(1);
    run(1);

    // Its link drops, and the next one has yet to show its request path works.
    links.disconnected(spare);
    health.disconnected(spare);
    run(1);
    links.greeted(spare, 0);
    run(1);
    health.answered(spare, tick);
    run(1);

    // Its request path goes quiet, which also ends its verification, and an answer brings it back.
    health.missed(spare, tick);
    health.missed(spare, tick);
    health.evaluate(tick + NodeHealth.QUIET_TICKS, 0);
    run(1);
    health.answered(spare, tick);
    run(1);

    assertEquals(
        List.of(
            "2 node.down node=node-1 reason=not_linked",
            "4 node.up node=node-1 reason=verified",
            "5 node.down node=node-1 reason=not_in_service",
            "6 node.up node=node-1 reason=in_service"),
        reported(1));
    assertEquals(List.of(), topology());
  }

  @Test
  void aPolicyIsWholeWindows() {
    assertThrows(IllegalArgumentException.class, () -> new Placement.Policy(0, HOT, COLD));
    assertThrows(IllegalArgumentException.class, () -> new Placement.Policy(GRACE, 30, COLD));
    assertThrows(IllegalArgumentException.class, () -> new Placement.Policy(GRACE, HOT, 0));
  }

  /** Runs {@code windows} whole windows in each of which the region sheds a request per cell. */
  private void shedEveryWindow(Region region, int windows) {
    for (int i = 0; i < windows; i++) {
      for (int cx = 0; cx < region.sizeX() / RegionLayout.CELL; cx++) {
        for (int cz = 0; cz < region.sizeZ() / RegionLayout.CELL; cz++) {
          placement.shed(region, cell(region, cx, cz));
        }
      }
      run(Placement.WINDOW_TICKS);
    }
  }

  /** Runs {@code windows} whole windows in each of which the region sends {@code count}. */
  private void sendEveryWindow(Region region, int count, int windows) {
    for (int i = 0; i < windows; i++) {
      for (int j = 0; j < count; j++) {
        placement.sent(region, cell(region, 0, 0));
      }
      run(Placement.WINDOW_TICKS);
    }
  }

  /** The middle of cell {@code (cx, cz)} of the region. */
  private static Pos cell(Region region, int cx, int cz) {
    int half = RegionLayout.CELL / 2;
    return new Pos(
        region.minX() + cx * RegionLayout.CELL + half,
        64,
        region.minZ() + cz * RegionLayout.CELL + half);
  }

  /** The node's heartbeats show one worker searching at {@code perTick} requests a tick. */
  private void searchAt(NodeId node, int perTick) {
    long searches = Capacity.MIN_SEARCHES;
    capacity.heartbeat(node, searches, searches * Capacity.TICK_MICROS / perTick, 1);
  }

  /** The run's events, replayed, against the regions, the nodes up and the gauges now. */
  private List<String> topology() {
    Set<NodeId> up = new LinkedHashSet<>();
    for (NodeId node : fleet) {
      if (placement.up(node)) {
        up.add(node);
      }
    }
    return events.checkTopology(RUN, regions, fleet, up, metrics);
  }

  /** The run's events after {@code tick}, each as its tick, name and remaining attributes. */
  private List<String> reported(long tick) {
    List<String> lines = new ArrayList<>();
    for (SimEvents.Event event : events.of(RUN)) {
      if (Long.parseLong(event.get("tick")) <= tick) {
        continue;
      }
      StringBuilder line = new StringBuilder(event.get("tick")).append(' ').append(event.name());
      event
          .attributes()
          .forEach(
              (key, value) -> {
                if (!key.equals("run") && !key.equals("tick")) {
                  line.append(' ').append(key).append('=').append(value);
                }
              });
      lines.add(line.toString());
    }
    return lines;
  }

  /** The holder changes reported after the starting layout, as name, region, node and reason. */
  private List<String> holderChanges() {
    List<String> lines = new ArrayList<>();
    for (SimEvents.Event event : events.of(RUN)) {
      if (event.name().startsWith("holder.") && !event.get("reason").equals("initial")) {
        lines.add(
            String.join(
                " ", event.name(), event.get("region"), event.get("node"), event.get("reason")));
      }
    }
    return lines;
  }

  private static String bounds(Region region) {
    return "region="
        + region.id()
        + " min_x="
        + region.minX()
        + " min_z="
        + region.minZ()
        + " size_x="
        + region.sizeX()
        + " size_z="
        + region.sizeZ();
  }

  /** Evaluates the next {@code ticks} ticks. */
  private void run(int ticks) {
    for (int i = 0; i < ticks; i++) {
      tick++;
      placement.evaluate(tick);
    }
  }

  private final class Recorder implements Placement.Changes {

    @Override
    public void regionSplit(Region parent, Region lower, Region upper) {
      changes.add(
          "split " + parent.id() + " into " + lower.id() + " " + regions.holders(lower.id()));
    }

    @Override
    public void holderAdded(Region region, NodeId node) {
      changes.add("held " + region.id() + " " + node);
    }

    @Override
    public void holderRemoved(Region region, NodeId node) {
      changes.add("released " + region.id() + " " + node);
    }
  }

  /** An environment for links that are never dialed or sent on. */
  private ControllerEnv env() {
    Clock clock =
        new Clock() {
          @Override
          public long tick() {
            return tick;
          }

          @Override
          public long nowNanos() {
            return tick;
          }
        };
    Offload offload =
        new Offload() {
          @Override
          public <T> void submit(
              String name, Lane lane, Supplier<T> work, Consumer<T> completion, Runnable failure) {
            completion.accept(work.get());
          }
        };
    Transport transport =
        new Transport() {
          @Override
          public void send(NodeId node, Channel channel, ByteBuffer frame) {}

          @Override
          public void connect(NodeId node) {}

          @Override
          public void listen(Listener listener) {}
        };
    Tracing tracing = trace -> {};
    return new ControllerEnv(
        clock, SeededRng.of(1), offload, transport, metrics, tracing, events, RUN);
  }
}
