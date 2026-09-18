package io.github.davidmenggx.maestro.controller.world;

import java.util.Objects;

/**
 * Everything the controller learns about the world. Production emits these from mixins and the
 * simulator replays them from a trace or generator. Events arrive on the server main thread in tick
 * order.
 *
 * <p>Mob and player positions are only reported where the controller needs them: a request carries
 * its own start, so per-tick movement never crosses the boundary.
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
   * {@code pathType} is {@code getPathTypeFromState} for the new block state: the per-block layer
   * only, as an ordinal of the shared {@code PathType} enum. {@code floor} is the top of the
   * block's collision shape in sixteenths, 0 when it has none. Exactly one event per block change.
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

  /** A region is only snapshottable once its chunks are resident. */
  record ChunkLoaded(long tick, int chunkX, int chunkZ) implements WorldEvent {
    public ChunkLoaded {
      requireTick(tick);
    }
  }

  /** Its region's state is unknown from here until the chunk comes back. */
  record ChunkUnloaded(long tick, int chunkX, int chunkZ) implements WorldEvent {
    public ChunkUnloaded {
      requireTick(tick);
    }
  }

  /** {@code classId} indexes the mob class table; a mob's class never changes. */
  record MobSpawned(long tick, int mobId, short classId, Pos pos) implements WorldEvent {
    public MobSpawned {
      requireTick(tick);
      Objects.requireNonNull(pos, "pos");
      if (classId < 0) {
        throw new IllegalArgumentException("class id must not be negative: " + classId);
      }
    }
  }

  record MobRemoved(long tick, int mobId) implements WorldEvent {
    public MobRemoved {
      requireTick(tick);
    }
  }

  /**
   * The inputs of one {@code PathFinder.findPath} call that vary per call rather than per mob: how
   * close counts as arrival, and the two bounds on the search.
   */
  record Search(int reachRange, float maxPathLength, int maxVisitedNodes) {
    public Search {
      if (reachRange < 0) {
        throw new IllegalArgumentException("reach range must not be negative: " + reachRange);
      }
      if (!(maxPathLength > 0)) {
        throw new IllegalArgumentException("path length bound must be positive: " + maxPathLength);
      }
      if (maxVisitedNodes < 1) {
        throw new IllegalArgumentException("a search visits at least one node: " + maxVisitedNodes);
      }
    }
  }

  /**
   * One A* request. {@code start} is the start node the evaluator resolved on the server, not the
   * mob's position. The controller mints the request id, so a repeat for the same mob supersedes
   * the outstanding one rather than racing it.
   */
  record PathRequested(long tick, int mobId, Pos start, Pos target, Search search)
      implements WorldEvent {
    public PathRequested {
      requireTick(tick);
      Objects.requireNonNull(start, "start");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(search, "search");
    }
  }

  /**
   * The mob stopped wanting a path, having lost its target or stopped hunting. Frees fleet capacity
   * that would otherwise be spent on an answer nobody reads.
   */
  record PathCanceled(long tick, int mobId) implements WorldEvent {
    public PathCanceled {
      requireTick(tick);
    }
  }

  /** Feeds the shedding and failover priorities, which are both distance-to-player ordered. */
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
