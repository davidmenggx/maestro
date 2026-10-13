package io.github.davidmenggx.maestro.controller.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import maestro.fbs.CellDelta;
import maestro.fbs.ChunkSnapshot;
import maestro.fbs.ControlFrame;
import maestro.fbs.ParityCase;
import maestro.fbs.ParityFixture;
import maestro.fbs.PathNode;
import maestro.fbs.PathRequest;
import maestro.fbs.PathRequestBatch;
import maestro.fbs.PathResponse;
import maestro.fbs.RegionAssignment;
import maestro.fbs.RequestFrame;
import maestro.fbs.ResponseFrame;
import maestro.fbs.WorldDelta;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;
import org.junit.jupiter.api.Test;

/**
 * The parity fixtures recorded from vanilla are also the wire fixtures shared with the node. These
 * tests check that every frame in them keeps the wire contract, and that the controller's encoder
 * builds each one byte for byte from its decoded content.
 */
class ParityFixtureTest {

  /** Set by the Gradle build to the committed fixtures. */
  private static final String DIRECTORY = "maestro.fixtures";

  private record Fixture(Path file, ParityFixture root, List<byte[]> world) {}

  @Test
  void theFixturesArePresent() {
    assertFalse(fixtures().isEmpty(), "no fixtures in " + System.getProperty(DIRECTORY));
  }

  @Test
  void everyWorldStreamKeepsTheWireContract() {
    for (Fixture fixture : fixtures()) {
      String name = fixture.file().getFileName().toString();
      RegionAssignment assignment = assignment(fixture.world().get(0));
      assertFalse(assignment.resume(), name);
      assertEquals(0, assignment.size() % Chunks.SIZE, name);
      int chunksPerSide = assignment.size() / Chunks.SIZE;
      int chunks = chunksPerSide * chunksPerSide;
      assertTrue(fixture.world().size() > chunks + 1, name + ": the stream has deltas");

      boolean[] seen = new boolean[chunks];
      for (int i = 1; i <= chunks; i++) {
        ChunkSnapshot chunk = chunk(fixture.world().get(i));
        assertEquals(i, chunk.sequence(), name + ": sequences start at 1 with no gap");
        int index = chunk.chunkXz().x() * chunksPerSide + chunk.chunkXz().z();
        assertFalse(seen[index], name + ": a chunk is sent once");
        seen[index] = true;

        assertEquals(Chunks.SIZE * Chunks.SIZE, chunk.runCountsLength(), name);
        int run = 0;
        for (int column = 0; column < chunk.runCountsLength(); column++) {
          int height = 0;
          for (int r = 0; r < chunk.runCounts(column); r++, run++) {
            height += chunk.runs(run).length();
            assertTrue(PathTypes.isPerBlock((byte) chunk.runs(run).nodeType()), name);
            assertTrue(chunk.runs(run).floor() <= Cells.MAX_FLOOR, name);
          }
          assertEquals(assignment.height(), height, name + ": columns are full height");
        }
        assertEquals(chunk.runsLength(), run, name);
      }

      for (int i = chunks + 1; i < fixture.world().size(); i++) {
        WorldDelta delta = delta(fixture.world().get(i));
        assertEquals(i, delta.sequence(), name + ": sequences continue with no gap");
        assertTrue(delta.cellsLength() > 0, name);
        for (int c = 0; c < delta.cellsLength(); c++) {
          CellDelta cell = delta.cells(c);
          assertTrue(PathTypes.isPerBlock((byte) cell.nodeType()), name);
          assertTrue(cell.floor() <= Cells.MAX_FLOOR, name);
          assertTrue(cell.pos().x() >= 0 && cell.pos().x() < assignment.size(), name);
          assertTrue(cell.pos().z() >= 0 && cell.pos().z() < assignment.size(), name);
          assertTrue(
              cell.pos().y() >= assignment.minY()
                  && cell.pos().y() < assignment.minY() + assignment.height(),
              name);
        }
      }
    }
  }

  @Test
  void everyCaseNamesOneConsistentProfileAndAnswersFromTheStreamSoFar() {
    for (Fixture fixture : fixtures()) {
      String name = fixture.file().getFileName().toString();
      Map<Integer, MobClass> profiles = new HashMap<>();
      assertTrue(fixture.root().casesLength() > 0, name);
      long previousFrames = 0;
      for (int i = 0; i < fixture.root().casesLength(); i++) {
        ParityCase recorded = fixture.root().cases(i);
        // Frame `n` of the stream has sequence `n`, so the frames applied name the sequence.
        assertTrue(recorded.worldFrames() >= previousFrames, name + ": frames only accumulate");
        assertTrue(recorded.worldFrames() <= fixture.world().size(), name);
        previousFrames = recorded.worldFrames();
        MobClass profile = profile(recorded);
        MobClass known = profiles.putIfAbsent((int) profile.classId(), profile);
        if (known != null) {
          assertArrayEquals(
              Frames.mobClass(known), Frames.mobClass(profile), name + ": one id, one profile");
        }

        PathRequest request = request(recorded);
        assertEquals(i + 1, request.requestId(), name);
        assertEquals(profile.classId(), request.classId(), name);
        PathResponse response = response(recorded);
        assertEquals(request.requestId(), response.requestId(), name);
        assertEquals(recorded.worldFrames() - 1, response.worldSequence(), name);
        assertTrue(response.nodesLength() > 0, name + ": vanilla always returns a path");
        PathNode first = response.nodes(0);
        assertEquals(pos(request.start()), pos(first.pos()), name + ": a path starts at its start");
      }
    }
  }

  @Test
  void theEncoderRebuildsEveryFrameByteForByte() {
    for (Fixture fixture : fixtures()) {
      String name = fixture.file().getFileName().toString();
      byte[] assignmentFrame = fixture.world().get(0);
      RegionAssignment assignment = assignment(assignmentFrame);
      assertArrayEquals(
          assignmentFrame,
          Frames.assignment(
              assignment.run(),
              (int) assignment.regionId(),
              assignment.size(),
              assignment.resume(),
              new ChunkWorld(null, assignment.minY(), assignment.height())),
          name);

      for (byte[] frame : fixture.world().subList(1, fixture.world().size())) {
        if (isDelta(frame)) {
          WorldDelta delta = delta(frame);
          List<WorldEvent.BlockChanged> cells = new ArrayList<>();
          for (int c = 0; c < delta.cellsLength(); c++) {
            CellDelta cell = delta.cells(c);
            cells.add(
                new WorldEvent.BlockChanged(
                    0, pos(cell.pos()), (byte) cell.nodeType(), cell.floor()));
          }
          assertArrayEquals(frame, Frames.delta(delta.sequence(), cells, 0, 0), name);
          continue;
        }
        ChunkSnapshot chunk = chunk(frame);
        int chunkX = chunk.chunkXz().x();
        int chunkZ = chunk.chunkXz().z();
        WorldSource world = new ChunkWorld(chunk, assignment.minY(), assignment.height());
        assertArrayEquals(
            frame,
            Frames.chunkSnapshot(
                chunk.sequence(), chunkX, chunkZ, Frames.readChunk(world, chunkX, chunkZ)),
            name);
      }

      for (int i = 0; i < fixture.root().casesLength(); i++) {
        ParityCase recorded = fixture.root().cases(i);
        assertArrayEquals(
            bytes(recorded.mobClassAsByteBuffer()), Frames.mobClass(profile(recorded)), name);

        PathRequest request = request(recorded);
        Frames.Request rebuilt =
            new Frames.Request(
                request.requestId(),
                (short) request.classId(),
                pos(request.start()),
                pos(request.target()),
                new WorldEvent.Search(
                    request.reachRange(),
                    request.maxPathLength(),
                    request.maxVisitedNodes(),
                    request.maxFallDistance()));
        assertArrayEquals(
            bytes(recorded.requestAsByteBuffer()), Frames.requests(List.of(rebuilt), 0, 0), name);

        PathResponse response = response(recorded);
        List<WorldOutput.Node> nodes = new ArrayList<>();
        for (int n = 0; n < response.nodesLength(); n++) {
          PathNode node = response.nodes(n);
          nodes.add(new WorldOutput.Node(pos(node.pos()), (byte) node.type()));
        }
        assertArrayEquals(
            bytes(recorded.responseAsByteBuffer()),
            Frames.response(
                response.requestId(),
                response.reached(),
                nodes,
                response.cost(),
                response.worldSequence(),
                0,
                0),
            name);
      }
    }
  }

  /** The cells of one decoded chunk, read in the chunk's own frame. */
  private static final class ChunkWorld implements WorldSource {

    private final int minY;
    private final int height;
    private final byte[] types;
    private final int[] floors;

    /** A null {@code chunk} is all air. */
    ChunkWorld(ChunkSnapshot chunk, int minY, int height) {
      this.minY = minY;
      this.height = height;
      this.types = new byte[Chunks.SIZE * Chunks.SIZE * height];
      this.floors = new int[types.length];
      java.util.Arrays.fill(types, (byte) maestro.fbs.PathType.OPEN);
      if (chunk == null) {
        return;
      }
      int run = 0;
      for (int column = 0; column < chunk.runCountsLength(); column++) {
        int y = 0;
        for (int r = 0; r < chunk.runCounts(column); r++, run++) {
          for (int n = 0; n < chunk.runs(run).length(); n++, y++) {
            types[column * height + y] = (byte) chunk.runs(run).nodeType();
            floors[column * height + y] = chunk.runs(run).floor();
          }
        }
      }
    }

    @Override
    public MobClass mobClass(short classId) {
      return null;
    }

    @Override
    public int minY() {
      return minY;
    }

    @Override
    public int height() {
      return height;
    }

    @Override
    public byte pathTypeAt(int x, int y, int z) {
      return types[index(x, y, z)];
    }

    @Override
    public int floorAt(int x, int y, int z) {
      return floors[index(x, y, z)];
    }

    private int index(int x, int y, int z) {
      int column = Math.floorMod(x, Chunks.SIZE) * Chunks.SIZE + Math.floorMod(z, Chunks.SIZE);
      return column * height + (y - minY);
    }
  }

  private static List<Fixture> fixtures() {
    String directory = System.getProperty(DIRECTORY);
    assertNotNull(directory, "the build sets " + DIRECTORY);
    try (Stream<Path> files = Files.list(Path.of(directory))) {
      List<Fixture> fixtures = new ArrayList<>();
      for (Path file : files.filter(f -> f.toString().endsWith(".bin")).sorted().toList()) {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
        assertTrue(ParityFixture.ParityFixtureBufferHasIdentifier(buffer), file.toString());
        ParityFixture root = ParityFixture.getRootAsParityFixture(buffer);
        fixtures.add(new Fixture(file, root, unframe(root.worldAsByteBuffer())));
      }
      return fixtures;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Splits a framed stream: each frame is a little-endian 32-bit length, then the payload. */
  private static List<byte[]> unframe(ByteBuffer stream) {
    ByteBuffer in = stream.slice().order(ByteOrder.LITTLE_ENDIAN);
    List<byte[]> frames = new ArrayList<>();
    while (in.hasRemaining()) {
      byte[] frame = new byte[in.getInt()];
      in.get(frame);
      frames.add(frame);
    }
    return frames;
  }

  private static RegionAssignment assignment(byte[] frame) {
    WorldFrame root = WorldFrame.getRootAsWorldFrame(ByteBuffer.wrap(frame));
    assertEquals(WorldMsg.RegionAssignment, root.bodyType(), "the stream opens with an assignment");
    return (RegionAssignment) root.body(new RegionAssignment());
  }

  private static boolean isDelta(byte[] frame) {
    return WorldFrame.getRootAsWorldFrame(ByteBuffer.wrap(frame)).bodyType() == WorldMsg.WorldDelta;
  }

  private static WorldDelta delta(byte[] frame) {
    WorldFrame root = WorldFrame.getRootAsWorldFrame(ByteBuffer.wrap(frame));
    assertEquals(WorldMsg.WorldDelta, root.bodyType(), "then only block changes");
    return (WorldDelta) root.body(new WorldDelta());
  }

  private static ChunkSnapshot chunk(byte[] frame) {
    WorldFrame root = WorldFrame.getRootAsWorldFrame(ByteBuffer.wrap(frame));
    assertEquals(WorldMsg.ChunkSnapshot, root.bodyType(), "then the chunks");
    return (ChunkSnapshot) root.body(new ChunkSnapshot());
  }

  private static MobClass profile(ParityCase recorded) {
    ControlFrame root = ControlFrame.getRootAsControlFrame(recorded.mobClassAsByteBuffer());
    maestro.fbs.MobClass wire = (maestro.fbs.MobClass) root.body(new maestro.fbs.MobClass());
    float[] malus = new float[wire.malusLength()];
    for (int i = 0; i < malus.length; i++) {
      malus[i] = wire.malus(i);
    }
    return new MobClass(
        (short) wire.classId(),
        new MobClass.Shape(wire.width(), wire.height(), wire.maxUpStep()),
        new MobClass.Abilities(
            wire.canPassDoors(), wire.canOpenDoors(), wire.canFloat(), wire.canWalkOverFences()),
        malus);
  }

  private static PathRequest request(ParityCase recorded) {
    RequestFrame root = RequestFrame.getRootAsRequestFrame(recorded.requestAsByteBuffer());
    PathRequestBatch batch = (PathRequestBatch) root.body(new PathRequestBatch());
    assertEquals(1, batch.requestsLength());
    return batch.requests(0);
  }

  private static PathResponse response(ParityCase recorded) {
    ResponseFrame root = ResponseFrame.getRootAsResponseFrame(recorded.responseAsByteBuffer());
    return (PathResponse) root.body(new PathResponse());
  }

  private static Pos pos(maestro.fbs.Vec3s wire) {
    return new Pos(wire.x(), wire.y(), wire.z());
  }

  private static byte[] bytes(ByteBuffer buffer) {
    byte[] out = new byte[buffer.remaining()];
    buffer.duplicate().get(out);
    return out;
  }
}
