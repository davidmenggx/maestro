package io.github.davidmenggx.maestro.controller.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.nio.ByteBuffer;
import java.util.List;
import maestro.fbs.PathRequest;
import maestro.fbs.PathRequestBatch;
import maestro.fbs.PathResponse;
import maestro.fbs.PathType;
import maestro.fbs.RegionAssignment;
import maestro.fbs.RequestFrame;
import maestro.fbs.ResponseFrame;
import maestro.fbs.WorldFrame;
import org.junit.jupiter.api.Test;

class FramesTest {

  private static final WorldEvent.Search SEARCH = new WorldEvent.Search(0, 16f, 256, 3);

  @Test
  void aValueTooWideForItsWireFieldIsRefusedRatherThanWrapped() {
    assertEquals((short) -32768, Frames.int16(Short.MIN_VALUE));
    assertThrows(IllegalArgumentException.class, () -> Frames.int16(Short.MAX_VALUE + 1));
    assertEquals(0xFFFF, Frames.uint16(0xFFFF));
    assertThrows(IllegalArgumentException.class, () -> Frames.uint16(0x10000));
    assertThrows(IllegalArgumentException.class, () -> Frames.uint16(-1));

    Frames.Request farAway =
        new Frames.Request(1, (short) 1, new Pos(40_000, 40, 0), new Pos(0, 40, 0), SEARCH);
    assertThrows(IllegalArgumentException.class, () -> Frames.requests(List.of(farAway), 0, 0));
  }

  @Test
  void everySearchInputIsCarriedOnTheWire() {
    // Distinct values, so a field written into its neighbour's slot is detected.
    WorldEvent.Search search = new WorldEvent.Search(6, 40.5f, 640, 300);
    Frames.Request request =
        new Frames.Request(9, (short) 2, new Pos(3, 40, 5), new Pos(7, 41, 11), search);
    ByteBuffer frame = ByteBuffer.wrap(Frames.requests(List.of(request), 0, 0));

    PathRequestBatch batch =
        (PathRequestBatch) RequestFrame.getRootAsRequestFrame(frame).body(new PathRequestBatch());
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
    ByteBuffer frame = ByteBuffer.wrap(Frames.requests(List.of(request), -32, 1024));

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
    ByteBuffer frame = ByteBuffer.wrap(Frames.response(7, true, nodes, 3.25f, 12, 96, 192));

    PathResponse decoded =
        (PathResponse) ResponseFrame.getRootAsResponseFrame(frame).body(new PathResponse());
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
    ByteBuffer frame = ByteBuffer.wrap(Frames.assignment(3, 4, 96, false, world));
    RegionAssignment decoded =
        (RegionAssignment) WorldFrame.getRootAsWorldFrame(frame).body(new RegionAssignment());
    assertEquals(96, decoded.size());
    assertEquals(world.minY(), decoded.minY());
    assertEquals(world.height(), decoded.height());

    assertThrows(IllegalArgumentException.class, () -> Frames.assignment(3, 4, 40, false, world));
    assertThrows(IllegalArgumentException.class, () -> Frames.assignment(3, 4, 0, false, world));
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
    };
  }

  /**
   * Solid up to y = 5 and open above, with a fence post. Uniform sections are reported if asked.
   */
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
