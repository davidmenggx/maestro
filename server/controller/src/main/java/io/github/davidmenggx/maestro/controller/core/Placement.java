package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.Events;
import io.github.davidmenggx.maestro.controller.env.Metrics;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.world.Pos;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which node holds which region as the run goes on. A node is up when it is linked, in service, and
 * its request path has been shown to work. A spare is a node of the fleet that holds no region and
 * is up; spares are taken in fleet order.
 *
 * <ul>
 *   <li>A holder down for {@link Policy#graceTicks} hands its region to a spare, if one is up, and
 *       is a spare itself once it is back. Its time down counts from when it first went down, and
 *       starts over only once it has stayed up for {@link #STABLE_TICKS}. With no spare, a region
 *       left with no holder up takes the newest replica of another region that keeps a holder up.
 *       Otherwise the down holder keeps the region, so it can resume its stream when it returns.
 *   <li>A region that shed requests in every second of {@link Policy#hotTicks} takes a spare, but
 *       only while more spares are up than holders are down, which may need them. If it spans more
 *       than one cell, it is cut at the cell boundary that best balances the requests it saw while
 *       hot, its holders keeping the lower side and the spare taking the upper. If it is one cell,
 *       or no cut leaves the busier side at most {@link #FAIR_SHARE} of them, the spare becomes a
 *       replica.
 *   <li>A replicated region whose load would have fit one holder fewer, at half the load a holder
 *       takes before it sheds, for {@link Policy#coldTicks} returns a holder to the spares: one
 *       that is down, else the newest. A holder takes about {@link Priority#MIN_SHARE} of its
 *       {@link Capacity} before it sheds.
 * </ul>
 *
 * A new holder is sent its region afresh, so a mob of a changed region waits for its chunks. The
 * holders of a cut region keep what they hold of the lower side.
 *
 * <p>Every change to the regions, their holders and which nodes are up is reported as an event and
 * in the {@code region.info}, {@code region.holder} and {@code node.up} gauges, from the layout the
 * run starts with on. Replayed from the start, the events give the regions and holders now; the
 * gauges give them at any time, without the events before. The region gauges name the run, as each
 * run numbers its regions afresh.
 */
public final class Placement {

  /** Ticks of each judgment. A region is hot or cold for whole windows. */
  public static final int WINDOW_TICKS = 20;

  /** Share of the load the remaining holders take before shedding that a cold region stays in. */
  static final double COLD_SHARE = 0.5;

  /** Most of a hot region's requests the busier side of a cut may carry, else it is replicated. */
  static final double FAIR_SHARE = 2.0 / 3.0;

  /** Ticks a holder that went down must stay up before its time down starts over. */
  public static final int STABLE_TICKS = NodeHealth.QUIET_WINDOW_TICKS;

  private static final long NONE = Long.MIN_VALUE;

  /** How long each condition must last before the region changes, in ticks. */
  public record Policy(int graceTicks, int hotTicks, int coldTicks) {

    /** 5 s of a holder down, 10 s of shedding, 30 s of low load. */
    public static final Policy DEFAULT = new Policy(100, 200, 600);

    public Policy {
      if (graceTicks < 1
          || hotTicks < WINDOW_TICKS
          || hotTicks % WINDOW_TICKS != 0
          || coldTicks < WINDOW_TICKS
          || coldTicks % WINDOW_TICKS != 0) {
        throw new IllegalArgumentException(
            "a grace of at least a tick and whole windows of "
                + WINDOW_TICKS
                + " ticks: "
                + graceTicks
                + ", "
                + hotTicks
                + ", "
                + coldTicks);
      }
    }
  }

  /** Why a region or a holder came or went. */
  enum Reason {
    /** The layout the run starts with. */
    INITIAL,
    /** A spare took the region of a holder down past its grace. */
    HANDOFF,
    /** A replica left its region for one with no holder up. */
    RECLAIM,
    /** A spare joined a hot region that could not be cut. */
    REPLICA,
    /** A cold region returned a holder to the spares. */
    RETURN,
    /** A hot region was cut. */
    SPLIT;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** What a node must be to be up, in the order checked. */
  private enum Check {
    LINKED,
    IN_SERVICE,
    VERIFIED;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** What a region change must also change elsewhere, applied in the order called. */
  interface Changes {

    /**
     * {@code parent} was cut. Its holders hold {@code lower}, whose area has the parent's corner;
     * no node holds {@code upper} yet.
     */
    void regionSplit(Region parent, Region lower, Region upper);

    void holderAdded(Region region, NodeId node);

    void holderRemoved(Region region, NodeId node);
  }

  /** One region's load in the current window and how long its condition has lasted. */
  private static final class Load {
    private final Region region;
    private final int cellsZ;

    /** Requests sent or shed per cell in the current window, indexed {@code cx * cellsZ + cz}. */
    private final long[] window;

    /** The same, over every window of the current hot streak. */
    private final long[] streak;

    private int shed;
    private int sent;
    private int hotWindows;
    private int coldWindows;

    private Load(Region region) {
      this.region = region;
      this.cellsZ = region.sizeZ() / RegionLayout.CELL;
      this.window = new long[region.sizeX() / RegionLayout.CELL * cellsZ];
      this.streak = new long[window.length];
    }

    private void count(Pos start) {
      int cx = Math.floorDiv(start.x() - region.minX(), RegionLayout.CELL);
      int cz = Math.floorDiv(start.z() - region.minZ(), RegionLayout.CELL);
      int index = cx * cellsZ + cz;
      if (cx >= 0 && cz >= 0 && cz < cellsZ && index < window.length) {
        window[index]++;
      }
    }
  }

  /** A holder's spell down: since when, and since when it has been back up, if it is. */
  private static final class Episode {
    private final long downSince;
    private long upSince = NONE;

    private Episode(long downSince) {
      this.downSince = downSince;
    }
  }

  private final RegionMap regions;
  private final List<NodeId> fleet;
  private final NodeLinks links;
  private final NodeHealth health;
  private final Policy policy;
  private final Capacity capacity;
  private final Changes changes;
  private final Metrics metrics;
  private final Events events;

  /** The run, as its events and gauges name it. */
  private final String run;

  /** Holders down or not yet back for {@link #STABLE_TICKS}, in the order they went down. */
  private final Map<NodeId, Episode> episodes = new LinkedHashMap<>();

  /** By region id, in id order. */
  private final Map<Integer, Load> loads = new LinkedHashMap<>();

  /** Each node last reported down, with the check it failed at the last look. */
  private final Map<NodeId, Check> down = new LinkedHashMap<>();

  private int version;

  /** The tick being evaluated, or the one this was made at, which its events name. */
  private long tick;

  Placement(
      RegionMap regions,
      List<NodeId> fleet,
      NodeLinks links,
      NodeHealth health,
      Policy policy,
      Capacity capacity,
      Changes changes,
      ControllerEnv env) {
    this.regions = regions;
    this.fleet = List.copyOf(fleet);
    this.links = links;
    this.health = health;
    this.policy = policy;
    this.capacity = capacity;
    this.changes = changes;
    this.metrics = env.metrics();
    this.events = env.events();
    this.run = Long.toHexString(env.run());
    this.tick = env.clock().tick();
    for (Region region : regions.regions()) {
      loads.put(region.id(), new Load(region));
    }
    reportLayout();
  }

  /** Counts changes to the region map, so a reader can tell when to look again. */
  int version() {
    return version;
  }

  /**
   * A request starting at {@code start} was shed, as the region's holders' queues were too long.
   */
  void shed(Region region, Pos start) {
    Load load = loads.get(region.id());
    load.shed++;
    load.count(start);
  }

  /** A request starting at {@code start} was taken by a holder of the region. */
  void sent(Region region, Pos start) {
    Load load = loads.get(region.id());
    load.sent++;
    load.count(start);
  }

  /** Whether the node can take a region: linked, in service, and its request path shown to work. */
  boolean up(NodeId node) {
    return failing(node) == null;
  }

  /** The first check the node fails, or null if it is up. */
  private Check failing(NodeId node) {
    if (!links.greeted(node)) {
      return Check.LINKED;
    }
    if (!health.inService(node)) {
      return Check.IN_SERVICE;
    }
    return health.verified(node) ? null : Check.VERIFIED;
  }

  /** Judges every holder and region at the start of a tick, and changes what must change. */
  void evaluate(long tick) {
    this.tick = tick;
    reportUp();
    for (NodeId node : regions.nodes()) {
      Episode episode = episodes.get(node);
      if (!up(node)) {
        if (episode == null) {
          episodes.put(node, new Episode(tick));
        } else {
          episode.upSince = NONE;
        }
      } else if (episode != null) {
        if (episode.upSince == NONE) {
          episode.upSince = tick;
        } else if (tick - episode.upSince >= STABLE_TICKS) {
          episodes.remove(node);
        }
      }
    }
    for (Map.Entry<NodeId, Episode> entry : List.copyOf(episodes.entrySet())) {
      NodeId node = entry.getKey();
      if (regions.regionOf(node) == Regions.NONE) {
        episodes.remove(node);
        continue;
      }
      if (up(node) || tick - entry.getValue().downSince < policy.graceTicks()) {
        continue;
      }
      Region region = regions.region(regions.regionOf(node));
      NodeId spare = spare();
      if (spare != null) {
        episodes.remove(node);
        release(region, node, Reason.HANDOFF);
        hold(region, spare, Reason.HANDOFF);
        continue;
      }
      if (upHolders(region) > 0) {
        continue;
      }
      NodeId replica = reclaimable();
      if (replica == null) {
        break;
      }
      episodes.remove(node);
      release(regions.region(regions.regionOf(replica)), replica, Reason.RECLAIM);
      release(region, node, Reason.RECLAIM);
      hold(region, replica, Reason.RECLAIM);
    }
    if (tick > 0 && tick % WINDOW_TICKS == 0) {
      closeWindow();
    }
    for (Region region : List.copyOf(regions.regions())) {
      Load load = loads.get(region.id());
      List<NodeId> holders = regions.holders(region.id());
      if (load.coldWindows * WINDOW_TICKS >= policy.coldTicks() && holders.size() > 1) {
        load.coldWindows = 0;
        release(region, returning(holders), Reason.RETURN);
      }
    }
    for (Region region : List.copyOf(regions.regions())) {
      Load load = loads.get(region.id());
      if (load.hotWindows * WINDOW_TICKS < policy.hotTicks()) {
        continue;
      }
      NodeId spare = upSpares() > downHolders() ? spare() : null;
      if (spare == null) {
        break;
      }
      RegionLayout.Cut cut =
          RegionLayout.splittable(region) ? balancedCut(region, load.streak) : null;
      if (cut != null) {
        split(region, cut, spare);
      } else {
        load.hotWindows = 0;
        Arrays.fill(load.streak, 0);
        hold(region, spare, Reason.REPLICA);
      }
    }
  }

  /** Ends the window: each region's streaks grow or reset, and the counts start over. */
  private void closeWindow() {
    for (Map.Entry<Integer, Load> entry : loads.entrySet()) {
      Load load = entry.getValue();
      List<NodeId> holders = regions.holders(entry.getKey());
      if (load.shed > 0) {
        load.hotWindows++;
        for (int i = 0; i < load.window.length; i++) {
          load.streak[i] += load.window[i];
        }
      } else {
        load.hotWindows = 0;
        Arrays.fill(load.streak, 0);
      }
      boolean cold =
          holders.size() > 1
              && load.shed == 0
              && load.sent
                  <= COLD_SHARE * Priority.MIN_SHARE * remainingCapacity(holders) * WINDOW_TICKS;
      load.coldWindows = cold ? load.coldWindows + 1 : 0;
      Arrays.fill(load.window, 0);
      load.shed = 0;
      load.sent = 0;
    }
  }

  /** Requests a tick the holders serve without the one a cold region would return. */
  private double remainingCapacity(List<NodeId> holders) {
    NodeId leaving = returning(holders);
    double remaining = 0;
    for (NodeId node : holders) {
      remaining += node.equals(leaving) ? 0 : capacity.of(node);
    }
    return remaining;
  }

  /**
   * The cut of {@code region} that leaves its busier side the least of {@code load}, a count per
   * cell indexed {@code cx * cellsZ + cz}, or null if even that side carries more than {@link
   * #FAIR_SHARE} of it. Ties go to the cut nearest the middle, then across x. With no load, the
   * halving cut.
   */
  static RegionLayout.Cut balancedCut(Region region, long[] load) {
    int cellsX = region.sizeX() / RegionLayout.CELL;
    int cellsZ = region.sizeZ() / RegionLayout.CELL;
    long[] byX = new long[cellsX];
    long[] byZ = new long[cellsZ];
    long total = 0;
    for (int cx = 0; cx < cellsX; cx++) {
      for (int cz = 0; cz < cellsZ; cz++) {
        long count = load[cx * cellsZ + cz];
        byX[cx] += count;
        byZ[cz] += count;
        total += count;
      }
    }
    if (total == 0) {
      return RegionLayout.halving(region.sizeX(), region.sizeZ());
    }
    RegionLayout.Cut best = null;
    long bestBusier = Long.MAX_VALUE;
    int bestOffset = Integer.MAX_VALUE;
    for (boolean acrossX : new boolean[] {true, false}) {
      long[] line = acrossX ? byX : byZ;
      long lower = 0;
      for (int cells = 1; cells < line.length; cells++) {
        lower += line[cells - 1];
        long busier = Math.max(lower, total - lower);
        int offset = Math.abs(2 * cells - line.length);
        if (busier < bestBusier || (busier == bestBusier && offset < bestOffset)) {
          best = new RegionLayout.Cut(acrossX, cells);
          bestBusier = busier;
          bestOffset = offset;
        }
      }
    }
    return bestBusier <= FAIR_SHARE * total ? best : null;
  }

  /**
   * Cuts the region. Its holders keep the lower side, whose area lies inside theirs, and {@code
   * spare} is sent the upper one afresh.
   */
  private void split(Region region, RegionLayout.Cut cut, NodeId spare) {
    List<NodeId> holders = List.copyOf(regions.holders(region.id()));
    for (NodeId node : holders) {
      regions.unassign(node);
      reportHolder(region, node, false, Reason.SPLIT);
    }
    reportRegion(region, false, Reason.SPLIT);
    loads.remove(region.id());
    List<Region> sides = regions.split(region.id(), cut);
    for (Region side : sides) {
      loads.put(side.id(), new Load(side));
      reportRegion(side, true, Reason.SPLIT);
    }
    for (NodeId node : holders) {
      regions.assign(sides.get(0).id(), node);
      reportHolder(sides.get(0), node, true, Reason.SPLIT);
    }
    changes.regionSplit(region, sides.get(0), sides.get(1));
    hold(sides.get(1), spare, Reason.SPLIT);
    version++;
  }

  private void hold(Region region, NodeId node, Reason reason) {
    regions.assign(region.id(), node);
    reportHolder(region, node, true, reason);
    changes.holderAdded(region, node);
    version++;
  }

  private void release(Region region, NodeId node, Reason reason) {
    regions.unassign(node);
    reportHolder(region, node, false, reason);
    changes.holderRemoved(region, node);
    version++;
  }

  /**
   * Reports the layout the run starts with. Every node counts as down until it is found up, for
   * want of a link, as a new controller has none.
   */
  private void reportLayout() {
    for (NodeId node : fleet) {
      down.put(node, Check.LINKED);
      metrics.gauge("node.up", 0, "node", node.toString());
    }
    for (Region region : regions.regions()) {
      reportRegion(region, true, Reason.INITIAL);
      for (NodeId node : regions.holders(region.id())) {
        reportHolder(region, node, true, Reason.INITIAL);
      }
    }
  }

  /**
   * Reports each node that came up or went down since the last evaluation. A node goes down for the
   * first check it fails, as {@code not_<check>}, and comes up for the last one it failed.
   */
  private void reportUp() {
    for (NodeId node : fleet) {
      Check failing = failing(node);
      Check was = down.get(node);
      if (failing != null) {
        down.put(node, failing);
        if (was == null) {
          metrics.gauge("node.up", 0, "node", node.toString());
          report("node.down", "node", node.toString(), "reason", "not_" + failing.label());
        }
      } else if (was != null) {
        down.remove(node);
        metrics.gauge("node.up", 1, "node", node.toString());
        report("node.up", "node", node.toString(), "reason", was.label());
      }
    }
  }

  private void reportRegion(Region region, boolean exists, Reason reason) {
    String id = Integer.toString(region.id());
    String minX = Integer.toString(region.minX());
    String minZ = Integer.toString(region.minZ());
    String sizeX = Integer.toString(region.sizeX());
    String sizeZ = Integer.toString(region.sizeZ());
    metrics.gauge(
        "region.info",
        exists ? 1 : 0,
        "run",
        run,
        "region",
        id,
        "min_x",
        minX,
        "min_z",
        minZ,
        "size_x",
        sizeX,
        "size_z",
        sizeZ);
    report(
        exists ? "region.added" : "region.removed",
        "region",
        id,
        "min_x",
        minX,
        "min_z",
        minZ,
        "size_x",
        sizeX,
        "size_z",
        sizeZ,
        "reason",
        reason.label());
  }

  private void reportHolder(Region region, NodeId node, boolean holds, Reason reason) {
    String id = Integer.toString(region.id());
    metrics.gauge(
        "region.holder", holds ? 1 : 0, "run", run, "region", id, "node", node.toString());
    report(
        holds ? "holder.added" : "holder.removed",
        "region",
        id,
        "node",
        node.toString(),
        "reason",
        reason.label());
  }

  /** An event of this run at the tick being evaluated. */
  private void report(String name, String... attributes) {
    String[] named = new String[attributes.length + 4];
    named[0] = "run";
    named[1] = run;
    named[2] = "tick";
    named[3] = Long.toString(tick);
    System.arraycopy(attributes, 0, named, 4, attributes.length);
    events.event(name, named);
  }

  /** The first node of the fleet that holds no region and is up, or null. */
  private NodeId spare() {
    for (NodeId node : fleet) {
      if (regions.regionOf(node) == Regions.NONE && up(node)) {
        return node;
      }
    }
    return null;
  }

  private int upSpares() {
    int up = 0;
    for (NodeId node : fleet) {
      up += regions.regionOf(node) == Regions.NONE && up(node) ? 1 : 0;
    }
    return up;
  }

  /** Holders down, or back for less than {@link #STABLE_TICKS}. */
  private int downHolders() {
    int down = 0;
    for (NodeId node : regions.nodes()) {
      down += episodes.containsKey(node) || !up(node) ? 1 : 0;
    }
    return down;
  }

  private int upHolders(Region region) {
    int up = 0;
    for (NodeId node : regions.holders(region.id())) {
      up += up(node) ? 1 : 0;
    }
    return up;
  }

  /**
   * The newest holder that is up of the first region, in id order, that keeps another holder up
   * without it, or null.
   */
  private NodeId reclaimable() {
    for (Region region : regions.regions()) {
      NodeId newest = null;
      int up = 0;
      for (NodeId node : regions.holders(region.id())) {
        if (up(node)) {
          newest = node;
          up++;
        }
      }
      if (up > 1) {
        return newest;
      }
    }
    return null;
  }

  /**
   * The holder a cold region returns: one that is down or not yet back for {@link #STABLE_TICKS},
   * which serves least, else the newest.
   */
  private NodeId returning(List<NodeId> holders) {
    for (NodeId node : holders) {
      if (episodes.containsKey(node) || !up(node)) {
        return node;
      }
    }
    return holders.get(holders.size() - 1);
  }
}
