package com.smpler.maestro.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
              "otlpEndpoint": "http://collector:4317",
              "devMode": false
            }
            """);

    MaestroConfig config = MaestroConfig.load(path);

    assertEquals(MaestroConfig.Mode.STATELESS, config.mode());
    assertEquals(MaestroConfig.SubMode.STREAM, config.subMode());
    assertEquals("http://collector:4317", config.otlpEndpoint());
    assertEquals(false, config.devMode());
  }

  @Test
  void rejectsUnknownMode(@TempDir Path dir) throws IOException {
    Path path =
        write(
            dir,
            """
            {"mode": "sharded", "subMode": "batch", "otlpEndpoint": "http://localhost:4317"}
            """);

    assertThrows(IllegalStateException.class, () -> MaestroConfig.load(path));
  }

  @Test
  void rejectsMissingField(@TempDir Path dir) throws IOException {
    Path path =
        write(
            dir,
            """
            {"mode": "stateful", "otlpEndpoint": "http://localhost:4317"}
            """);

    assertThrows(IllegalStateException.class, () -> MaestroConfig.load(path));
  }

  @Test
  void rejectsBlankEndpoint(@TempDir Path dir) throws IOException {
    Path path =
        write(
            dir,
            """
            {"mode": "stateful", "subMode": "batch", "otlpEndpoint": "  "}
            """);

    assertThrows(IllegalStateException.class, () -> MaestroConfig.load(path));
  }

  @Test
  void rejectsMalformedJson(@TempDir Path dir) throws IOException {
    Path path = write(dir, "{\"mode\": ");

    assertThrows(IllegalStateException.class, () -> MaestroConfig.load(path));
  }

  private static Path write(Path dir, String json) throws IOException {
    Path path = dir.resolve(MaestroConfig.FILE_NAME);
    Files.writeString(path, json);
    return path;
  }
}
