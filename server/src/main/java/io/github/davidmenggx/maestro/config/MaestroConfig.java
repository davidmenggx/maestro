package io.github.davidmenggx.maestro.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Startup settings read from `<config dir>/maestro.json`. A missing file is created with defaults
 * and a missing key takes its default. A malformed file, an unknown key or a bad value aborts
 * startup.
 *
 * <p>{@code nodes} lists the compute fleet. With none, nothing is offloaded. {@code nodeCapacity}
 * is the requests a node serves per tick, as admission assumes until the node's heartbeats show it
 * serves fewer; it is never taken to serve more. {@code areaCells} is the side, in 256-block cells,
 * of the square around the origin whose mobs are offloaded. {@code spares} is how many nodes start
 * with no region, to take over a dead node's or relieve a hot one. {@code traceSampleRate} is the
 * share of offloaded requests traced, 0 to 1. Each trace is five spans built on the server thread,
 * and spans past the export queue are dropped, so a high rate suits short trials only.
 */
public record MaestroConfig(
    SubMode subMode,
    Offload offload,
    List<NodeAddress> nodes,
    int nodeCapacity,
    int areaCells,
    int spares,
    String otlpEndpoint,
    double traceSampleRate) {

  /** How pathfinding requests are dispatched to a node. */
  public enum SubMode {
    @SerializedName("batch")
    BATCH,
    @SerializedName("stream")
    STREAM
  }

  /** What an offloaded search returns. */
  public enum Offload {
    /** Vanilla's path. The fleet's answer is only compared with it. */
    @SerializedName("shadow")
    SHADOW,
    /** The fleet's answer. */
    @SerializedName("on")
    ON
  }

  /**
   * Where one compute node listens. A port left out, or 0, is the node's default for that stream.
   */
  public record NodeAddress(String host, int worldPort, int controlPort, int requestPort) {

    public static final int DEFAULT_WORLD_PORT = 25700;
    public static final int DEFAULT_CONTROL_PORT = 25701;
    public static final int DEFAULT_REQUEST_PORT = 25702;

    public NodeAddress {
      require(host != null && !host.isBlank(), "a node's `host` must not be empty");
      worldPort = port(worldPort, DEFAULT_WORLD_PORT, "worldPort");
      controlPort = port(controlPort, DEFAULT_CONTROL_PORT, "controlPort");
      requestPort = port(requestPort, DEFAULT_REQUEST_PORT, "requestPort");
    }

    private static int port(int value, int fallback, String name) {
      require(value >= 0 && value <= 0xFFFF, "a node's `" + name + "` must be a port: " + value);
      return value == 0 ? fallback : value;
    }
  }

  public static final String FILE_NAME = "maestro.json";

  public static final MaestroConfig DEFAULT =
      new MaestroConfig(
          SubMode.BATCH, Offload.ON, List.of(), 100, 5, 1, "http://localhost:4317", 0.01);

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private static final Set<String> SETTINGS = componentNames(MaestroConfig.class);

  private static final Set<String> NODE_SETTINGS = componentNames(NodeAddress.class);

  public MaestroConfig {
    require(subMode != null, "`subMode` must be \"batch\" or \"stream\"");
    require(offload != null, "`offload` must be \"shadow\" or \"on\"");
    require(nodes != null, "`nodes` must be a list");
    for (NodeAddress node : nodes) {
      require(node != null, "`nodes` must not hold null");
    }
    nodes = List.copyOf(nodes);
    require(nodeCapacity >= 1, "`nodeCapacity` must be at least 1");
    require(
        areaCells >= 1 && areaCells <= RegionLayout.MAX_CELLS,
        "`areaCells` must be 1 to " + RegionLayout.MAX_CELLS);
    require(spares >= 0, "`spares` must not be negative");
    require(otlpEndpoint != null && !otlpEndpoint.isBlank(), "`otlpEndpoint` must not be empty");
    require(traceSampleRate >= 0 && traceSampleRate <= 1, "`traceSampleRate` must be 0 to 1");
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
        // Gson silently ignores unknown keys.
        require(SETTINGS.contains(setting.getKey()), "`" + setting.getKey() + "` is not a setting");
        settings.add(setting.getKey(), setting.getValue());
      }
      if (settings.get("nodes") instanceof JsonArray nodes) {
        for (JsonElement node : nodes) {
          if (node instanceof JsonObject object) {
            for (String key : object.keySet()) {
              require(NODE_SETTINGS.contains(key), "`" + key + "` is not a node setting");
            }
          }
        }
      }
      return GSON.fromJson(settings, MaestroConfig.class);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read Maestro config at " + path, e);
    } catch (RuntimeException e) {
      // Gson wraps the constructor's exception; the useful message is on the cause.
      throw new IllegalStateException(
          "Invalid Maestro config at " + path + ": " + rootCause(e).getMessage(), e);
    }
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    return Arrays.stream(type.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toUnmodifiableSet());
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
