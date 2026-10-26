package io.github.davidmenggx.maestro.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaestroConfigTest {

  @Test
  void writesDefaultsWhenMissing(@TempDir Path dir) throws IOException {
    Path path = dir.resolve(MaestroConfig.FILE_NAME);

    assertEquals(MaestroConfig.DEFAULT, MaestroConfig.load(path));

    // The written file must round-trip.
    assertTrue(Files.exists(path));
    assertEquals(MaestroConfig.DEFAULT, MaestroConfig.load(path));
  }

  @Test
  void readsExplicitSettings(@TempDir Path dir) throws IOException {
    Path path =
        write(
            dir,
            """
            {
              "mode": "stateless",
              "subMode": "stream",
              "offload": "shadow",
              "nodes": [
                {"host": "10.0.0.5", "worldPort": 1, "controlPort": 2, "requestPort": 3},
                {"host": "node-2"}
              ],
              "nodeCapacity": 40,
              "areaCells": 9,
              "spares": 2,
              "otlpEndpoint": "http://collector:4317"
            }
            """);

    MaestroConfig config = MaestroConfig.load(path);

    assertEquals(MaestroConfig.Mode.STATELESS, config.mode());
    assertEquals(MaestroConfig.SubMode.STREAM, config.subMode());
    assertEquals(MaestroConfig.Offload.SHADOW, config.offload());
    assertEquals(
        List.of(
            new MaestroConfig.NodeAddress("10.0.0.5", 1, 2, 3),
            // A port left out is the node's default.
            new MaestroConfig.NodeAddress(
                "node-2",
                MaestroConfig.NodeAddress.DEFAULT_WORLD_PORT,
                MaestroConfig.NodeAddress.DEFAULT_CONTROL_PORT,
                MaestroConfig.NodeAddress.DEFAULT_REQUEST_PORT)),
        config.nodes());
    assertEquals(40, config.nodeCapacity());
    assertEquals(9, config.areaCells());
    assertEquals(2, config.spares());
    assertEquals("http://collector:4317", config.otlpEndpoint());
  }

  @Test
  void theDefaultCoversFiveCellsAroundTheOriginAndKeepsOneSpare() {
    assertEquals(5, MaestroConfig.DEFAULT.areaCells());
    assertEquals(1, MaestroConfig.DEFAULT.spares());
  }

  @Test
  void theDefaultOffloadsNothing() {
    assertEquals(List.of(), MaestroConfig.DEFAULT.nodes());
  }

  @Test
  void aMissingKeyTakesItsDefault(@TempDir Path dir) throws IOException {
    MaestroConfig partial =
        MaestroConfig.load(
            write(
                dir,
                """
                {"mode": "stateless"}
                """));
    assertEquals(MaestroConfig.Mode.STATELESS, partial.mode());
    assertEquals(MaestroConfig.DEFAULT.subMode(), partial.subMode());
    assertEquals(MaestroConfig.DEFAULT.otlpEndpoint(), partial.otlpEndpoint());

    assertEquals(MaestroConfig.DEFAULT, MaestroConfig.load(write(dir, "{}")));
  }

  @Test
  void rejectsAFileItCannotUse(@TempDir Path dir) throws IOException {
    List<String> unusable =
        List.of(
            // Unknown enum value, null, wrong type, blank endpoint, truncated JSON, empty file,
            // non-object.
            """
            {"mode": "sharded", "subMode": "batch", "otlpEndpoint": "http://localhost:4317"}
            """,
            """
            {"mode": null}
            """,
            """
            {"subMode": ["batch"]}
            """,
            """
            {"mode": "stateful", "subMode": "batch", "otlpEndpoint": "  "}
            """,
            "{\"mode\": ",
            "",
            "[]",
            // Unknown offload, no host, port out of range, capacity below one, empty list.
            """
            {"offload": "maybe"}
            """,
            """
            {"nodes": [{"worldPort": 1}]}
            """,
            """
            {"nodes": [{"host": "a", "requestPort": 70000}]}
            """,
            """
            {"nodeCapacity": 0}
            """,
            """
            {"nodes": [null]}
            """,
            // A square of no cells, one too large for the wire, and negative spares.
            """
            {"areaCells": 0}
            """,
            """
            {"areaCells": 128}
            """,
            """
            {"spares": -1}
            """);

    for (String json : unusable) {
      Path path = write(dir, json);
      assertThrows(IllegalStateException.class, () -> MaestroConfig.load(path), json);
    }
  }

  @Test
  void rejectsAKeyThatIsNotASetting(@TempDir Path dir) throws IOException {
    // A setting that was removed, or a misspelled one, must not be ignored silently.
    Path path =
        write(
            dir,
            """
            {
              "mode": "stateful",
              "subMode": "batch",
              "otlpEndpoint": "http://localhost:4317",
              "devMode": true
            }
            """);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> MaestroConfig.load(path));
    assertTrue(thrown.getMessage().contains("devMode"), thrown.getMessage());

    // Inside a node too.
    Path node =
        write(
            dir,
            """
            {"nodes": [{"host": "a", "worldport": 1}]}
            """);
    thrown = assertThrows(IllegalStateException.class, () -> MaestroConfig.load(node));
    assertTrue(thrown.getMessage().contains("worldport"), thrown.getMessage());
  }

  private static Path write(Path dir, String json) throws IOException {
    Path path = dir.resolve(MaestroConfig.FILE_NAME);
    Files.writeString(path, json);
    return path;
  }
}
