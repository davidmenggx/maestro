package io.github.davidmenggx.maestro.controller.world;

import java.util.Objects;

/**
 * Everything the controller learns about the world. Production emits these from mixins and the
 * simulator replays them from a trace or generator. Events arrive on the server main thread in tick
 * order.
 *
 * <p>Mob and player positions are reported only where the controller needs them. A request carries
 * its own start, so per-tick movement does not cross the boundary.
 */
public sealed interface WorldEvent {

  /** Tick the event belongs to. */
  long tick();

  record TickBegan(long tick) implements WorldEvent {
    public TickBegan {
      requireTick(tick);
    }
  }

  /** End of the tick's world mutation. Batched sends are flushed here. */
  record TickEnded(long tick) implements WorldEvent {
    public TickEnded {
      requireTick(tick);
    }
  }

  /**
   * {@code pathType} is {@code getPathTypeFromState} for the new block state (the per-block type
   * only), as an ordinal of the shared {@code PathType} enum. {@code floor} is the top of the
   * block's collision shape in sixteenths, rounded up, or 0 if it has none. One event per block
   * change.
   */
  record BlockChanged(long tick, Pos pos, byte pathType, int floor) implements WorldEvent {
    public BlockChanged {
      requireTick(tick);
      Objects.requireNonNull(pos, "pos");
      if (!PathTypes.isPerBlock(pathType)) {
        throw new IllegalArgumentException(
            "a cell carries per-block path types only, got " + PathTypes.name(pathType));
      }
      if (floor < 0 || floor > Cells.MAX_FLOOR) {
        throw new IllegalArgumentException("floor is sixteenths of a block in a byte: " + floor);
      }
    }
  }

  /** The chunk's state is sent to its owning node at the end of the tick. */
  record ChunkLoaded(long tick, int chunkX, int chunkZ) implements WorldEvent {
    public ChunkLoaded {
      requireTick(tick);
    }
  }

  /** The owning node is sent the chunk as cleared and treats it as air until it is loaded again. */
  record ChunkUnloaded(long tick, int chunkX, int chunkZ) implements WorldEvent {
    public ChunkUnloaded {
      requireTick(tick);
    }
  }

  record MobSpawned(long tick, int mobId, Pos pos) implements WorldEvent {
    public MobSpawned {
      requireTick(tick);
      Objects.requireNonNull(pos, "pos");
    }
  }

  record MobRemoved(long tick, int mobId) implements WorldEvent {
    public MobRemoved {
      requireTick(tick);
    }
  }

  /**
   * The inputs of one {@code PathFinder.findPath} call that vary per call, not per mob: the
   * distance that counts as arrival, the two search bounds, and {@code Mob.getMaxFallDistance},
   * which depends on the mob's target, health and the difficulty.
   */
  record Search(int reachRange, float maxPathLength, int maxVisitedNodes, int maxFallDistance) {

    /** Largest reach range the wire carries. */
    public static final int MAX_REACH_RANGE = 0xFF;

    /** Largest visit budget the wire carries. */
    public static final int MAX_VISITED_NODES = 0xFFFF;

    /** Largest fall distance the wire carries. */
    public static final int MAX_FALL_DISTANCE = 0xFFFF;

    public Search {
      if (reachRange < 0 || reachRange > MAX_REACH_RANGE) {
        throw new IllegalArgumentException("reach range out of range: " + reachRange);
      }
      if (Float.isNaN(maxPathLength) || maxPathLength <= 0) {
        throw new IllegalArgumentException("path length bound must be positive: " + maxPathLength);
      }
      if (maxVisitedNodes < 1 || maxVisitedNodes > MAX_VISITED_NODES) {
        throw new IllegalArgumentException("visit budget out of range: " + maxVisitedNodes);
      }
      if (maxFallDistance < 0 || maxFallDistance > MAX_FALL_DISTANCE) {
        throw new IllegalArgumentException("fall distance out of range: " + maxFallDistance);
      }
    }
  }

  /**
   * One A* request. {@code start} is the start node the evaluator resolved on the server, not the
   * mob's position. {@code classId} names the mob's current profile, not one fixed at spawn. The
   * controller assigns the request id, so a repeat request for the same mob replaces the
   * outstanding one.
   */
  record PathRequested(long tick, int mobId, short classId, Pos start, Pos target, Search search)
      implements WorldEvent {
    public PathRequested {
      requireTick(tick);
      Objects.requireNonNull(start, "start");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(search, "search");
      if (classId < 0) {
        throw new IllegalArgumentException("class id must not be negative: " + classId);
      }
    }
  }

  /**
   * The mob no longer wants a path because it lost its target or stopped hunting. Frees fleet
   * capacity that would be spent on an answer nobody reads.
   */
  record PathCanceled(long tick, int mobId) implements WorldEvent {
    public PathCanceled {
      requireTick(tick);
    }
  }

  /** Used for shedding and failover priorities, which both order mobs by distance to a player. */
  record PlayerMoved(long tick, int playerId, Pos pos) implements WorldEvent {
    public PlayerMoved {
      requireTick(tick);
      Objects.requireNonNull(pos, "pos");
    }
  }

  record PlayerLeft(long tick, int playerId) implements WorldEvent {
    public PlayerLeft {
      requireTick(tick);
    }
  }

  private static void requireTick(long tick) {
    if (tick < 0) {
      throw new IllegalArgumentException("tick must not be negative: " + tick);
    }
  }
}
