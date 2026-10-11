package io.github.davidmenggx.maestro.controller.wire;

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
