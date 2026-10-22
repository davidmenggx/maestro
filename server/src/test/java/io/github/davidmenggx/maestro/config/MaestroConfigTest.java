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

    // The written file must round-trip, otherwise the second boot fails.
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
              "otlpEndpoint": "http://collector:4317"
            }
            """);

    MaestroConfig config = MaestroConfig.load(path);

    assertEquals(MaestroConfig.Mode.STATELESS, config.mode());
    assertEquals(MaestroConfig.SubMode.STREAM, config.subMode());
    assertEquals("http://collector:4317", config.otlpEndpoint());
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
            // An unknown enum value, an explicit null, a value of the wrong type, a blank
            // endpoint, truncated JSON, an empty file, and a value that is not an object.
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
            "[]");

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
  }

  private static Path write(Path dir, String json) throws IOException {
    Path path = dir.resolve(MaestroConfig.FILE_NAME);
    Files.writeString(path, json);
    return path;
  }
}
