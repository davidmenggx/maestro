package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.world.Pos;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How much of its holder's queue a request may wait behind, by how much its mob matters, within the
 * latency SLO. A request leaves {@link RequestTracker#SEND_TICKS} after it is asked, and its answer
 * reaches the server at the hand-over after it is served, so to meet the SLO it must be served
 * within {@link #QUEUE_TICKS} of leaving. The requests a holder serves in that time, less the
 * request itself, are the queue a hunting mob near a player may wait behind. The share shrinks with
 * distance from the nearest player and for a mob with no target, so the least important requests
 * are shed first.
 */
public final class Priority {

  /** The latency SLO: p99 below this many ticks. */
  public static final int SLO_TICKS = 3;

  /** Ticks after it leaves by which a request must be served for its answer to meet the SLO. */
  public static final double QUEUE_TICKS = SLO_TICKS - 1 - RequestTracker.SEND_TICKS;

  /** The least share, which a request has with no player in the world. */
  public static final double MIN_SHARE = 0.5;

  /** Blocks from the nearest player that take {@link #STEP_SHARE} off a request's share. */
  static final int BLOCKS_PER_STEP = 32;

  static final double STEP_SHARE = 1.0 / 12;

  /** Share taken off a mob that is not hunting. */
  static final double IDLE_SHARE = 1.0 / 6;

  /** Where each player stood when last reported, by id. */
  private final Map<Integer, Pos> players = new LinkedHashMap<>();

  void moved(int playerId, Pos pos) {
    players.put(playerId, pos);
  }

  void left(int playerId) {
    players.remove(playerId);
  }

  /** The share of its holder's queue a request starting at {@code start} may wait behind. */
  double share(Pos start, boolean hunting) {
    long nearest = Long.MAX_VALUE;
    for (Pos player : players.values()) {
      long dx = (long) player.x() - start.x();
      long dy = (long) player.y() - start.y();
      long dz = (long) player.z() - start.z();
      nearest = Math.min(nearest, dx * dx + dy * dy + dz * dz);
    }
    if (nearest == Long.MAX_VALUE) {
      return MIN_SHARE;
    }
    long steps = (long) (Math.sqrt((double) nearest) / BLOCKS_PER_STEP);
    return Math.max(MIN_SHARE, 1 - (hunting ? 0 : IDLE_SHARE) - steps * STEP_SHARE);
  }

  /**
   * The most requests that may wait ahead of one with {@code share} at a holder serving {@code
   * capacity} a tick. A holder too slow to serve one request in time still takes one with nothing
   * ahead, so its rate can be measured again.
   */
  public static double allowedAhead(double share, double capacity) {
    return Math.max(0, share * (QUEUE_TICKS * capacity - 1));
  }
}
