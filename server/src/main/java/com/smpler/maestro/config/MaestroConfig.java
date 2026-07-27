package com.smpler.maestro.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Startup settings read from `<config dir>/maestro.json`. A missing file is written with the
 * defaults below; a malformed or incomplete one aborts startup rather than silently running the
 * fleet in the wrong mode.
 */
public record MaestroConfig(Mode mode, SubMode subMode, String otlpEndpoint, boolean devMode) {

  /** How compute nodes hold world state. */
  public enum Mode {
    @SerializedName("stateful")
    STATEFUL,
    @SerializedName("stateless")
    STATELESS
  }

  /** How pathfinding requests are dispatched to a stateful node. */
  public enum SubMode {
    @SerializedName("batch")
    BATCH,
    @SerializedName("stream")
    STREAM
  }

  public static final String FILE_NAME = "maestro.json";

  public static final MaestroConfig DEFAULT =
      new MaestroConfig(Mode.STATEFUL, SubMode.BATCH, "http://localhost:4317", true);

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  public MaestroConfig {
    require(mode != null, "`mode` must be \"stateful\" or \"stateless\"");
    require(subMode != null, "`subMode` must be \"batch\" or \"stream\"");
    require(otlpEndpoint != null && !otlpEndpoint.isBlank(), "`otlpEndpoint` must not be empty");
  }

  public static MaestroConfig load(Path path) {
    if (!Files.exists(path)) {
      write(path, DEFAULT);
      return DEFAULT;
    }

    try (Reader reader = Files.newBufferedReader(path)) {
      MaestroConfig config = GSON.fromJson(reader, MaestroConfig.class);
      require(config != null, "file is empty");
      return config;
    } catch (IOException e) {
      throw new IllegalStateException("Could not read Maestro config at " + path, e);
    } catch (RuntimeException e) {
      // Gson reports a rejected record component by wrapping the canonical
      // constructor's exception, so the useful message is on the cause.
      throw new IllegalStateException(
          "Invalid Maestro config at " + path + ": " + rootCause(e).getMessage(), e);
    }
  }

  private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static void write(Path path, MaestroConfig config) {
    try {
      Files.createDirectories(path.getParent());
      try (Writer writer = Files.newBufferedWriter(path)) {
        GSON.toJson(config, writer);
        writer.write('\n');
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not write default Maestro config to " + path, e);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
