package io.github.davidmenggx.maestro.controller.wire;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.Arrays;
import java.util.List;
import maestro.fbs.CellDelta;
import maestro.fbs.CellRun;
import maestro.fbs.ChunkSnapshot;
import maestro.fbs.ControlFrame;
import maestro.fbs.ControlMsg;
import maestro.fbs.PathCancel;
import maestro.fbs.PathNode;
import maestro.fbs.PathRequest;
import maestro.fbs.PathRequestBatch;
import maestro.fbs.PathResponse;
import maestro.fbs.RegionAssignment;
import maestro.fbs.RequestFrame;
import maestro.fbs.RequestMsg;
import maestro.fbs.ResponseFrame;
import maestro.fbs.ResponseMsg;
import maestro.fbs.Vec2i;
import maestro.fbs.Vec3s;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;

/**
 * Every frame payload the controller puts on the wire, encoded with the generated bindings, so the
 * encoding is in one place. Positions are given in world coordinates with the corner {@code
 * (originX, originZ)} of the receiving node's area, and leave as the wire's relative positions.
 *
 * <p>The generated setters take an {@code int} and cast it to the field's width without checking,
 * so any value not already range-checked at the boundary must go through {@link #int16} or {@link
 * #uint16} first.
 *
 * <p>Each thread encodes with one builder, cleared before every frame, so its buffer grows to the
 * largest frame once instead of being allocated per frame. Only the finished bytes are copied out.
 * An encoder must not call another while it builds.
 */
public final class Frames {

  private static final ThreadLocal<FlatBufferBuilder> BUILDER =
      ThreadLocal.withInitial(() -> new FlatBufferBuilder(4096));

  /**
   * Largest payload the node reads from its world and control streams. Must match the node's {@code
   * net::MaxPayloadSize}. A chunk at the tallest world Minecraft allows, with every block in a run
   * of its own, is about 4 MiB.
   */
  public static final int MAX_FRAME_BYTES = 8 << 20;

  /**
   * Largest request frame. A request frame is one UDP datagram, and this fits the 1232 bytes of
   * payload that IPv6's minimum MTU guarantees, so a frame is never fragmented and one lost
   * fragment never loses a whole batch.
   */
  public static final int MAX_REQUEST_FRAME_BYTES = 1200;

  /**
   * Requests in one frame. A full frame of the largest requests fits {@link
   * #MAX_REQUEST_FRAME_BYTES}.
   */
  public static final int MAX_REQUESTS_PER_FRAME = 24;

  /** Request ids in one cancel frame. A full frame fits {@link #MAX_REQUEST_FRAME_BYTES}. */
  public static final int MAX_CANCELS_PER_FRAME = 128;

  /** Block changes in one delta. A delta of this many is 512 KiB, well below the frame limit. */
  public static final int MAX_CELLS_PER_DELTA = 1 << 16;

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
   * separate from the encoding, which runs on another thread. A section the source reports as
   * uniform is not read block by block, so the cost follows terrain complexity, not world height.
   */
  public static ChunkRuns readChunk(WorldSource world, int chunkX, int chunkZ) {
    int minX = chunkX * Chunks.SIZE;
    int minZ = chunkZ * Chunks.SIZE;
    int minY = world.minY();
    int height = world.height();
    int sections = height / WorldSource.SECTION_HEIGHT;
    int[] uniform = new int[sections];
    for (int section = 0; section < sections; section++) {
      int cell = world.uniformCell(chunkX, chunkZ, section);
      uniform[section] = cell == WorldSource.NOT_UNIFORM ? cell : cell(cell >> 8, (byte) cell);
    }
    ChunkRuns runs = new ChunkRuns();
    for (int dx = 0; dx < Chunks.SIZE; dx++) {
      for (int dz = 0; dz < Chunks.SIZE; dz++) {
        int x = minX + dx;
        int z = minZ + dz;
        int closedRuns = 0;
        int runLength = 0;
        int runCell = 0;
        for (int dy = 0; dy < height; ) {
          int section = dy / WorldSource.SECTION_HEIGHT;
          int cell;
          int length;
          if (section < sections && uniform[section] != WorldSource.NOT_UNIFORM) {
            cell = uniform[section];
            length = WorldSource.SECTION_HEIGHT;
          } else {
            int y = minY + dy;
            cell = cell(world.floorAt(x, y, z), world.pathTypeAt(x, y, z));
            length = 1;
          }
          dy += length;
          if (runLength > 0 && cell == runCell) {
            runLength += length;
            continue;
          }
          if (runLength > 0) {
            runs.add(runLength, runCell);
            closedRuns++;
          }
          runCell = cell;
          runLength = length;
        }
        runs.add(runLength, runCell);
        runs.runsPerColumn[dx * Chunks.SIZE + dz] = closedRuns + 1;
      }
    }
    return runs;
  }

  /** {@code chunkX} and {@code chunkZ} are indices within the area, not world coordinates. */
  public static byte[] chunkSnapshot(long sequence, int chunkX, int chunkZ, ChunkRuns runs) {
    FlatBufferBuilder builder = builder();
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
    FlatBufferBuilder builder = builder();
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

  /** At most {@link #MAX_CELLS_PER_DELTA} changes, so the frame stays under the node's limit. */
  public static byte[] delta(
      long sequence, List<WorldEvent.BlockChanged> cells, int originX, int originZ) {
    if (cells.size() > MAX_CELLS_PER_DELTA) {
      throw new IllegalArgumentException("a delta carries at most " + MAX_CELLS_PER_DELTA);
    }
    FlatBufferBuilder builder = builder();
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

  /** At most {@link #MAX_REQUESTS_PER_FRAME} requests, so the frame fits one datagram. */
  public static byte[] requests(List<Request> batch, int originX, int originZ) {
    if (batch.size() > MAX_REQUESTS_PER_FRAME) {
      throw new IllegalArgumentException(
          "a request frame carries at most " + MAX_REQUESTS_PER_FRAME);
    }
    FlatBufferBuilder builder = builder();
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

  /**
   * Requests the controller no longer wants, at most {@link #MAX_CANCELS_PER_FRAME}, so the frame
   * fits one datagram.
   */
  public static byte[] cancels(List<Long> requestIds) {
    if (requestIds.size() > MAX_CANCELS_PER_FRAME) {
      throw new IllegalArgumentException("a cancel frame carries at most " + MAX_CANCELS_PER_FRAME);
    }
    FlatBufferBuilder builder = builder();
    long[] ids = new long[requestIds.size()];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = requestIds.get(i);
    }
    int vector = PathCancel.createRequestIdsVector(builder, ids);
    int cancel = PathCancel.createPathCancel(builder, vector);
    builder.finish(RequestFrame.createRequestFrame(builder, RequestMsg.PathCancel, cancel));
    return builder.sizedByteArray();
  }

  /**
   * {@code size} is the side of the square area, a multiple of {@link Chunks#SIZE}. Every position
   * in the area must fit the wire's 16 bits, as the node requires. {@code sequence} is where the
   * region's stream stands, so its next frame is {@code sequence + 1}.
   */
  public static byte[] assignment(
      long run, int regionId, int size, long sequence, boolean resume, WorldSource world) {
    if (size <= 0 || size % Chunks.SIZE != 0 || size > Short.MAX_VALUE) {
      throw new IllegalArgumentException("an area is whole chunks a short can index: " + size);
    }
    int minY = world.minY();
    int height = world.height();
    if (height <= 0 || !fitsInt16(minY) || !fitsInt16((long) minY + height - 1)) {
      throw new IllegalArgumentException("a column a short cannot index: " + minY + " + " + height);
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("a stream position is never negative: " + sequence);
    }
    FlatBufferBuilder builder = builder();
    RegionAssignment.startRegionAssignment(builder);
    RegionAssignment.addRun(builder, run);
    RegionAssignment.addSequence(builder, sequence);
    RegionAssignment.addRegionId(builder, regionId);
    RegionAssignment.addSize(builder, size);
    RegionAssignment.addMinY(builder, minY);
    RegionAssignment.addHeight(builder, uint16(height));
    RegionAssignment.addResume(builder, resume);
    int assignment = RegionAssignment.endRegionAssignment(builder);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.RegionAssignment, assignment));
    return builder.sizedByteArray();
  }

  public static byte[] mobClass(MobClass profile) {
    FlatBufferBuilder builder = builder();
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

  /**
   * A node's answer. The controller never sends one, but recorded vanilla answers are stored in
   * this form so they compare directly with what a node returns.
   */
  public static byte[] response(
      long requestId,
      boolean reached,
      List<WorldOutput.Node> nodes,
      float cost,
      long worldSequence,
      int originX,
      int originZ) {
    FlatBufferBuilder builder = builder();
    PathResponse.startNodesVector(builder, nodes.size());
    for (int i = nodes.size() - 1; i >= 0; i--) {
      WorldOutput.Node node = nodes.get(i);
      Pos pos = node.pos();
      PathNode.createPathNode(
          builder,
          int16(pos.x() - originX),
          int16(pos.y()),
          int16(pos.z() - originZ),
          node.pathType());
    }
    int nodesVector = builder.endVector();
    PathResponse.startPathResponse(builder);
    PathResponse.addRequestId(builder, requestId);
    PathResponse.addReached(builder, reached);
    PathResponse.addNodes(builder, nodesVector);
    PathResponse.addCost(builder, cost);
    PathResponse.addWorldSequence(builder, worldSequence);
    int response = PathResponse.endPathResponse(builder);
    builder.finish(ResponseFrame.createResponseFrame(builder, ResponseMsg.PathResponse, response));
    return builder.sizedByteArray();
  }

  private static FlatBufferBuilder builder() {
    FlatBufferBuilder builder = BUILDER.get();
    builder.clear();
    return builder;
  }

  /**
   * Whether {@code pos} can be encoded relative to the corner {@code (originX, originZ)}. Encoding
   * a position that cannot throws, and would throw again on every retry.
   */
  public static boolean fits(Pos pos, int originX, int originZ) {
    return fitsInt16((long) pos.x() - originX)
        && fitsInt16(pos.y())
        && fitsInt16((long) pos.z() - originZ);
  }

  private static boolean fitsInt16(long value) {
    return value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
  }

  private static int vec3(FlatBufferBuilder builder, Pos pos, int originX, int originZ) {
    return Vec3s.createVec3s(
        builder, int16(pos.x() - originX), int16(pos.y()), int16(pos.z() - originZ));
  }

  /**
   * A cell as {@code (floor << 8) | pathType}. Throws if the cell is not one the wire carries,
   * which a packed run could not show: a wide floor would spill into the run length.
   */
  private static int cell(int floor, byte pathType) {
    if (floor < 0 || floor > Cells.MAX_FLOOR || !PathTypes.isPerBlock(pathType)) {
      throw new IllegalArgumentException(
          "not a wire cell: " + PathTypes.name(pathType) + " with floor " + floor);
    }
    return (floor << 8) | pathType;
  }

  /** {@code value} as a wire {@code short}. Throws if it does not fit instead of wrapping. */
  static short int16(int value) {
    if (!fitsInt16(value)) {
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
