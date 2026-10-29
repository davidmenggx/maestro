package io.github.davidmenggx.maestro.controller.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import maestro.fbs.ControlFrame;
import maestro.fbs.ControlMsg;
import maestro.fbs.ControlReplyFrame;
import maestro.fbs.ControlReplyMsg;
import maestro.fbs.Heartbeat;
import maestro.fbs.Join;
import maestro.fbs.PathCancel;
import maestro.fbs.PathRequest;
import maestro.fbs.PathRequestBatch;
import maestro.fbs.PathResponse;
import maestro.fbs.PathType;
import maestro.fbs.RegionAssignment;
import maestro.fbs.RequestFrame;
import maestro.fbs.RequestMsg;
import maestro.fbs.ResponseFrame;
import maestro.fbs.ResponseMsg;
import maestro.fbs.WorldFrame;
import maestro.fbs.WorldMsg;
import org.junit.jupiter.api.Test;

class FramesTest {

  private static final WorldEvent.Search SEARCH = new WorldEvent.Search(0, 16f, 256, 3);

  private static final long RUN = 0x5eed;

  @Test
  void aLinkOpensEachConnectionWithAJoinNamingTheRunAndAttempt() {
    WorldFrame world = WorldFrame.getRootAsWorldFrame(ByteBuffer.wrap(Frames.joinWorld(RUN, 4)));
    assertEquals(WorldMsg.Join, world.bodyType());
    Join worldJoin = (Join) world.body(new Join());
    assertEquals(RUN, worldJoin.run());
    assertEquals(4, worldJoin.link());

    ControlFrame control =
        ControlFrame.getRootAsControlFrame(ByteBuffer.wrap(Frames.joinControl(RUN, 4)));
    assertEquals(ControlMsg.Join, control.bodyType());
    Join controlJoin = (Join) control.body(new Join());
    assertEquals(RUN, controlJoin.run());
    assertEquals(4, controlJoin.link());

    // The node refuses a zero field, so the controller never sends one.
    assertThrows(IllegalArgumentException.class, () -> Frames.joinWorld(0, 1));
    assertThrows(IllegalArgumentException.class, () -> Frames.joinControl(RUN, 0));
  }

  @Test
  void aValueTooWideForItsWireFieldIsRefusedRatherThanWrapped() {
    assertEquals((short) -32768, Frames.int16(Short.MIN_VALUE));
    assertThrows(IllegalArgumentException.class, () -> Frames.int16(Short.MAX_VALUE + 1));
    assertEquals(0xFFFF, Frames.uint16(0xFFFF));
    assertThrows(IllegalArgumentException.class, () -> Frames.uint16(0x10000));
    assertThrows(IllegalArgumentException.class, () -> Frames.uint16(-1));

    Frames.Request farAway =
        new Frames.Request(1, (short) 1, new Pos(40_000, 40, 0), new Pos(0, 40, 0), SEARCH);
    assertThrows(
        IllegalArgumentException.class, () -> Frames.requests(RUN, 0, List.of(farAway), 0, 0));
  }

  @Test
  void aFullFrameOfTheLargestRequestsFitsOneDatagram() {
    // Every field at its widest, so none is left out as a default.
    WorldEvent.Search widest =
        new WorldEvent.Search(
            WorldEvent.Search.MAX_REACH_RANGE,
            WorldEvent.Search.MAX_PATH_LENGTH,
            WorldEvent.Search.MAX_VISITED_NODES,
            WorldEvent.Search.MAX_FALL_DISTANCE);
    List<Frames.Request> batch = new ArrayList<>();
    for (int i = 0; i < Frames.MAX_REQUESTS_PER_FRAME; i++) {
      batch.add(
          new Frames.Request(
              Long.MAX_VALUE - i,
              Short.MAX_VALUE,
              new Pos(Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE),
              new Pos(Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE),
              widest));
    }
    byte[] full = Frames.requests(Long.MAX_VALUE, Integer.MAX_VALUE, batch, 0, 0);
    assertTrue(full.length <= Frames.MAX_REQUEST_FRAME_BYTES, full.length + " bytes");

    batch.add(batch.get(0));
    assertThrows(IllegalArgumentException.class, () -> Frames.requests(RUN, 0, batch, 0, 0));
  }

  @Test
  void aFullFrameOfCancelsFitsOneDatagramAndCarriesEveryId() {
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < Frames.MAX_CANCELS_PER_FRAME; i++) {
      ids.add(Long.MAX_VALUE - i);
    }
    byte[] bytes = Frames.cancels(Long.MAX_VALUE, Integer.MAX_VALUE, ids);
    assertTrue(bytes.length <= Frames.MAX_REQUEST_FRAME_BYTES, bytes.length + " bytes");

    RequestFrame frame = RequestFrame.getRootAsRequestFrame(ByteBuffer.wrap(bytes));
    assertEquals(RequestMsg.PathCancel, frame.bodyType());
    assertEquals(Long.MAX_VALUE, frame.run());
    assertEquals(0xFFFFFFFFL & Integer.MAX_VALUE, frame.regionId());
    PathCancel cancel = (PathCancel) frame.body(new PathCancel());
    assertEquals(ids.size(), cancel.requestIdsLength());
    for (int i = 0; i < ids.size(); i++) {
      assertEquals(ids.get(i), cancel.requestIds(i));
    }

    ids.add(1L);
    assertThrows(IllegalArgumentException.class, () -> Frames.cancels(RUN, 1, ids));
  }

  @Test
  void aFullFrameFitsOneDatagramWhicheverFieldsAreZero() {
    // Every field is written, since a field left at its default would change the layout.
    List<Frames.Request> batch = new ArrayList<>();
    for (int i = 0; i < Frames.MAX_REQUESTS_PER_FRAME; i++) {
      WorldEvent.Search search = new WorldEvent.Search(i % 2, 16f, 256, i % 3 == 0 ? 0 : 3);
      batch.add(
          new Frames.Request(
              Long.MAX_VALUE - i, (short) (i % 2), new Pos(i, 0, 0), new Pos(0, i % 2, 0), search));
    }
    byte[] mixed = Frames.requests(Long.MAX_VALUE, Integer.MAX_VALUE, batch, 0, 0);
    assertTrue(mixed.length <= Frames.MAX_REQUEST_FRAME_BYTES, mixed.length + " bytes");

    RequestFrame frame = RequestFrame.getRootAsRequestFrame(ByteBuffer.wrap(mixed));
    PathRequestBatch decoded = (PathRequestBatch) frame.body(new PathRequestBatch());
    PathRequest first = decoded.requests(0);
    assertEquals(0, first.classId());
    assertEquals(0, first.maxFallDistance());
    // The builder writes defaults only while it builds a request frame.
    byte[] cleared = Frames.chunkCleared(1, 0, 0);
    assertTrue(cleared.length < 64, cleared.length + " bytes");
  }

  @Test
  void theLongestAnswerFitsOneDatagram() {
    // A path walks less than its length bound, a block or more per node.
    int longest = (int) WorldEvent.Search.MAX_PATH_LENGTH + 1;
    List<WorldOutput.Node> nodes = new ArrayList<>();
    for (int i = 0; i < longest; i++) {
      nodes.add(new WorldOutput.Node(new Pos(i % 1000, i / 1000, 0), (byte) PathType.WALKABLE));
    }
    byte[] answer =
        Frames.response(Long.MAX_VALUE, Long.MAX_VALUE, true, nodes, 1e9f, Long.MAX_VALUE, 0, 0);
    assertTrue(answer.length <= Frames.MAX_DATAGRAM_BYTES, answer.length + " bytes");

    Reply.Answer read = (Reply.Answer) Frames.readResponse(ByteBuffer.wrap(answer), Long.MAX_VALUE);
    assertEquals(longest, read.nodes().size());
    assertEquals(nodes.get(longest - 1).pos(), read.nodes().get(longest - 1).pos());
    assertTrue(Frames.readResponse(ByteBuffer.wrap(answer), 1) instanceof Reply.Foreign);
  }

  @Test
  void anAnswerIsReadWithHowLongItWaitedAndSearched() {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    PathResponse.startNodesVector(builder, 0);
    int nodes = builder.endVector();
    int body =
        PathResponse.createPathResponse(builder, 7, false, nodes, 0f, 12, 4_000_000_000L, 350);
    builder.finish(ResponseFrame.createResponseFrame(builder, ResponseMsg.PathResponse, body, RUN));

    Reply.Answer read =
        (Reply.Answer) Frames.readResponse(ByteBuffer.wrap(builder.sizedByteArray()), RUN);
    assertEquals(12, read.worldSequence());
    // Unsigned on the wire.
    assertEquals(4_000_000_000L, read.waitMicros());
    assertEquals(350, read.searchMicros());
  }

  @Test
  void aHeartbeatIsReadWithTheAreaHeld() {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int body = Heartbeat.createHeartbeat(builder, 3, 4, 500, 2, 6, 41);
    builder.finish(
        ControlReplyFrame.createControlReplyFrame(builder, ControlReplyMsg.Heartbeat, body));

    Reply.Beat read =
        (Reply.Beat) Frames.readControlReply(ByteBuffer.wrap(builder.sizedByteArray()));
    assertEquals(new Reply.Beat(3, 4, 500, 2, 6, 41), read);
  }

  @Test
  void aFullDeltaAndTheLargestChunkFitWhatTheNodeReads() {
    List<WorldEvent.BlockChanged> cells = new ArrayList<>();
    for (int i = 0; i < Frames.MAX_CELLS_PER_DELTA; i++) {
      cells.add(
          new WorldEvent.BlockChanged(
              0, new Pos(i % 512, -1000, i / 512), (byte) PathType.FENCE, Cells.MAX_FLOOR));
    }
    assertTrue(Frames.delta(1, cells, 0, 0).length <= Frames.MAX_FRAME_BYTES);
    cells.add(cells.get(0));
    assertThrows(IllegalArgumentException.class, () -> Frames.delta(1, cells, 0, 0));

    // The tallest world Minecraft allows, with every block in a run of its own.
    WorldSource striped = source(-2032, 4064, (x, y, z) -> y % 2 == 0 ? 16 : 0);
    byte[] chunk = Frames.chunkSnapshot(1, 0, 0, Frames.readChunk(striped, 0, 0));
    assertTrue(chunk.length <= Frames.MAX_FRAME_BYTES, chunk.length + " bytes");
  }

  @Test
  void aCellTheWireCannotCarryIsRefusedWhenTheChunkIsRead() {
    WorldSource tooTall = source(0, 32, (x, y, z) -> Cells.MAX_FLOOR + 1);
    assertThrows(IllegalArgumentException.class, () -> Frames.readChunk(tooTall, 0, 0));
    WorldSource negative = source(0, 32, (x, y, z) -> -1);
    assertThrows(IllegalArgumentException.class, () -> Frames.readChunk(negative, 0, 0));
  }

  @Test
  void aChunkTheWorldCannotReadNowIsNotRead() {
    WorldSource leaving =
        new WorldSource() {
          @Override
          public MobClass mobClass(short classId) {
            return null;
          }

          @Override
          public int minY() {
            return 0;
          }

          @Override
          public int height() {
            return 32;
          }

          @Override
          public byte pathTypeAt(int x, int y, int z) {
            throw new AssertionError("a block was read");
          }

          @Override
          public int floorAt(int x, int y, int z) {
            throw new AssertionError("a block was read");
          }

          @Override
          public boolean readable(int chunkX, int chunkZ) {
            return false;
          }
        };
    assertEquals(null, Frames.readChunk(leaving, 0, 0));
  }

  @Test
  void aChunkReadKeepsNothingOfTheChunkReadBeforeIt() {
    // Reads reuse their buffers, so each result must stand alone.
    WorldSource ground = source(0, 32, (x, y, z) -> y < 8 ? 16 : 0);
    WorldSource striped = source(0, 32, (x, y, z) -> y % 2 == 0 ? 16 : 0);
    byte[] alone = Frames.chunkSnapshot(1, 0, 0, Frames.readChunk(ground, 0, 0));
    Frames.ChunkRuns first = Frames.readChunk(ground, 0, 0);
    Frames.readChunk(striped, 0, 0);
    assertArrayEquals(alone, Frames.chunkSnapshot(1, 0, 0, first));
    // A read that fails partway leaves nothing behind either.
    assertThrows(
        IllegalArgumentException.class,
        () -> Frames.readChunk(source(0, 32, (x, y, z) -> y < 20 ? 16 : -1), 0, 0));
    assertArrayEquals(alone, Frames.chunkSnapshot(1, 0, 0, Frames.readChunk(ground, 0, 0)));
  }

  @Test
  void everySearchInputIsCarriedOnTheWire() {
    // Distinct values, so a field written into its neighbour's slot is detected.
    WorldEvent.Search search = new WorldEvent.Search(6, 40.5f, 640, 300);
    Frames.Request request =
        new Frames.Request(9, (short) 2, new Pos(3, 40, 5), new Pos(7, 41, 11), search);
    ByteBuffer frame = ByteBuffer.wrap(Frames.requests(RUN, 3, List.of(request), 0, 0));

    RequestFrame root = RequestFrame.getRootAsRequestFrame(frame);
    assertEquals(RUN, root.run());
    assertEquals(3, root.regionId());
    PathRequestBatch batch = (PathRequestBatch) root.body(new PathRequestBatch());
    assertEquals(1, batch.requestsLength());
    PathRequest decoded = batch.requests(0);
    assertEquals(9, decoded.requestId());
    assertEquals(2, decoded.classId());
    assertEquals(search.reachRange(), decoded.reachRange());
    assertEquals(search.maxPathLength(), decoded.maxPathLength());
    assertEquals(search.maxVisitedNodes(), decoded.maxVisitedNodes());
    assertEquals(search.maxFallDistance(), decoded.maxFallDistance());
  }

  @Test
  void positionsLeaveRelativeToTheAreaCorner() {
    Frames.Request request =
        new Frames.Request(1, (short) 1, new Pos(-30, 64, 1050), new Pos(-17, 70, 1040), SEARCH);
    ByteBuffer frame = ByteBuffer.wrap(Frames.requests(RUN, 0, List.of(request), -32, 1024));

    PathRequestBatch batch =
        (PathRequestBatch) RequestFrame.getRootAsRequestFrame(frame).body(new PathRequestBatch());
    PathRequest decoded = batch.requests(0);
    assertEquals(new Pos(2, 64, 26), pos(decoded.start()));
    assertEquals(new Pos(15, 70, 16), pos(decoded.target()));
  }

  @Test
  void aResponseCarriesThePathInOrderWithItsTypes() {
    List<WorldOutput.Node> nodes =
        List.of(
            new WorldOutput.Node(new Pos(100, 64, 200), (byte) PathType.WALKABLE),
            new WorldOutput.Node(new Pos(101, 65, 200), (byte) PathType.WATER_BORDER),
            new WorldOutput.Node(new Pos(102, 65, 201), (byte) PathType.DOOR_OPEN));
    ByteBuffer frame = ByteBuffer.wrap(Frames.response(RUN, 7, true, nodes, 3.25f, 12, 96, 192));

    ResponseFrame root = ResponseFrame.getRootAsResponseFrame(frame);
    assertEquals(RUN, root.run());
    PathResponse decoded = (PathResponse) root.body(new PathResponse());
    assertEquals(7, decoded.requestId());
    assertEquals(true, decoded.reached());
    assertEquals(3.25f, decoded.cost());
    assertEquals(12, decoded.worldSequence());
    assertEquals(nodes.size(), decoded.nodesLength());
    for (int i = 0; i < nodes.size(); i++) {
      Pos pos = nodes.get(i).pos();
      maestro.fbs.PathNode node = decoded.nodes(i);
      assertEquals(pos.x() - 96, node.pos().x());
      assertEquals(pos.y(), node.pos().y());
      assertEquals(pos.z() - 192, node.pos().z());
      assertEquals(nodes.get(i).pathType(), (byte) node.type());
    }
  }

  @Test
  void anAreaIsWholeChunks() {
    WorldSource world = flat();
    ByteBuffer frame = ByteBuffer.wrap(Frames.assignment(3, 4, 96, 384, 17, false, world));
    RegionAssignment decoded =
        (RegionAssignment) WorldFrame.getRootAsWorldFrame(frame).body(new RegionAssignment());
    assertEquals(3, decoded.run());
    assertEquals(4, decoded.regionId());
    assertEquals(96, decoded.sizeX());
    assertEquals(384, decoded.sizeZ());
    assertEquals(17, decoded.sequence());
    assertEquals(world.minY(), decoded.minY());
    assertEquals(world.height(), decoded.height());

    assertThrows(
        IllegalArgumentException.class, () -> Frames.assignment(3, 4, 40, 96, 0, false, world));
    assertThrows(
        IllegalArgumentException.class, () -> Frames.assignment(3, 4, 96, 40, 0, false, world));
    assertThrows(
        IllegalArgumentException.class, () -> Frames.assignment(3, 4, 0, 96, 0, false, world));
    assertThrows(
        IllegalArgumentException.class, () -> Frames.assignment(3, 4, 96, 0, 0, false, world));
    // The node indexes the area with shorts.
    assertThrows(
        IllegalArgumentException.class, () -> Frames.assignment(3, 4, 32768, 96, 0, false, world));
    assertThrows(
        IllegalArgumentException.class, () -> Frames.assignment(3, 4, 96, 32768, 0, false, world));
    WorldSource tooHigh = source(32000, 1024, (x, y, z) -> 0);
    assertThrows(
        IllegalArgumentException.class, () -> Frames.assignment(3, 4, 96, 96, 0, false, tooHigh));
    assertThrows(
        IllegalArgumentException.class, () -> Frames.assignment(3, 4, 96, 96, -1, false, world));
  }

  @Test
  void uniformSectionsGiveTheSameChunkWithFarFewerReads() {
    int[] plainReads = {0};
    int[] sectionReads = {0};
    byte[] expected =
        Frames.chunkSnapshot(
            1, 0, 0, Frames.readChunk(counting(terrain(false), plainReads), 2, -1));
    byte[] actual =
        Frames.chunkSnapshot(
            1, 0, 0, Frames.readChunk(counting(terrain(true), sectionReads), 2, -1));

    assertArrayEquals(expected, actual);
    assertEquals(256 * 384 * 2, plainReads[0]);
    // Only the section holding the surface and the post is read block by block.
    assertEquals(256 * 16 * 2, sectionReads[0]);
  }

  @Test
  void sectionsReadWholeGiveTheSameChunkWithoutReadingBlocks() {
    WorldSource blocks = mixed(-64, 384);
    int[] reads = {0};
    byte[] expected = Frames.chunkSnapshot(1, 0, 0, Frames.readChunk(blocks, 3, -2));
    byte[] actual =
        Frames.chunkSnapshot(
            1,
            0,
            0,
            Frames.readChunk(counting(readingSections(blocks, section -> true), reads), 3, -2));

    assertArrayEquals(expected, actual);
    assertEquals(0, reads[0]);
  }

  @Test
  void aSectionNotReadWholeIsReadBlockByBlock() {
    // Every other section is read whole, and the blocks above the last whole section one by one.
    WorldSource blocks = mixed(-8, 5 * 16 + 7);
    int[] reads = {0};
    byte[] expected = Frames.chunkSnapshot(1, 0, 0, Frames.readChunk(blocks, -1, 4));
    byte[] actual =
        Frames.chunkSnapshot(
            1,
            0,
            0,
            Frames.readChunk(
                counting(readingSections(blocks, section -> section % 2 == 0), reads), -1, 4));

    assertArrayEquals(expected, actual);
    assertEquals(256 * (2 * 16 + 7) * 2, reads[0]);
  }

  @Test
  void aCellTheWireCannotCarryIsRefusedWhenASectionIsReadWhole() {
    WorldSource tooTall =
        readingSections(source(0, 32, (x, y, z) -> Cells.MAX_FLOOR + 1), section -> true);
    assertThrows(IllegalArgumentException.class, () -> Frames.readChunk(tooTall, 0, 0));
    WorldSource notPerBlock =
        readingSections(
            new WorldSource() {
              @Override
              public MobClass mobClass(short classId) {
                return null;
              }

              @Override
              public int minY() {
                return 0;
              }

              @Override
              public int height() {
                return 32;
              }

              @Override
              public byte pathTypeAt(int x, int y, int z) {
                return (byte) PathType.WALKABLE;
              }

              @Override
              public int floorAt(int x, int y, int z) {
                return 0;
              }
            },
            section -> true);
    assertThrows(IllegalArgumentException.class, () -> Frames.readChunk(notPerBlock, 0, 0));
  }

  private static WorldSource counting(WorldSource inner, int[] reads) {
    return new WorldSource() {
      @Override
      public MobClass mobClass(short classId) {
        return null;
      }

      @Override
      public int minY() {
        return inner.minY();
      }

      @Override
      public int height() {
        return inner.height();
      }

      @Override
      public byte pathTypeAt(int x, int y, int z) {
        reads[0]++;
        return inner.pathTypeAt(x, y, z);
      }

      @Override
      public int floorAt(int x, int y, int z) {
        reads[0]++;
        return inner.floorAt(x, y, z);
      }

      @Override
      public int uniformCell(int chunkX, int chunkZ, int section) {
        return inner.uniformCell(chunkX, chunkZ, section);
      }

      @Override
      public boolean readSection(int chunkX, int chunkZ, int section, int[] cells) {
        return inner.readSection(chunkX, chunkZ, section, cells);
      }
    };
  }

  /** {@code inner}, but reading the sections {@code whole} picks in one call. */
  private static WorldSource readingSections(WorldSource inner, IntPredicate whole) {
    return new WorldSource() {
      @Override
      public MobClass mobClass(short classId) {
        return null;
      }

      @Override
      public int minY() {
        return inner.minY();
      }

      @Override
      public int height() {
        return inner.height();
      }

      @Override
      public byte pathTypeAt(int x, int y, int z) {
        return inner.pathTypeAt(x, y, z);
      }

      @Override
      public int floorAt(int x, int y, int z) {
        return inner.floorAt(x, y, z);
      }

      @Override
      public boolean readSection(int chunkX, int chunkZ, int section, int[] cells) {
        if (!whole.test(section)) {
          return false;
        }
        int minY = minY() + section * SECTION_HEIGHT;
        for (int dx = 0; dx < Chunks.SIZE; dx++) {
          for (int dz = 0; dz < Chunks.SIZE; dz++) {
            for (int dy = 0; dy < SECTION_HEIGHT; dy++) {
              int x = chunkX * Chunks.SIZE + dx;
              int z = chunkZ * Chunks.SIZE + dz;
              cells[(dx * Chunks.SIZE + dz) * SECTION_HEIGHT + dy] =
                  (inner.floorAt(x, minY + dy, z) << 8) | inner.pathTypeAt(x, minY + dy, z);
            }
          }
        }
        return true;
      }
    };
  }

  /** Runs of every length and several cells, which differ between neighbouring columns. */
  private static WorldSource mixed(int minY, int height) {
    return new WorldSource() {
      private int cell(int x, int y, int z) {
        return switch (Math.floorMod(x * 73 + z * 31 + x * z + (y >> 2) * 17 + (y >> 5), 7)) {
          case 0, 1 -> (Cells.FULL_FLOOR << 8) | PathType.BLOCKED;
          case 2 -> (24 << 8) | PathType.FENCE;
          case 3 -> PathType.WATER;
          case 4 -> (8 << 8) | PathType.BLOCKED;
          default -> PathType.OPEN;
        };
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
        return (byte) cell(x, y, z);
      }

      @Override
      public int floorAt(int x, int y, int z) {
        return cell(x, y, z) >> 8;
      }
    };
  }

  /** Solid up to y = 5 and open above, with a fence post. */
  private static WorldSource terrain(boolean reportUniform) {
    return new WorldSource() {
      private byte type(int x, int y, int z) {
        if (x == 35 && z == -12 && y == 6) {
          return (byte) PathType.FENCE;
        }
        return (byte) (y <= 5 ? PathType.BLOCKED : PathType.OPEN);
      }

      private int floor(int x, int y, int z) {
        if (x == 35 && z == -12 && y == 6) {
          return 24;
        }
        return y <= 5 ? 16 : 0;
      }

      @Override
      public MobClass mobClass(short classId) {
        return null;
      }

      @Override
      public int minY() {
        return -64;
      }

      @Override
      public int height() {
        return 384;
      }

      @Override
      public byte pathTypeAt(int x, int y, int z) {
        return type(x, y, z);
      }

      @Override
      public int floorAt(int x, int y, int z) {
        return floor(x, y, z);
      }

      @Override
      public int uniformCell(int chunkX, int chunkZ, int section) {
        if (!reportUniform) {
          return NOT_UNIFORM;
        }
        int low = minY() + section * SECTION_HEIGHT;
        int high = low + SECTION_HEIGHT - 1;
        // The section holding the surface and the post (y 0..15) is mixed.
        if (low <= 6 && high >= 5) {
          return NOT_UNIFORM;
        }
        return (floor(0, low, 0) << 8) | type(0, low, 0);
      }
    };
  }

  /** The floor of a block, from its position. */
  @FunctionalInterface
  private interface Floors {
    int at(int x, int y, int z);
  }

  /** A column from {@code minY} of {@code height} blocks, solid where {@code floors} is not 0. */
  private static WorldSource source(int minY, int height, Floors floors) {
    return new WorldSource() {
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
        return (byte) (floors.at(x, y, z) == 0 ? PathType.OPEN : PathType.BLOCKED);
      }

      @Override
      public int floorAt(int x, int y, int z) {
        return floors.at(x, y, z);
      }
    };
  }

  private static Pos pos(maestro.fbs.Vec3s wire) {
    return new Pos(wire.x(), wire.y(), wire.z());
  }

  private static WorldSource flat() {
    return new WorldSource() {
      @Override
      public MobClass mobClass(short classId) {
        return null;
      }

      @Override
      public int minY() {
        return -64;
      }

      @Override
      public int height() {
        return 384;
      }

      @Override
      public byte pathTypeAt(int x, int y, int z) {
        return (byte) (y < 0 ? PathType.BLOCKED : PathType.OPEN);
      }

      @Override
      public int floorAt(int x, int y, int z) {
        return y < 0 ? 16 : 0;
      }
    };
  }
}
