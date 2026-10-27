package io.github.davidmenggx.maestro.load;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A load run, read from `<name>.json`. A missing key takes its default; a malformed file, an
 * unknown key or a bad value is refused.
 *
 * <p>Each of {@code players} stands on the surface at its block, in creative mode, and loads the
 * chunks around it as a player would. Each of {@code groups} places {@code count} entities of type
 * {@code entity}, each built from the SNBT compound {@code nbt}, on the surface at blocks drawn
 * from {@code seed} within {@code spread} blocks of its {@code x} and {@code z} on either axis.
 * Coordinates are world blocks. The run waits {@code warmupSeconds} once every entity is placed,
 * then measures for {@code durationSeconds}, or until stopped if 0.
 */
public record Scenario(
    long seed, int warmupSeconds, int durationSeconds, List<Player> players, List<Group> groups) {

  public static final int MAX_PLAYERS = 64;

  public static final int MAX_COUNT = 100_000;

  public static final int MAX_SPREAD = 4096;

  /** Scenario names, so a name cannot leave the scenario directory. */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private static final Gson GSON = new Gson();

  private static final Set<String> SETTINGS = componentNames(Scenario.class);

  private static final Set<String> PLAYER_SETTINGS = componentNames(Player.class);

  private static final Set<String> GROUP_SETTINGS = componentNames(Group.class);

  public record Player(int x, int z) {}

  public record Group(String entity, int count, int x, int z, int spread, String nbt) {

    public Group {
      require(entity != null && !entity.isBlank(), "a group's `entity` must not be empty");
      require(count >= 1 && count <= MAX_COUNT, "a group's `count` must be 1 to " + MAX_COUNT);
      require(spread >= 0 && spread <= MAX_SPREAD, "a group's `spread` must be 0 to " + MAX_SPREAD);
      nbt = nbt == null ? "{}" : nbt;
    }
  }

  /** Where one entity stands: its group's index and its block. */
  public record Spawn(int group, int x, int z) {}

  public Scenario {
    require(warmupSeconds >= 0, "`warmupSeconds` must not be negative");
    require(durationSeconds >= 0, "`durationSeconds` must not be negative");
    players = players == null ? List.of() : players;
    groups = groups == null ? List.of() : groups;
    require(players.size() <= MAX_PLAYERS, "at most " + MAX_PLAYERS + " `players`");
    require(players.stream().allMatch(Objects::nonNull), "`players` must not hold null");
    require(groups.stream().allMatch(Objects::nonNull), "`groups` must not hold null");
    players = List.copyOf(players);
    groups = List.copyOf(groups);
  }

  /** The file holding scenario {@code name} in {@code dir}. */
  public static Path file(Path dir, String name) {
    require(NAME.matcher(name).matches(), "a scenario name is 1 to 64 of A-Z, a-z, 0-9, _ and -");
    return dir.resolve(name + ".json");
  }

  public static Scenario load(Path path) {
    try (Reader reader = Files.newBufferedReader(path)) {
      JsonElement json = JsonParser.parseReader(reader);
      require(json.isJsonObject(), "file is not a JSON object");
      JsonObject settings = json.getAsJsonObject();
      requireKnown(settings, SETTINGS, "`%s` is not a setting");
      requireKnownInEach(settings, "players", PLAYER_SETTINGS, "`%s` is not a player setting");
      requireKnownInEach(settings, "groups", GROUP_SETTINGS, "`%s` is not a group setting");
      return GSON.fromJson(settings, Scenario.class);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read scenario at " + path, e);
    } catch (RuntimeException e) {
      // Gson wraps the constructor's exception; the useful message is on the cause.
      throw new IllegalStateException(
          "Invalid scenario at " + path + ": " + rootCause(e).getMessage(), e);
    }
  }

  /** How many entities the groups place. */
  public int entities() {
    return groups.stream().mapToInt(Group::count).sum();
  }

  /** Where each entity stands, group by group. The same scenario gives the same places. */
  public List<Spawn> spawns() {
    Random random = new Random(seed);
    List<Spawn> spawns = new ArrayList<>(entities());
    for (int index = 0; index < groups.size(); index++) {
      Group group = groups.get(index);
      int side = 2 * group.spread() + 1;
      for (int i = 0; i < group.count(); i++) {
        int x = group.x() + random.nextInt(side) - group.spread();
        int z = group.z() + random.nextInt(side) - group.spread();
        spawns.add(new Spawn(index, x, z));
      }
    }
    return spawns;
  }

  private static void requireKnown(JsonObject object, Set<String> known, String message) {
    for (String key : object.keySet()) {
      require(known.contains(key), String.format(message, key));
    }
  }

  private static void requireKnownInEach(
      JsonObject settings, String list, Set<String> known, String message) {
    if (settings.get(list) != null && settings.get(list).isJsonArray()) {
      for (JsonElement element : settings.getAsJsonArray(list)) {
        if (element.isJsonObject()) {
          requireKnown(element.getAsJsonObject(), known, message);
        }
      }
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

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
