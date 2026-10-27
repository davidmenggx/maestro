package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.core.RequestTracker;
import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.Metrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Paths for mobs whose node cannot take their search now (down, or its region resent). A mob
 * following a path made in the last {@link #RECENT_TICKS} ticks keeps it. Any other gets a cheap
 * path at once (vanilla's search on {@link #CHEAP_SHARE} of its node budget) and waits for a full
 * search.
 *
 * <p>Full searches run on the server thread within {@link #BUDGET_NANOS} a tick: in the call while
 * no other mob waits, otherwise at the tick's end, in turns. A mob's turn is the tick it asked,
 * plus one per {@link #BLOCKS_PER_TICK} blocks from its nearest player. A search cannot stop part
 * way, so an overrun is paid back from later ticks. Server thread only.
 */
final class Failover {

  /** The server thread's time for full searches per tick, on average. */
  static final long BUDGET_NANOS = 1_000_000;

  /** A path made this recently is kept, as one a node's answer could still have been. */
  static final int RECENT_TICKS = RequestTracker.MAX_TICKS_LATE;

  /** The share of its node budget a cheap path's search gets. */
  static final float CHEAP_SHARE = 1f / 16;

  /** Blocks from the nearest player that count as one tick of waiting less. */
  static final int BLOCKS_PER_TICK = 16;

  /** The furthest a player counts as being, which is also the distance with no player online. */
  static final int MAX_COUNTED_BLOCKS = 1024;

  /** Ticks of budget a long overrun is paid back over at most. */
  private static final int MAX_DEBT_TICKS = 1000;

  /** What became of a mob's call, or of its wait for a full search. */
  enum Outcome {
    /** It kept a path made recently. */
    RECENT,
    /** Its full search ran in the call. */
    FULL,
    /** It got a cheap path and waits for a full search. */
    CHEAP,
    /** Its full search ran in its turn. */
    LATER,
    /** It stopped following its cheap path before its turn. */
    MOVED_ON
  }

  private record Waiting(long asked, Path cheap, BlockPos target, Supplier<Path> search) {}

  private record Turn(int mobId, long key, Waiting waiting, PathOffload.Navigator navigator) {}

  private final IntFunction<PathOffload.Navigator> navigators;
  private final Clock clock;
  private final LongSupplier nanos;
  private final Supplier<List<Vec3>> players;
  private final Metrics metrics;

  /** Mobs waiting for a full search, by id. */
  private final Map<Integer, Waiting> waiting = new LinkedHashMap<>();

  private final Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);

  private long budgetLeft = BUDGET_NANOS;
  private long budgetTick = Long.MIN_VALUE;

  /**
   * {@code navigators} finds a mob's navigation by its id, or null if the mob is gone. {@code
   * nanos} times the searches, and {@code players} gives where the players stand.
   */
  Failover(
      IntFunction<PathOffload.Navigator> navigators,
      Clock clock,
      LongSupplier nanos,
      Supplier<List<Vec3>> players,
      Metrics metrics) {
    this.navigators = navigators;
    this.clock = clock;
    this.nanos = nanos;
    this.players = players;
    this.metrics = metrics;
  }

  /** The path a call returns for a mob whose node cannot take its search. */
  Path serve(
      int mobId, PathOffload.Navigator navigator, BlockPos target, Searches.Vanilla vanilla) {
    Path current = navigator.path();
    if (current != null
        && !current.isDone()
        && !HeldPaths.isHeld(current)
        && HeldPaths.madeWithin(current, RECENT_TICKS)) {
      record(Outcome.RECENT);
      return current;
    }
    Waiting earlier = waiting.remove(mobId);
    if (waiting.isEmpty() && budgetLeft() > 0) {
      record(Outcome.FULL);
      return timed(vanilla::search);
    }
    Path cheap = vanilla.cheap(CHEAP_SHARE);
    record(Outcome.CHEAP);
    if (cheap != null) {
      long asked = earlier == null ? clock.tick() : earlier.asked();
      waiting.put(mobId, new Waiting(asked, cheap, target, vanilla.later()));
    }
    return cheap;
  }

  /** Runs the full searches whose turn it is, while the tick's budget lasts. */
  void tickEnding() {
    if (waiting.isEmpty()) {
      return;
    }
    List<Vec3> around = players.get();
    List<Turn> turns = new ArrayList<>();
    Iterator<Map.Entry<Integer, Waiting>> entries = waiting.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<Integer, Waiting> entry = entries.next();
      PathOffload.Navigator navigator = navigators.apply(entry.getKey());
      Waiting wait = entry.getValue();
      if (navigator == null || !follows(navigator.path(), wait.cheap())) {
        entries.remove();
        record(Outcome.MOVED_ON);
        continue;
      }
      long key =
          wait.asked() + (long) (blocksToNearest(navigator.position(), around) / BLOCKS_PER_TICK);
      turns.add(new Turn(entry.getKey(), key, wait, navigator));
    }
    turns.sort(Comparator.comparingLong(Turn::key).thenComparingInt(Turn::mobId));
    for (Turn turn : turns) {
      if (budgetLeft() <= 0) {
        break;
      }
      waiting.remove(turn.mobId());
      record(Outcome.LATER);
      Path path = timed(turn.waiting().search());
      if (path == null) {
        turn.navigator().stop();
      } else {
        turn.navigator().follow(path, turn.waiting().target());
      }
    }
    metrics.gauge("failover.waiting", waiting.size());
  }

  /** The mob no longer waits for a full search: it left, or something else served its call. */
  void forget(int mobId) {
    waiting.remove(mobId);
  }

  /** Whether the mob waits for a full search. */
  boolean waits(int mobId) {
    return waiting.containsKey(mobId);
  }

  long outcomes(Outcome outcome) {
    return outcomes.getOrDefault(outcome, 0L);
  }

  /** Counts of what became of the calls and waits so far, by name. */
  Map<String, Long> counts() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Outcome outcome : Outcome.values()) {
      counts.put("failover_" + outcome.name().toLowerCase(Locale.ROOT), outcomes(outcome));
    }
    return counts;
  }

  /** The navigation keeps a path with the same nodes as the one it was handed. */
  private static boolean follows(Path current, Path cheap) {
    return current != null && (current == cheap || current.sameAs(cheap));
  }

  private static double blocksToNearest(Vec3 at, List<Vec3> around) {
    double nearest = (double) MAX_COUNTED_BLOCKS * MAX_COUNTED_BLOCKS;
    for (Vec3 player : around) {
      nearest = Math.min(nearest, at.distanceToSqr(player));
    }
    return Math.sqrt(nearest);
  }

  private Path timed(Supplier<Path> search) {
    long started = nanos.getAsLong();
    try {
      return search.get();
    } finally {
      budgetLeft -= nanos.getAsLong() - started;
    }
  }

  /** The budget left this tick, refilled by a tick's worth for each tick since it was last read. */
  private long budgetLeft() {
    long tick = clock.tick();
    if (tick != budgetTick) {
      long ticks = budgetTick == Long.MIN_VALUE ? 1 : Math.min(tick - budgetTick, MAX_DEBT_TICKS);
      budgetLeft = Math.min(BUDGET_NANOS, budgetLeft + ticks * BUDGET_NANOS);
      budgetTick = tick;
    }
    return budgetLeft;
  }

  private void record(Outcome outcome) {
    outcomes.merge(outcome, 1L, Long::sum);
    metrics.count("path.failover", 1, "outcome", outcome.name());
  }
}
