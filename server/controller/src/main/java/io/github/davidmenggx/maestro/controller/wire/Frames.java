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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import maestro.fbs.CellDelta;
import maestro.fbs.CellRun;
import maestro.fbs.ChunkSnapshot;
import maestro.fbs.ControlFrame;
import maestro.fbs.ControlMsg;
import maestro.fbs.ControlReplyFrame;
import maestro.fbs.ControlReplyMsg;
import maestro.fbs.Heartbeat;
import maestro.fbs.Hello;
import maestro.fbs.Join;
import maestro.fbs.PathCancel;
import maestro.fbs.PathNode;
import maestro.fbs.PathProbe;
import maestro.fbs.PathRequest;
import maestro.fbs.PathRequestBatch;
import maestro.fbs.PathResponse;
import maestro.fbs.RegionAssignment;
import maestro.fbs.RequestFrame;
import maestro.fbs.RequestMsg;
import maestro.fbs.ResponseFrame;
import maestro.fbs.ResponseMsg;
import maestro.fbs.UnknownProfile;
import maestro.fbs.Vec2i;
import maestro.fbs.Vec3s;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;

/**
 * Every frame payload the controller sends and every reply it reads, using the generated bindings.
 * Positions are given in world coordinates and leave relative to the corner {@code (originX,
 * originZ)} of the receiving node's area.
 *
 * <p>Generated setters cast an {@code int} to the field width unchecked, so values not
 * range-checked at the boundary go through {@link #int16} or {@link #uint16}.
 *
 * <p>Each thread reuses one builder, cleared per frame, so its buffer grows once. An encoder must
 * not call another while it builds.
 */
public final class Frames {

  private static final ThreadLocal<FlatBufferBuilder> BUILDER =
      ThreadLocal.withInitial(() -> new FlatBufferBuilder(4096));

  /** Buffers a chunk is read through, reused per thread. */
  private static final ThreadLocal<RunBuilder> CHUNK_SCRATCH =
      ThreadLocal.withInitial(RunBuilder::new);

  /**
   * Largest payload the node reads from its world and control streams. Must match the node's {@code
   * net::MaxPayloadSize}. A worst-case chunk is about 4 MiB.
   */
  public static final int MAX_FRAME_BYTES = 8 << 20;

  /** Largest UDP payload over IPv4. An answer fits: its path has at most 2049 nodes of 8 bytes. */
  public static final int MAX_DATAGRAM_BYTES = 65_507;

  /**
   * Largest request frame: the 1232 payload bytes IPv6's minimum MTU guarantees, so a frame is
   * never fragmented.
   */
  public static final int MAX_REQUEST_FRAME_BYTES = 1200;

  /**
   * Requests in one frame. All fields are always encoded, so a full frame fits {@link
   * #MAX_REQUEST_FRAME_BYTES} whatever the values.
   */
  public static final int MAX_REQUESTS_PER_FRAME = 24;

  /** Request ids in one cancel frame, fitting {@link #MAX_REQUEST_FRAME_BYTES}. */
  public static final int MAX_CANCELS_PER_FRAME = 128;

  /** Block changes in one delta, 512 KiB at most. */
  public static final int MAX_CELLS_PER_DELTA = 1 << 16;

  private Frames() {}

  /** One path request, held by the controller until the tick is flushed. */
  public record Request(
      long requestId, short classId, Pos start, Pos target, WorldEvent.Search search) {}

  /** Columns in one chunk, indexed `(x - minX) * Chunks.SIZE + (z - minZ)`. */
  static final int COLUMNS_PER_CHUNK = Chunks.SIZE * Chunks.SIZE;

  /**
   * One chunk's columns, run-length encoded. A run packs into one int as {@code (length << 16) |
   * (floor << 8) | pathType}.
   */
  public static final class ChunkRuns {

    private final int[] runsPerColumn = new int[COLUMNS_PER_CHUNK];
    private int[] packedRuns;
    private int runCount;
  }

  /**
   * Reads one chunk on the main thread, or returns null if the source cannot read it now ({@link
   * WorldSource#readable}). Uniform sections are not read block by block, so cost follows terrain
   * complexity, not world height.
   */
  public static ChunkRuns readChunk(WorldSource world, int chunkX, int chunkZ) {
    if (!world.readable(chunkX, chunkZ)) {
      return null;
    }
    int minX = chunkX * Chunks.SIZE;
    int minZ = chunkZ * Chunks.SIZE;
    int minY = world.minY();
    int height = world.height();
    int sections = height / WorldSource.SECTION_HEIGHT;
    RunBuilder runs = CHUNK_SCRATCH.get();
    runs.reset();
    int[] sectionCells = runs.sectionCells;
    for (int section = 0; section < sections; section++) {
      int bottom = section * WorldSource.SECTION_HEIGHT;
      int uniform = world.uniformCell(chunkX, chunkZ, section);
      if (uniform != WorldSource.NOT_UNIFORM) {
        int cell = cell(uniform);
        for (int column = 0; column < COLUMNS_PER_CHUNK; column++) {
          runs.add(column, cell, WorldSource.SECTION_HEIGHT);
        }
        continue;
      }
      if (!world.readSection(chunkX, chunkZ, section, sectionCells)) {
        readBlocks(world, minX, minY + bottom, minZ, WorldSource.SECTION_HEIGHT, runs);
        continue;
      }
      for (int column = 0; column < COLUMNS_PER_CHUNK; column++) {
        int end = (column + 1) * WorldSource.SECTION_HEIGHT;
        for (int i = column * WorldSource.SECTION_HEIGHT; i < end; ) {
          int read = sectionCells[i];
          int length = 1;
          while (i + length < end && sectionCells[i + length] == read) {
            length++;
          }
          runs.add(column, cell(read), length);
          i += length;
        }
      }
    }
    int top = sections * WorldSource.SECTION_HEIGHT;
    readBlocks(world, minX, minY + top, minZ, height - top, runs);
    return runs.finish();
  }

  /** Reads {@code layers} blocks of every column from {@code y} up, one block at a time. */
  private static void readBlocks(
      WorldSource world, int minX, int y, int minZ, int layers, RunBuilder runs) {
    for (int dx = 0; dx < Chunks.SIZE; dx++) {
      for (int dz = 0; dz < Chunks.SIZE; dz++) {
        int x = minX + dx;
        int z = minZ + dz;
        int column = dx * Chunks.SIZE + dz;
        for (int dy = 0; dy < layers; dy++) {
          runs.add(
              column,
              cell(Cells.pack(world.floorAt(x, y + dy, z), world.pathTypeAt(x, y + dy, z))),
              1);
        }
      }
    }
  }

  /**
   * Run-length encodes a chunk read a section at a time, bottom up. Each column keeps an open run;
   * closed runs are put in column order at the end, keeping each column in height order. Reused
   * after {@link #reset}.
   */
  private static final class RunBuilder {

    private final int[] sectionCells = new int[COLUMNS_PER_CHUNK * WorldSource.SECTION_HEIGHT];
    private final int[] runsPerColumn = new int[COLUMNS_PER_CHUNK];
    private final int[] openCell = new int[COLUMNS_PER_CHUNK];
    private final int[] openLength = new int[COLUMNS_PER_CHUNK];
    private final int[] next = new int[COLUMNS_PER_CHUNK];
    // Closed runs as `(column << 32) | packed run`. Flat terrain has two per column.
    private long[] closed = new long[COLUMNS_PER_CHUNK * 2];
    private int closedCount;

    void reset() {
      Arrays.fill(runsPerColumn, 0);
      Arrays.fill(openLength, 0);
      closedCount = 0;
    }

    void add(int column, int cell, int length) {
      if (openLength[column] > 0) {
        if (openCell[column] == cell) {
          openLength[column] += length;
          return;
        }
        close(column);
      }
      openCell[column] = cell;
      openLength[column] = length;
    }

    private void close(int column) {
      if (closedCount == closed.length) {
        closed = Arrays.copyOf(closed, closedCount * 2);
      }
      int run = (uint16(openLength[column]) << 16) | openCell[column];
      closed[closedCount++] = ((long) column << 32) | (run & 0xFFFFFFFFL);
      runsPerColumn[column]++;
    }

    /** Closes every column's last run, even an empty one, and returns the runs by column. */
    ChunkRuns finish() {
      for (int column = 0; column < COLUMNS_PER_CHUNK; column++) {
        close(column);
      }
      next[0] = 0;
      for (int column = 1; column < COLUMNS_PER_CHUNK; column++) {
        next[column] = next[column - 1] + runsPerColumn[column - 1];
      }
      ChunkRuns runs = new ChunkRuns();
      System.arraycopy(runsPerColumn, 0, runs.runsPerColumn, 0, COLUMNS_PER_CHUNK);
      runs.packedRuns = new int[closedCount];
      runs.runCount = closedCount;
      for (int i = 0; i < closedCount; i++) {
        long entry = closed[i];
        runs.packedRuns[next[(int) (entry >>> 32)]++] = (int) entry;
      }
      return runs;
    }
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

  /** The first frame on a link's world connection. See {@link #joinControl}. */
  public static byte[] joinWorld(long run, long link) {
    FlatBufferBuilder builder = builder();
    int join = join(builder, run, link);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.Join, join));
    return builder.sizedByteArray();
  }

  /**
   * The first frame on a link's control connection. {@code link} counts connect attempts, so the
   * node pairs the two connections of one attempt.
   */
  public static byte[] joinControl(long run, long link) {
    FlatBufferBuilder builder = builder();
    int join = join(builder, run, link);
    builder.finish(ControlFrame.createControlFrame(builder, ControlMsg.Join, join));
    return builder.sizedByteArray();
  }

  private static int join(FlatBufferBuilder builder, long run, long link) {
    if (run == 0 || link <= 0) {
      throw new IllegalArgumentException("a link needs a run and a positive attempt: " + link);
    }
    return Join.createJoin(builder, run, link);
  }

  /**
   * At most {@link #MAX_REQUESTS_PER_FRAME} requests, so the frame fits one datagram. {@code run}
   * and {@code regionId} name the link and area.
   */
  public static byte[] requests(
      long run, int regionId, List<Request> batch, int originX, int originZ) {
    if (batch.size() > MAX_REQUESTS_PER_FRAME) {
      throw new IllegalArgumentException(
          "a request frame carries at most " + MAX_REQUESTS_PER_FRAME);
    }
    FlatBufferBuilder builder = builder();
    // Defaults are omitted unless forced, and requests omitting different fields would need
    // different layouts.
    builder.forceDefaults(true);
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
    builder.forceDefaults(false);
    int requestsVector = PathRequestBatch.createRequestsVector(builder, offsets);
    PathRequestBatch.startPathRequestBatch(builder);
    PathRequestBatch.addRequests(builder, requestsVector);
    int batchOffset = PathRequestBatch.endPathRequestBatch(builder);
    builder.finish(
        RequestFrame.createRequestFrame(
            builder, RequestMsg.PathRequestBatch, batchOffset, run, regionId));
    return builder.sizedByteArray();
  }

  /**
   * Requests no longer wanted, at most {@link #MAX_CANCELS_PER_FRAME}. {@code run} and {@code
   * regionId} as for {@link #requests}.
   */
  public static byte[] cancels(long run, int regionId, List<Long> requestIds) {
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
    builder.finish(
        RequestFrame.createRequestFrame(builder, RequestMsg.PathCancel, cancel, run, regionId));
    return builder.sizedByteArray();
  }

  /**
   * A probe of whether the node serves requests, under a request id. It names region 0, so the node
   * answers it with no path at sequence 0.
   */
  public static byte[] probe(long run, long requestId) {
    FlatBufferBuilder builder = builder();
    int probe = PathProbe.createPathProbe(builder, requestId);
    builder.finish(RequestFrame.createRequestFrame(builder, RequestMsg.PathProbe, probe, run, 0));
    return builder.sizedByteArray();
  }

  /**
   * {@code sizeX} and {@code sizeZ} are the sides of the area, multiples of {@link Chunks#SIZE};
   * every position in it must fit the wire's 16 bits. {@code sequence} is where the region's stream
   * stands, so the next frame is {@code sequence + 1}.
   */
  public static byte[] assignment(
      long run,
      int regionId,
      int sizeX,
      int sizeZ,
      long sequence,
      boolean resume,
      WorldSource world) {
    return assignment(run, regionId, sizeX, sizeZ, sequence, resume, 0, world);
  }

  /**
   * An assignment that keeps the node's state: it holds region {@code fromRegionId} at {@code
   * sequence}, in an area with the same corner that contains this one, and keeps what lies inside
   * as {@code regionId}.
   */
  public static byte[] keptAssignment(
      long run,
      int regionId,
      int fromRegionId,
      int sizeX,
      int sizeZ,
      long sequence,
      WorldSource world) {
    if (fromRegionId < 1) {
      throw new IllegalArgumentException("a kept area comes from a region: " + fromRegionId);
    }
    return assignment(run, regionId, sizeX, sizeZ, sequence, false, fromRegionId, world);
  }

  private static byte[] assignment(
      long run,
      int regionId,
      int sizeX,
      int sizeZ,
      long sequence,
      boolean resume,
      int fromRegionId,
      WorldSource world) {
    for (int size : new int[] {sizeX, sizeZ}) {
      if (size <= 0 || size % Chunks.SIZE != 0 || size > Short.MAX_VALUE) {
        throw new IllegalArgumentException("an area is whole chunks a short can index: " + size);
      }
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
    RegionAssignment.addSizeX(builder, sizeX);
    RegionAssignment.addSizeZ(builder, sizeZ);
    RegionAssignment.addMinY(builder, minY);
    RegionAssignment.addHeight(builder, uint16(height));
    RegionAssignment.addResume(builder, resume);
    RegionAssignment.addFromRegionId(builder, fromRegionId);
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
   * A node's answer. Only for storing recorded vanilla answers in the node's form for comparison.
   */
  public static byte[] response(
      long run,
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
    builder.finish(
        ResponseFrame.createResponseFrame(builder, ResponseMsg.PathResponse, response, run));
    return builder.sizedByteArray();
  }

  /**
   * The reply in a response frame of run {@code run}, {@link Reply.Foreign} if another run's, or
   * null for an unknown body. Throws on a malformed frame.
   */
  public static Reply readResponse(ByteBuffer frame, long run) {
    ResponseFrame response = ResponseFrame.getRootAsResponseFrame(frame);
    if (response.run() != run) {
      return new Reply.Foreign();
    }
    switch (response.bodyType()) {
      case ResponseMsg.PathResponse -> {
        PathResponse body = (PathResponse) response.body(new PathResponse());
        // Not presized: the length comes from the datagram and may be garbage.
        List<WorldOutput.Node> nodes = new ArrayList<>();
        PathNode scratch = new PathNode();
        for (int i = 0; i < body.nodesLength(); i++) {
          body.nodes(scratch, i);
          Pos pos = new Pos(scratch.pos().x(), scratch.pos().y(), scratch.pos().z());
          nodes.add(new WorldOutput.Node(pos, (byte) scratch.type()));
        }
        return new Reply.Answer(
            body.requestId(),
            body.reached(),
            nodes,
            body.cost(),
            body.worldSequence(),
            body.waitMicros(),
            body.searchMicros());
      }
      case ResponseMsg.UnknownProfile -> {
        UnknownProfile body = (UnknownProfile) response.body(new UnknownProfile());
        return new Reply.Refusal(body.requestId(), body.classId());
      }
      default -> {
        return null;
      }
    }
  }

  /** The reply in a control frame, or null for a body this controller does not know. */
  public static Reply readControlReply(ByteBuffer frame) {
    ControlReplyFrame reply = ControlReplyFrame.getRootAsControlReplyFrame(frame);
    switch (reply.bodyType()) {
      case ControlReplyMsg.Hello -> {
        Hello hello = (Hello) reply.body(new Hello());
        return new Reply.Greeting(
            hello.run(), hello.regionId(), hello.lastSequence(), hello.pathfinder());
      }
      case ControlReplyMsg.Heartbeat -> {
        Heartbeat heartbeat = (Heartbeat) reply.body(new Heartbeat());
        return new Reply.Beat(
            heartbeat.queueDepth(),
            heartbeat.searches(),
            heartbeat.workerMicros(),
            heartbeat.workers(),
            heartbeat.regionId(),
            heartbeat.sequence());
      }
      default -> {
        return null;
      }
    }
  }

  private static FlatBufferBuilder builder() {
    FlatBufferBuilder builder = BUILDER.get();
    builder.clear();
    builder.forceDefaults(false);
    return builder;
  }

  /**
   * Whether {@code pos} can be encoded relative to {@code (originX, originZ)}. Encoding one that
   * cannot throws on every retry.
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
   * {@code packed}, a {@link Cells#pack packed} cell, checked. Throws for a cell the wire cannot
   * carry, such as a floor that would spill into the run length.
   */
  private static int cell(int packed) {
    int floor = Cells.floorOf(packed);
    byte pathType = Cells.typeOf(packed);
    if (floor < 0 || floor > Cells.MAX_FLOOR || !PathTypes.isPerBlock(pathType)) {
      throw new IllegalArgumentException(
          "not a wire cell: " + PathTypes.name(pathType) + " with floor " + floor);
    }
    return Cells.pack(floor, pathType);
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
