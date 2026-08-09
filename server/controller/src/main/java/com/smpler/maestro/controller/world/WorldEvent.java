package com.smpler.maestro.controller.world;

/**
 * Everything the controller learns about the world, as data. Production emits these from mixins and
 * the simulator replays them from a trace or a generator, so both drive the controller down one
 * path. Events arrive on the server main thread, in tick order.
 */
public sealed interface WorldEvent {

  /** Tick the event belongs to. */
  long tick();

  record TickBegan(long tick) implements WorldEvent {}

  /** End of the tick's world mutation. Batched sends are flushed here. */
  record TickEnded(long tick) implements WorldEvent {}

  /**
   * {@code pathType} is {@code getPathTypeFromState} for the new block state: the per-block layer
   * only, as an ordinal of the shared {@code PathType} enum. Exactly one event per block change.
   */
  record BlockChanged(long tick, Pos pos, byte pathType) implements WorldEvent {}

  /** A region is only snapshottable once its chunks are resident. */
  record ChunkLoaded(long tick, int chunkX, int chunkZ) implements WorldEvent {}

  record ChunkUnloaded(long tick, int chunkX, int chunkZ) implements WorldEvent {}

  /** {@code classId} indexes the mob class table; a mob's class never changes. */
  record MobSpawned(long tick, int mobId, short classId, Pos pos) implements WorldEvent {}

  record MobRemoved(long tick, int mobId) implements WorldEvent {}

  /**
   * One A* request. The controller mints the request id, so a repeat for the same mob supersedes
   * the outstanding one rather than racing it.
   */
  record PathRequested(long tick, int mobId, Pos start, Pos target) implements WorldEvent {}

  /** Feeds the shedding and failover priorities, which are both distance-to-player ordered. */
  record PlayerMoved(long tick, int playerId, Pos pos) implements WorldEvent {}

  record PlayerLeft(long tick, int playerId) implements WorldEvent {}
}
