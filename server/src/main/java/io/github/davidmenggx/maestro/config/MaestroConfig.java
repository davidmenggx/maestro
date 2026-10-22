package io.github.davidmenggx.maestro.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Startup settings read from `<config dir>/maestro.json`. A missing file is created with the
 * defaults below, and a missing key takes its default, so a file written before a setting was added
 * still loads. A malformed file, or one with a key that is not a setting or a value a setting
 * cannot take, aborts startup.
 */
public record MaestroConfig(Mode mode, SubMode subMode, String otlpEndpoint) {

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
      new MaestroConfig(Mode.STATEFUL, SubMode.BATCH, "http://localhost:4317");

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private static final Set<String> SETTINGS =
      Arrays.stream(MaestroConfig.class.getRecordComponents())
          .map(RecordComponent::getName)
          .collect(Collectors.toUnmodifiableSet());

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
      JsonElement json = JsonParser.parseReader(reader);
      require(!json.isJsonNull(), "file is empty");
      require(json.isJsonObject(), "file is not a JSON object");
      JsonObject settings = GSON.toJsonTree(DEFAULT).getAsJsonObject();
      for (Map.Entry<String, JsonElement> setting : json.getAsJsonObject().entrySet()) {
        // Gson ignores a key it does not know, so a misspelled or retired setting would
        // otherwise be dropped without a word.
        require(SETTINGS.contains(setting.getKey()), "`" + setting.getKey() + "` is not a setting");
        settings.add(setting.getKey(), setting.getValue());
      }
      return GSON.fromJson(settings, MaestroConfig.class);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read Maestro config at " + path, e);
    } catch (RuntimeException e) {
      // Gson wraps the canonical constructor's exception when it rejects a record
      // component, so the useful message is on the cause.
      throw new IllegalStateException(
          "Invalid Maestro config at " + path + ": " + rootCause(e).getMessage(), e);
    }
  }

  private static Throwable rootCause(Throwable thrown) {
    Throwable cause = thrown;
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
