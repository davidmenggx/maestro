package io.github.davidmenggx.maestro.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.pathfinder.PathType;
import org.junit.jupiter.api.Test;

class PathTypeMirrorTest {

  @Test
  void schemaOrdinalsMatchVanilla() {
    // A cell carries `PathType.ordinal()` as is, so the mirrored enum must list
    // the same names in the same order.
    List<String> vanilla = Arrays.stream(PathType.values()).map(Enum::name).toList();
    assertEquals(vanilla, List.of(maestro.fbs.PathType.names));
  }
}
