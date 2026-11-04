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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraft.world.phys.Vec3;

/**
 * A load run, read from `<name>.json`. A missing key takes its default; a malformed file, an
 * unknown key or a bad value is refused.
 *
 * <p>The columns of each square of {@code terrain} are rebuilt as {@link Terrain}, with a keep on
 * each square of {@code keeps}, which must lie inside one of them with {@link Terrain#KEEP_MARGIN}
 * blocks to spare. Each of {@code players} stands on the surface at its block and loads the chunks
 * around it as a player would. A {@code hunted} player is in survival mode, so hostile mobs hunt
 * it, and the others are in creative mode. One that walks goes at walking speed to points drawn
 * from {@code seed} within {@code walk} blocks of its block on either axis, one after another. Each
 * of {@code groups} places {@code count} entities of type {@code entity}, each built from the SNBT
 * compound {@code nbt}, on the surface at blocks drawn from {@code seed} within {@code spread}
 * blocks of its {@code x} and {@code z} on either axis, never on a keep, so some of those blocks
 * must lie off the keeps. With a {@code reset}, a mob within {@code within} blocks of the player it
 * hunts is moved, its path dropped, to a block of its group drawn as a spawn is, at least {@code
 * away} blocks from every player. Coordinates are world blocks. The run waits {@code warmupSeconds}
 * once every entity is placed, then measures for {@code durationSeconds}, or until stopped if 0.
 */
public record Scenario(
    long seed,
    int warmupSeconds,
    int durationSeconds,
    List<Area> terrain,
    List<Area> keeps,
    List<Player> players,
    List<Group> groups,
    Reset reset) {

  public static final int MAX_PLAYERS = 64;

  public static final int MAX_COUNT = 100_000;

  public static final int MAX_SPREAD = 4096;

  public static final int MAX_TERRAIN_RADIUS = 256;

  /**
   * Columns the {@code terrain} squares may cover together: two of the largest. A build runs in one
   * tick at tens of microseconds a column, so this keeps it well within the default watchdog.
   */
  public static final long MAX_TERRAIN_COLUMNS = 2 * new Area(0, 0, MAX_TERRAIN_RADIUS).columns();

  public static final int MAX_KEEP_RADIUS = 16;

  public static final int MAX_RESET_WITHIN = 16;

  /** Blocks drawn for a mob being reset before it is left until the next try. */
  public static final int RESET_TRIES = 16;

  /** Scenario names, so a name cannot leave the scenario directory. */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private static final Gson GSON = new Gson();

  private static final Set<String> SETTINGS = componentNames(Scenario.class);

  private static final Set<String> AREA_SETTINGS = componentNames(Area.class);

  private static final Set<String> PLAYER_SETTINGS = componentNames(Player.class);

  private static final Set<String> GROUP_SETTINGS = componentNames(Group.class);

  private static final Set<String> RESET_SETTINGS = componentNames(Reset.class);

  /** The square of blocks within {@code radius} of {@code x, z} on either axis. */
  public record Area(int x, int z, int radius) {

    public Area {
      require(radius >= 0, "a square's `radius` must not be negative");
    }

    /** How many columns this square covers. */
    public long columns() {
      long side = 2L * radius + 1;
      return side * side;
    }

    /** Whether column {@code x, z} lies within {@code margin} blocks of this square. */
    public boolean contains(int x, int z, int margin) {
      return Math.abs(x - this.x) <= radius + margin && Math.abs(z - this.z) <= radius + margin;
    }

    /** Whether {@code inner}, grown by {@code margin} blocks, lies inside this square. */
    public boolean contains(Area inner, int margin) {
      int reach = inner.radius + margin;
      return Math.abs(inner.x - x) + reach <= radius && Math.abs(inner.z - z) + reach <= radius;
    }
  }

  public record Player(int x, int z, int walk, boolean hunted) {

    public Player {
      require(walk >= 0 && walk <= MAX_SPREAD, "a player's `walk` must be 0 to " + MAX_SPREAD);
    }
  }

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

  /** Mobs that catch the player they hunt go elsewhere, so they never crowd it. */
  public record Reset(int within, int away) {

    public Reset {
      require(
          within >= 1 && within <= MAX_RESET_WITHIN,
          "a reset's `within` must be 1 to " + MAX_RESET_WITHIN);
      require(away >= 0 && away <= MAX_SPREAD, "a reset's `away` must be 0 to " + MAX_SPREAD);
    }

    /** Whether a mob {@code distanceSqr} squared blocks from the player it hunts caught it. */
    public boolean caught(double distanceSqr) {
      return distanceSqr <= (double) within * within;
    }

    /**
     * Whether the centre of block {@code x, z} is at least {@code away} blocks from each of {@code
     * players} across the ground.
     */
    public boolean clearOf(int x, int z, List<Vec3> players) {
      for (Vec3 player : players) {
        double dx = x + 0.5 - player.x();
        double dz = z + 0.5 - player.z();
        if (dx * dx + dz * dz < (double) away * away) {
          return false;
        }
      }
      return true;
    }
  }

  public Scenario {
    require(warmupSeconds >= 0, "`warmupSeconds` must not be negative");
    require(durationSeconds >= 0, "`durationSeconds` must not be negative");
    terrain = terrain == null ? List.of() : terrain;
    keeps = keeps == null ? List.of() : keeps;
    players = players == null ? List.of() : players;
    groups = groups == null ? List.of() : groups;
    require(terrain.stream().allMatch(Objects::nonNull), "`terrain` must not hold null");
    require(keeps.stream().allMatch(Objects::nonNull), "`keeps` must not hold null");
    require(players.size() <= MAX_PLAYERS, "at most " + MAX_PLAYERS + " `players`");
    require(players.stream().allMatch(Objects::nonNull), "`players` must not hold null");
    require(groups.stream().allMatch(Objects::nonNull), "`groups` must not hold null");
    long columns = 0;
    for (Area area : terrain) {
      require(
          area.radius() <= MAX_TERRAIN_RADIUS,
          "a `terrain` square's `radius` must be 0 to " + MAX_TERRAIN_RADIUS);
      columns += area.columns();
    }
    require(
        columns <= MAX_TERRAIN_COLUMNS,
        "the `terrain` squares must cover at most " + MAX_TERRAIN_COLUMNS + " columns together");
    for (Area keep : keeps) {
      require(
          keep.radius() <= MAX_KEEP_RADIUS, "a keep's `radius` must be 0 to " + MAX_KEEP_RADIUS);
      require(
          terrain.stream().anyMatch(area -> area.contains(keep, Terrain.KEEP_MARGIN)),
          "a keep must lie inside a `terrain` square with "
              + Terrain.KEEP_MARGIN
              + " blocks to spare");
    }
    for (Group group : groups) {
      require(
          reachesPastKeeps(new Area(group.x(), group.z(), group.spread()), keeps),
          "a group's `spread` must reach past the keeps");
    }
    terrain = List.copyOf(terrain);
    keeps = List.copyOf(keeps);
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
      requireKnownInEach(settings, "terrain", AREA_SETTINGS, "`%s` is not a square setting");
      requireKnownInEach(settings, "keeps", AREA_SETTINGS, "`%s` is not a square setting");
      requireKnownInEach(settings, "players", PLAYER_SETTINGS, "`%s` is not a player setting");
      requireKnownInEach(settings, "groups", GROUP_SETTINGS, "`%s` is not a group setting");
      if (settings.get("reset") != null && settings.get("reset").isJsonObject()) {
        requireKnown(
            settings.getAsJsonObject("reset"), RESET_SETTINGS, "`%s` is not a reset setting");
      }
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

  /**
   * Where each entity stands, group by group, off the keeps. The same scenario gives the same
   * places.
   */
  public List<Spawn> spawns() {
    Random random = new Random(seed);
    List<Spawn> spawns = new ArrayList<>(entities());
    for (int index = 0; index < groups.size(); index++) {
      Group group = groups.get(index);
      int side = 2 * group.spread() + 1;
      for (int i = 0; i < group.count(); i++) {
        int x;
        int z;
        do {
          x = group.x() + random.nextInt(side) - group.spread();
          z = group.z() + random.nextInt(side) - group.spread();
        } while (onKeep(x, z));
        spawns.add(new Spawn(index, x, z));
      }
    }
    return spawns;
  }

  /**
   * Where a mob of group {@code group} goes when it is reset: a block drawn from {@code random}
   * within the group's spread, off the keeps, for which {@code fits} holds, or null if none of
   * {@link #RESET_TRIES} draws is.
   */
  public Spawn respawn(int group, SplittableRandom random, BiPredicate<Integer, Integer> fits) {
    Group from = groups.get(group);
    int side = 2 * from.spread() + 1;
    for (int i = 0; i < RESET_TRIES; i++) {
      int x = from.x() + random.nextInt(side) - from.spread();
      int z = from.z() + random.nextInt(side) - from.spread();
      if (!onKeep(x, z) && fits.test(x, z)) {
        return new Spawn(group, x, z);
      }
    }
    return null;
  }

  /** Whether column {@code x, z} is on a keep. */
  private boolean onKeep(int x, int z) {
    return keeps.stream().anyMatch(keep -> keep.contains(x, z, 0));
  }

  /** Whether some column of {@code square} is on no keep. */
  private static boolean reachesPastKeeps(Area square, List<Area> keeps) {
    Set<Long> covered = new HashSet<>();
    for (Area keep : keeps) {
      int lowX = Math.max(square.x() - square.radius(), keep.x() - keep.radius());
      int highX = Math.min(square.x() + square.radius(), keep.x() + keep.radius());
      int lowZ = Math.max(square.z() - square.radius(), keep.z() - keep.radius());
      int highZ = Math.min(square.z() + square.radius(), keep.z() + keep.radius());
      for (int x = lowX; x <= highX; x++) {
        for (int z = lowZ; z <= highZ; z++) {
          covered.add((long) x << 32 | (z & 0xFFFFFFFFL));
        }
      }
    }
    return covered.size() < square.columns();
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
