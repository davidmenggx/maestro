package io.github.davidmenggx.maestro.controller.sim;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.Arrays;
import java.util.List;
import maestro.fbs.CellDelta;
import maestro.fbs.CellRun;
import maestro.fbs.ControlFrame;
import maestro.fbs.ControlMsg;
import maestro.fbs.MobClassTable;
import maestro.fbs.PathRequest;
import maestro.fbs.PathRequestBatch;
import maestro.fbs.Ping;
import maestro.fbs.RegionAssignment;
import maestro.fbs.RequestFrame;
import maestro.fbs.RequestMsg;
import maestro.fbs.TraceContext;
import maestro.fbs.Vec2i;
import maestro.fbs.Vec3i;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;
import maestro.fbs.WorldSnapshot;

/**
 * Everything the controller puts on the wire, encoded with the generated bindings. Kept apart from
 * the controller so a test can build the frames a run would build, and so the encoding reads as one
 * piece.
 */
public final class Frames {

  private Frames() {}

  /** One path request as the controller holds it until the tick is flushed. */
  public record Request(long requestId, int mobId, short classId, Pos start, Pos target) {}

  /**
   * A region's columns, run-length encoded. Runs are packed one per int as {@code (length << 8) |
   * pathType}, which keeps a full-height region's worth of runs out of the allocator.
   */
  public static final class RegionRuns {

    private final int[] runsPerColumn;
    private int[] packedRuns;
    private int runCount;

    private RegionRuns(int columns) {
      // Flat terrain is two runs a column, so this is the size most regions settle at.
      this.runsPerColumn = new int[columns];
      this.packedRuns = new int[columns * 2];
    }

    private void add(int length, byte pathType) {
      if (runCount == packedRuns.length) {
        packedRuns = Arrays.copyOf(packedRuns, runCount * 2);
      }
      packedRuns[runCount++] = (length << 8) | (pathType & 0xFF);
    }
  }

  /**
   * Reads a region out of the world. The world may only be read on the main thread, so this is
   * separate from the encoding, which runs off it.
   */
  public static RegionRuns readRegion(WorldSource world, RegionMap regions, int region) {
    int regionSize = regions.topology().regionSize();
    int minX = regions.minX(region);
    int minZ = regions.minZ(region);
    int minY = world.minY();
    int height = world.height();
    RegionRuns runs = new RegionRuns(regionSize * regionSize);
    for (int dx = 0; dx < regionSize; dx++) {
      for (int dz = 0; dz < regionSize; dz++) {
        int closedRuns = 0;
        int runLength = 0;
        byte runType = 0;
        for (int dy = 0; dy < height; dy++) {
          byte type = world.pathTypeAt(minX + dx, minY + dy, minZ + dz);
          if (runLength > 0 && type == runType) {
            runLength++;
            continue;
          }
          if (runLength > 0) {
            runs.add(runLength, runType);
            closedRuns++;
          }
          runType = type;
          runLength = 1;
        }
        runs.add(runLength, runType);
        runs.runsPerColumn[dx * regionSize + dz] = closedRuns + 1;
      }
    }
    return runs;
  }

  public static byte[] snapshot(long sequence, int baseY, RegionRuns runs) {
    FlatBufferBuilder builder = new FlatBufferBuilder(4096);
    int runCountsVector = WorldSnapshot.createRunCountsVector(builder, runs.runsPerColumn);
    WorldSnapshot.startRunsVector(builder, runs.runCount);
    for (int i = runs.runCount - 1; i >= 0; i--) {
      int run = runs.packedRuns[i];
      CellRun.createCellRun(builder, run >>> 8, run & 0xFF);
    }
    int runsVector = builder.endVector();

    WorldSnapshot.startWorldSnapshot(builder);
    WorldSnapshot.addSequence(builder, sequence);
    WorldSnapshot.addBaseY(builder, baseY);
    WorldSnapshot.addRunCounts(builder, runCountsVector);
    WorldSnapshot.addRuns(builder, runsVector);
    int snapshot = WorldSnapshot.endWorldSnapshot(builder);

    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.WorldSnapshot, snapshot));
    return builder.sizedByteArray();
  }

  /** Region snapshot as the controller would build it, for a test that recomputes one. */
  public static byte[] snapshot(WorldSource world, RegionMap regions, int region, long sequence) {
    return snapshot(sequence, world.minY(), readRegion(world, regions, region));
  }

  public static byte[] delta(long sequence, List<WorldEvent.BlockChanged> cells) {
    FlatBufferBuilder builder = new FlatBufferBuilder(256);
    WorldDelta.startCellsVector(builder, cells.size());
    for (int i = cells.size() - 1; i >= 0; i--) {
      WorldEvent.BlockChanged cell = cells.get(i);
      CellDelta.createCellDelta(
          builder, cell.pos().x(), cell.pos().y(), cell.pos().z(), cell.pathType());
    }
    int vector = builder.endVector();
    int delta = WorldDelta.createWorldDelta(builder, sequence, vector);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.WorldDelta, delta));
    return builder.sizedByteArray();
  }

  public static byte[] requests(List<Request> batch, long worldSequence) {
    FlatBufferBuilder builder = new FlatBufferBuilder(512);
    int[] offsets = new int[batch.size()];
    for (int i = 0; i < batch.size(); i++) {
      Request request = batch.get(i);
      PathRequest.startPathRequest(builder);
      PathRequest.addRequestId(builder, request.requestId());
      PathRequest.addMobId(builder, request.mobId());
      PathRequest.addClassId(builder, request.classId());
      PathRequest.addStart(builder, vec3(builder, request.start()));
      PathRequest.addTarget(builder, vec3(builder, request.target()));
      PathRequest.addWorldSequence(builder, worldSequence);
      // Minted from the request's span once the controller has a tracer; the harness traces
      // nothing.
      PathRequest.addTrace(builder, TraceContext.createTraceContext(builder, 0, 0, 0, 0));
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

  public static byte[] assignment(RegionMap regions, int region, long resumeFrom) {
    FlatBufferBuilder builder = new FlatBufferBuilder(128);
    int regionSize = regions.topology().regionSize();
    RegionAssignment.startRegionAssignment(builder);
    RegionAssignment.addRegionId(builder, region);
    RegionAssignment.addMinXz(
        builder, Vec2i.createVec2i(builder, regions.minX(region), regions.minZ(region)));
    RegionAssignment.addMaxXz(
        builder,
        Vec2i.createVec2i(
            builder, regions.minX(region) + regionSize - 1, regions.minZ(region) + regionSize - 1));
    RegionAssignment.addResumeFromSequence(builder, resumeFrom);
    int assignment = RegionAssignment.endRegionAssignment(builder);
    builder.finish(WorldFrame.createWorldFrame(builder, WorldMsg.RegionAssignment, assignment));
    return builder.sizedByteArray();
  }

  public static byte[] mobClasses(List<MobClass> classes) {
    FlatBufferBuilder builder = new FlatBufferBuilder(512);
    int[] offsets = new int[classes.size()];
    for (int i = 0; i < classes.size(); i++) {
      offsets[i] = mobClass(builder, classes.get(i));
    }
    int classesVector = MobClassTable.createClassesVector(builder, offsets);
    MobClassTable.startMobClassTable(builder);
    MobClassTable.addClasses(builder, classesVector);
    int table = MobClassTable.endMobClassTable(builder);
    builder.finish(ControlFrame.createControlFrame(builder, ControlMsg.MobClassTable, table));
    return builder.sizedByteArray();
  }

  public static byte[] ping(long sequence, long sendMicros) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    Ping.startPing(builder);
    Ping.addSequence(builder, sequence);
    Ping.addSendMicros(builder, sendMicros);
    int ping = Ping.endPing(builder);
    builder.finish(ControlFrame.createControlFrame(builder, ControlMsg.Ping, ping));
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
    maestro.fbs.MobClass.addMaxFollowRange(builder, profile.maxFollowRange());
    return maestro.fbs.MobClass.endMobClass(builder);
  }

  private static int vec3(FlatBufferBuilder builder, Pos pos) {
    return Vec3i.createVec3i(builder, pos.x(), pos.y(), pos.z());
  }
}
