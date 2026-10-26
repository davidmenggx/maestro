package io.github.davidmenggx.maestro.controller.world;

import java.util.Objects;

/**
 * Everything the controller learns about the world, in tick order on the main thread. A request
 * carries its own start, so per-tick mob movement does not cross the boundary.
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
   * {@code pathType} is the {@code getPathTypeFromState} ordinal of the new block state. {@code
   * floor} is the top of its collision shape in sixteenths, rounded up, or 0 if none.
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
   * The {@code PathFinder.findPath} inputs that vary per call: the arrival distance, the two search
   * bounds, and {@code Mob.getMaxFallDistance}.
   */
  record Search(int reachRange, float maxPathLength, int maxVisitedNodes, int maxFallDistance) {

    /** Largest reach range the wire carries. */
    public static final int MAX_REACH_RANGE = 0xFF;

    /** Largest visit budget the wire carries. */
    public static final int MAX_VISITED_NODES = 0xFFFF;

    /** Largest fall distance the wire carries. */
    public static final int MAX_FALL_DISTANCE = 0xFFFF;

    /**
     * Largest path length bound taken, the follow range maximum. The search read distance derives
     * from it, and it bounds the size of an answer.
     */
    public static final float MAX_PATH_LENGTH = 2048f;

    public Search {
      if (!fits(reachRange, maxPathLength, maxVisitedNodes, maxFallDistance)) {
        throw new IllegalArgumentException(
            "not a search the wire carries: reach "
                + reachRange
                + ", length "
                + maxPathLength
                + ", visits "
                + maxVisitedNodes
                + ", fall "
                + maxFallDistance);
      }
    }

    /**
     * Whether each input fits its wire field and the path length bound is positive and at most
     * {@link #MAX_PATH_LENGTH}.
     */
    public static boolean fits(
        int reachRange, float maxPathLength, int maxVisitedNodes, int maxFallDistance) {
      return reachRange >= 0
          && reachRange <= MAX_REACH_RANGE
          && maxPathLength > 0
          && maxPathLength <= MAX_PATH_LENGTH
          && maxVisitedNodes >= 1
          && maxVisitedNodes <= MAX_VISITED_NODES
          && maxFallDistance >= 0
          && maxFallDistance <= MAX_FALL_DISTANCE;
    }
  }

  /**
   * One A* request. {@code start} is the start node the evaluator resolved, not the mob's position.
   * {@code classId} names the mob's current profile. {@code hunting} is whether the mob has a
   * target, which keeps it among the last shed. A repeat request for the same mob replaces the
   * outstanding one, even if the repeat is refused.
   */
  record PathRequested(
      long tick, int mobId, short classId, Pos start, Pos target, Search search, boolean hunting)
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
   * The mob no longer wants a path. An unsent request is never sent. A sent one is canceled at its
   * node, best effort, and still counts against the node until its answer is due.
   */
  record PathCanceled(long tick, int mobId) implements WorldEvent {
    public PathCanceled {
      requireTick(tick);
    }
  }

  /**
   * Where a player stands, reported about once a second. Shedding orders mobs by distance to one.
   */
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
