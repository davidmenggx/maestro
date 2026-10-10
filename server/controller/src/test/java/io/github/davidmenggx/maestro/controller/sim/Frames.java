package io.github.davidmenggx.maestro.controller.sim;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.Arrays;
import java.util.List;
import maestro.fbs.CellDelta;
import maestro.fbs.CellRun;
import maestro.fbs.ChunkSnapshot;
import maestro.fbs.ControlFrame;
import maestro.fbs.ControlMsg;
import maestro.fbs.PathRequest;
import maestro.fbs.PathRequestBatch;
import maestro.fbs.RegionAssignment;
import maestro.fbs.RequestFrame;
import maestro.fbs.RequestMsg;
import maestro.fbs.Vec2i;
import maestro.fbs.Vec3s;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;

/**
 * Everything the controller puts on the wire, encoded with the generated bindings. Separate from
 * the controller so a test can build the same frames a run would, and so the encoding is in one
 * place.
 *
 * <p>The generated setters take an {@code int} and cast it to the field's width without checking,
 * so any value not already range-checked at the boundary must go through {@link #int16} or {@link
 * #uint16} first.
 */
public final class Frames {

  private Frames() {}

  /** One path request, held by the controller until the tick is flushed. */
  public record Request(
      long requestId, short classId, Pos start, Pos target, WorldEvent.Search search) {}

  /** Columns in one chunk, indexed `(x - minX) * Chunks.SIZE + (z - minZ)`. */
  static final int COLUMNS_PER_CHUNK = Chunks.SIZE * Chunks.SIZE;

  /**
   * One chunk's columns, run-length encoded. Each run is packed into one int as {@code (length <<
   * 16) | (floor << 8) | pathType}, which avoids allocating an object per run.
   */
  public static final class ChunkRuns {

    private final int[] runsPerColumn = new int[COLUMNS_PER_CHUNK];
    // Flat terrain has two runs per column, so most chunks end up this size.
    private int[] packedRuns = new int[COLUMNS_PER_CHUNK * 2];
    private int runCount;

    private void add(int length, int cell) {
      if (runCount == packedRuns.length) {
        packedRuns = Arrays.copyOf(packedRuns, runCount * 2);
      }
      packedRuns[runCount++] = (uint16(length) << 16) | cell;
    }
  }

  /**
   * Reads one chunk from the world. The world can only be read on the main thread, so this is
   * separate from the encoding, which runs on another thread.
   */
  public static ChunkRuns readChunk(WorldSource world, int chunkX, int chunkZ) {
    int minX = chunkX * Chunks.SIZE;
    int minZ = chunkZ * Chunks.SIZE;
    int minY = world.minY();
    int height = world.height();
    ChunkRuns runs = new ChunkRuns();
    for (int dx = 0; dx < Chunks.SIZE; dx++) {
      for (int dz = 0; dz < Chunks.SIZE; dz++) {
        int closedRuns = 0;
        int runLength = 0;
        int runCell = 0;
        for (int dy = 0; dy < height; dy++) {
          int x = minX + dx;
          int y = minY + dy;
          int z = minZ + dz;
          int cell = (world.floorAt(x, y, z) << 8) | (world.pathTypeAt(x, y, z) & 0xFF);
          if (runLength > 0 && cell == runCell) {
            runLength++;
            continue;
          }
          if (runLength > 0) {
            runs.add(runLength, runCell);
            closedRuns++;
          }
          runCell = cell;
          runLength = 1;
        }
        runs.add(runLength, runCell);
        runs.runsPerColumn[dx * Chunks.SIZE + dz] = closedRuns + 1;
      }
    }
    return runs;
  }

  /** {@code chunkX} and {@code chunkZ} are indices within the region, not world coordinates. */
  public static byte[] chunkSnapshot(long sequence, int chunkX, int chunkZ, ChunkRuns runs) {
    FlatBufferBuilder builder = new FlatBufferBuilder(4096);
    int runCountsVector = ChunkSnapshot.createRunCountsVector(builder, runs.runsPerColumn);
    ChunkSnapshot.startRunsVector(builder, runs.runCount);
    for (int i = runs.runCount - 1; i >= 0; i--) {
      int run = runs.packedRuns[i];
      CellRun.createCellRun(builder, run >>> 16, run & 0xFF, (run >>> 8) & 0xFF);
    }
    int runsVector = builder.endVector();
    return chunkFrame(builder, sequence, chunkX, chunkZ, runCountsVector, runsVector);
  }

  /** A chunk the server no longer holds. The node treats it as air, as vanilla does. */
  public static byte[] chunkCleared(long sequence, int chunkX, int chunkZ) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    return chunkFrame(builder, sequence, chunkX, chunkZ, 0, 0);
  }

  private static byte[] chunkFrame(
      FlatBufferBuilder builder, long sequence, int chunkX, int chunkZ, int runCounts, int runs) {
    ChunkSnapshot.startChunkSnapshot(builder);
    ChunkSnapshot.addSequence(builder, sequence);
    ChunkSnapshot.addChunkXz(builder, Vec2i.createVec2i(builder, chunkX, chunkZ));
    if (runCounts != 0) {
      ChunkSnapshot.addRunCounts(builder, runCounts);
      ChunkSnapshot.addRuns(builder, runs);
    }
    int snapshot = ChunkSnapshot.endChunkSnapshot(builder);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.ChunkSnapshot, snapshot));
    return builder.sizedByteArray();
  }

  public static byte[] delta(
      long sequence, List<WorldEvent.BlockChanged> cells, RegionMap regions, int region) {
    int originX = regions.minX(region);
    int originZ = regions.minZ(region);
    FlatBufferBuilder builder = new FlatBufferBuilder(256);
    WorldDelta.startCellsVector(builder, cells.size());
    for (int i = cells.size() - 1; i >= 0; i--) {
      WorldEvent.BlockChanged cell = cells.get(i);
      Pos pos = cell.pos();
      CellDelta.createCellDelta(
          builder,
          int16(pos.x() - originX),
          int16(pos.y()),
          int16(pos.z() - originZ),
          cell.pathType(),
          cell.floor());
    }
    int vector = builder.endVector();
    int delta = WorldDelta.createWorldDelta(builder, sequence, vector);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.WorldDelta, delta));
    return builder.sizedByteArray();
  }

  public static byte[] requests(List<Request> batch, RegionMap regions, int region) {
    int originX = regions.minX(region);
    int originZ = regions.minZ(region);
    FlatBufferBuilder builder = new FlatBufferBuilder(512);
    int[] offsets = new int[batch.size()];
    for (int i = 0; i < batch.size(); i++) {
      Request request = batch.get(i);
      WorldEvent.Search search = request.search();
      PathRequest.startPathRequest(builder);
      PathRequest.addRequestId(builder, request.requestId());
      PathRequest.addClassId(builder, request.classId());
      PathRequest.addStart(builder, vec3(builder, request.start(), originX, originZ));
      PathRequest.addTarget(builder, vec3(builder, request.target(), originX, originZ));
      PathRequest.addReachRange(builder, search.reachRange());
      PathRequest.addMaxPathLength(builder, search.maxPathLength());
      PathRequest.addMaxVisitedNodes(builder, search.maxVisitedNodes());
      PathRequest.addMaxFallDistance(builder, search.maxFallDistance());
      offsets[i] = PathRequest.endPathRequest(builder);
    }
    int requestsVector = PathRequestBatch.createRequestsVector(builder, offsets);
    PathRequestBatch.startPathRequestBatch(builder);
    PathRequestBatch.addRequests(builder, requestsVector);
    int batchOffset = PathRequestBatch.endPathRequestBatch(builder);
    builder.finish(
        RequestFrame.createRequestFrame(builder, RequestMsg.PathRequestBatch, batchOffset));
    return builder.sizedByteArray();
  }

  public static byte[] assignment(
      RegionMap regions, int region, boolean resume, long run, WorldSource world) {
    FlatBufferBuilder builder = new FlatBufferBuilder(128);
    RegionAssignment.startRegionAssignment(builder);
    RegionAssignment.addRun(builder, run);
    RegionAssignment.addRegionId(builder, region);
    RegionAssignment.addSize(builder, uint16(regions.topology().regionSize()));
    RegionAssignment.addMinY(builder, world.minY());
    RegionAssignment.addHeight(builder, uint16(world.height()));
    RegionAssignment.addResume(builder, resume);
    int assignment = RegionAssignment.endRegionAssignment(builder);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.RegionAssignment, assignment));
    return builder.sizedByteArray();
  }

  public static byte[] mobClass(MobClass profile) {
    FlatBufferBuilder builder = new FlatBufferBuilder(512);
    builder.finish(
        ControlFrame.createControlFrame(builder, ControlMsg.MobClass, mobClass(builder, profile)));
    return builder.sizedByteArray();
  }

  private static int mobClass(FlatBufferBuilder builder, MobClass profile) {
    MobClass.Shape shape = profile.shape();
    MobClass.Abilities abilities = profile.abilities();
    int malus = maestro.fbs.MobClass.createMalusVector(builder, profile.malus());
    maestro.fbs.MobClass.startMobClass(builder);
    maestro.fbs.MobClass.addClassId(builder, profile.classId());
    maestro.fbs.MobClass.addWidth(builder, shape.width());
    maestro.fbs.MobClass.addHeight(builder, shape.height());
    maestro.fbs.MobClass.addMalus(builder, malus);
    maestro.fbs.MobClass.addMaxUpStep(builder, shape.maxUpStep());
    maestro.fbs.MobClass.addCanPassDoors(builder, abilities.canPassDoors());
    maestro.fbs.MobClass.addCanOpenDoors(builder, abilities.canOpenDoors());
    maestro.fbs.MobClass.addCanFloat(builder, abilities.canFloat());
    maestro.fbs.MobClass.addCanWalkOverFences(builder, abilities.canWalkOverFences());
    return maestro.fbs.MobClass.endMobClass(builder);
  }

  private static int vec3(FlatBufferBuilder builder, Pos pos, int originX, int originZ) {
    return Vec3s.createVec3s(
        builder, int16(pos.x() - originX), int16(pos.y()), int16(pos.z() - originZ));
  }

  /** {@code value} as a wire {@code short}. Throws if it does not fit instead of wrapping. */
  static short int16(int value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new IllegalArgumentException("does not fit a 16-bit wire field: " + value);
    }
    return (short) value;
  }

  /** {@code value} as a wire {@code ushort}. Throws if it does not fit instead of wrapping. */
  static int uint16(int value) {
    if (value < 0 || value > 0xFFFF) {
      throw new IllegalArgumentException("does not fit an unsigned 16-bit wire field: " + value);
    }
    return value;
  }
}
