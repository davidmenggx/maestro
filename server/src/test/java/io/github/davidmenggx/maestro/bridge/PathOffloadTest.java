package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PathOffloadTest {

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @Test
  void anAnswerBecomesThePathVanillasSearchWouldHaveReturned() {
    WorldOutput.PathResult answer =
        new WorldOutput.PathResult(
            7,
            new Pos(3, 64, 0),
            true,
            List.of(
                new WorldOutput.Node(new Pos(0, 64, 0), (byte) PathType.WALKABLE.ordinal()),
                new WorldOutput.Node(new Pos(1, 64, 0), (byte) PathType.DOOR_OPEN.ordinal()),
                new WorldOutput.Node(new Pos(3, 64, 0), (byte) PathType.WALKABLE.ordinal())),
            2.5f,
            40);

    Path path = PathOffload.toPath(answer);

    assertEquals(3, path.getNodeCount());
    assertEquals(new BlockPos(1, 64, 0), path.getNodePos(1));
    assertEquals(PathType.DOOR_OPEN, path.getNode(1).type);
    assertEquals(new BlockPos(3, 64, 0), path.getTarget());
    assertTrue(path.canReach());
    assertEquals(0f, path.getDistToTarget());
    // The navigation trims a path it follows, so its nodes must be mutable.
    path.truncateNodes(1);
    assertEquals(1, path.getNodeCount());
  }

  @Test
  void anAnswerWithNoNodesIsNoPath() {
    WorldOutput.PathResult answer =
        new WorldOutput.PathResult(7, new Pos(3, 64, 0), false, List.of(), 0f, 40);

    assertNull(PathOffload.toPath(answer));
  }
}
