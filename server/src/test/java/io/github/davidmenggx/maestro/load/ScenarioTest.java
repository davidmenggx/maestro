package io.github.davidmenggx.maestro.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioTest {

  @Test
  void readsEverySetting(@TempDir Path dir) throws IOException {
    Path path =
        write(
            dir,
            """
            {
              "seed": 42,
              "warmupSeconds": 5,
              "durationSeconds": 60,
              "terrain": [{"x": 0, "z": 0, "radius": 96}],
              "keeps": [{"x": 8, "z": -8, "radius": 3}],
              "players": [{"x": 0, "z": 24}, {"x": 256, "z": 24, "walk": 12, "hunted": true}],
              "groups": [
                {"entity": "minecraft:husk", "count": 8, "x": 1, "z": 2, "spread": 6,
                 "nbt": "{DeathLootTable:\\"minecraft:empty\\"}"}
              ],
              "reset": {"within": 2, "away": 24}
            }
            """);

    Scenario scenario = Scenario.load(path);

    assertEquals(42, scenario.seed());
    assertEquals(5, scenario.warmupSeconds());
    assertEquals(60, scenario.durationSeconds());
    assertEquals(List.of(new Scenario.Area(0, 0, 96)), scenario.terrain());
    assertEquals(List.of(new Scenario.Area(8, -8, 3)), scenario.keeps());
    assertEquals(
        List.of(new Scenario.Player(0, 24, 0, false), new Scenario.Player(256, 24, 12, true)),
        scenario.players());
    assertEquals(
        List.of(
            new Scenario.Group(
                "minecraft:husk", 8, 1, 2, 6, "{DeathLootTable:\"minecraft:empty\"}")),
        scenario.groups());
    assertEquals(new Scenario.Reset(2, 24), scenario.reset());
  }

  @Test
  void aMissingKeyTakesItsDefault(@TempDir Path dir) throws IOException {
    Scenario scenario =
        Scenario.load(
            write(dir, "{\"groups\": [{\"entity\": \"minecraft:villager\", \"count\": 1}]}"));

    assertEquals(0, scenario.seed());
    assertEquals(0, scenario.warmupSeconds());
    // Runs until stopped.
    assertEquals(0, scenario.durationSeconds());
    assertEquals(List.of(), scenario.terrain());
    assertEquals(List.of(), scenario.keeps());
    assertEquals(List.of(), scenario.players());
    // No mob is reset.
    assertNull(scenario.reset());
    Scenario.Group group = scenario.groups().get(0);
    assertEquals(0, group.x());
    assertEquals(0, group.z());
    assertEquals(0, group.spread());
    assertEquals("{}", group.nbt());
  }

  @Test
  void aPlayerStandsInCreativeModeUnlessToldOtherwise(@TempDir Path dir) throws IOException {
    Scenario scenario = Scenario.load(write(dir, "{\"players\": [{\"x\": 3}]}"));

    assertEquals(List.of(new Scenario.Player(3, 0, 0, false)), scenario.players());
  }

  @Test
  void anEmptyObjectPlacesNothing(@TempDir Path dir) throws IOException {
    Scenario scenario = Scenario.load(write(dir, "{}"));

    assertEquals(List.of(), scenario.groups());
    assertEquals(0, scenario.entities());
    assertEquals(List.of(), scenario.spawns());
  }

  @Test
  void refusesAnUnknownKeyAtEachLevel(@TempDir Path dir) throws IOException {
    assertInvalid(write(dir, "{\"seeds\": 1}"), "`seeds` is not a setting");
    assertInvalid(
        write(dir, "{\"players\": [{\"x\": 0, \"y\": 64, \"z\": 0}]}"),
        "`y` is not a player setting");
    assertInvalid(
        write(dir, "{\"terrain\": [{\"x\": 0, \"z\": 0, \"size\": 1}]}"),
        "`size` is not a square setting");
    assertInvalid(write(dir, "{\"keeps\": [{\"x\": 0, \"y\": 0}]}"), "`y` is not a square setting");
    assertInvalid(
        write(dir, "{\"groups\": [{\"entity\": \"minecraft:husk\", \"count\": 1, \"type\": 1}]}"),
        "`type` is not a group setting");
    assertInvalid(
        write(dir, "{\"reset\": {\"within\": 2, \"near\": 1}}"), "`near` is not a reset setting");
  }

  @Test
  void refusesBadValues(@TempDir Path dir) throws IOException {
    assertInvalid(write(dir, "{\"warmupSeconds\": -1}"), "`warmupSeconds`");
    assertInvalid(write(dir, "{\"durationSeconds\": -1}"), "`durationSeconds`");
    assertInvalid(write(dir, "{\"groups\": [{\"count\": 1}]}"), "`entity`");
    assertInvalid(write(dir, "{\"groups\": [{\"entity\": \" \", \"count\": 1}]}"), "`entity`");
    assertInvalid(write(dir, "{\"groups\": [{\"entity\": \"minecraft:husk\"}]}"), "`count`");
    assertInvalid(
        write(dir, "{\"groups\": [{\"entity\": \"minecraft:husk\", \"count\": 100001}]}"),
        "`count`");
    assertInvalid(
        write(
            dir, "{\"groups\": [{\"entity\": \"minecraft:husk\", \"count\": 1, \"spread\": -1}]}"),
        "`spread`");
    assertInvalid(write(dir, "{\"players\": [{\"walk\": -1}]}"), "`walk`");
    assertInvalid(write(dir, "{\"players\": [{\"walk\": 4097}]}"), "`walk`");
    assertInvalid(write(dir, "{\"terrain\": [{\"radius\": -1}]}"), "`radius`");
    assertInvalid(write(dir, "{\"terrain\": [{\"radius\": 257}]}"), "`radius`");
    assertInvalid(write(dir, "{\"terrain\": [null]}"), "`terrain`");
    assertInvalid(write(dir, "{\"keeps\": [null]}"), "`keeps`");
    assertInvalid(
        write(dir, "{\"terrain\": [{\"radius\": 64}], \"keeps\": [{\"radius\": 17}]}"),
        "a keep's `radius`");
    assertInvalid(write(dir, "{\"reset\": {\"away\": 8}}"), "`within`");
    assertInvalid(write(dir, "{\"reset\": {\"within\": 17}}"), "`within`");
    assertInvalid(write(dir, "{\"reset\": {\"within\": 2, \"away\": -1}}"), "`away`");
    assertInvalid(write(dir, "{\"reset\": {\"within\": 2, \"away\": 4097}}"), "`away`");
    assertInvalid(write(dir, "{\"players\": [null]}"), "`players`");
    assertInvalid(write(dir, "{\"groups\": [null]}"), "`groups`");
  }

  @Test
  void aKeepLiesInsideATerrainSquareWithRoomAroundIt(@TempDir Path dir) throws IOException {
    String square = "\"terrain\": [{\"x\": 0, \"z\": 0, \"radius\": 10}, {\"x\": 100}]";

    // 5 + 3 + 2 blocks from the centre: the margin reaches the edge.
    Scenario.load(write(dir, "{" + square + ", \"keeps\": [{\"x\": 5, \"radius\": 3}]}"));
    Scenario.load(write(dir, "{" + square + ", \"keeps\": [{\"z\": -5, \"radius\": 3}]}"));
    assertInvalid(
        write(dir, "{" + square + ", \"keeps\": [{\"x\": 6, \"radius\": 3}]}"),
        "inside a `terrain` square");
    assertInvalid(
        write(dir, "{" + square + ", \"keeps\": [{\"z\": -6, \"radius\": 3}]}"),
        "inside a `terrain` square");
    // A square of radius 0 holds no keep with its margin.
    assertInvalid(
        write(dir, "{" + square + ", \"keeps\": [{\"x\": 100}]}"), "inside a `terrain` square");
    assertInvalid(write(dir, "{\"keeps\": [{\"radius\": 1}]}"), "inside a `terrain` square");
  }

  @Test
  void theTerrainCoversTwoOfTheLargestSquaresAtMost(@TempDir Path dir) throws IOException {
    String largest = "{\"radius\": " + Scenario.MAX_TERRAIN_RADIUS + "}";

    assertEquals(513L * 513, new Scenario.Area(0, 0, Scenario.MAX_TERRAIN_RADIUS).columns());
    assertEquals(2 * 513L * 513, Scenario.MAX_TERRAIN_COLUMNS);
    Scenario.load(write(dir, "{\"terrain\": [" + largest + ", " + largest + "]}"));
    // One more column, even where the squares overlap.
    assertInvalid(
        write(dir, "{\"terrain\": [" + largest + ", " + largest + ", {\"radius\": 0}]}"),
        "at most " + Scenario.MAX_TERRAIN_COLUMNS + " columns");
  }

  @Test
  void refusesTooManyPlayers(@TempDir Path dir) throws IOException {
    StringBuilder players = new StringBuilder();
    for (int i = 0; i <= Scenario.MAX_PLAYERS; i++) {
      players.append(i == 0 ? "" : ",").append("{\"x\": ").append(i).append("}");
    }

    assertInvalid(write(dir, "{\"players\": [" + players + "]}"), "`players`");
  }

  @Test
  void refusesAFileThatIsNotAnObject(@TempDir Path dir) throws IOException {
    assertInvalid(write(dir, "[]"), "not a JSON object");
    assertInvalid(write(dir, ""), "not a JSON object");
    assertThrows(IllegalStateException.class, () -> Scenario.load(write(dir, "{\"seed\": ")));
  }

  @Test
  void aMissingFileCannotBeRead(@TempDir Path dir) {
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> Scenario.load(dir.resolve("none.json")));

    assertTrue(thrown.getMessage().startsWith("Could not read scenario"), thrown.getMessage());
  }

  @Test
  void aNameNamesAFileInTheDirectory(@TempDir Path dir) {
    assertEquals(dir.resolve("e2e-split_2.json"), Scenario.file(dir, "e2e-split_2"));
  }

  @Test
  void aNameCannotLeaveTheDirectory(@TempDir Path dir) {
    for (String name : List.of("../e2e", "a/b", "..", "", "a.b", "x".repeat(65))) {
      assertThrows(IllegalArgumentException.class, () -> Scenario.file(dir, name), name);
    }
  }

  @Test
  void theSameScenarioGivesTheSameSpawns() {
    Scenario scenario = scenario(7, group(0, 0, 10, 50), group(300, -40, 3, 20));

    assertEquals(
        scenario.spawns(), scenario(7, group(0, 0, 10, 50), group(300, -40, 3, 20)).spawns());
  }

  @Test
  void anotherSeedGivesOtherSpawns() {
    assertNotEquals(
        scenario(1, group(0, 0, 10, 50)).spawns(), scenario(2, group(0, 0, 10, 50)).spawns());
  }

  @Test
  void spawnsComeGroupByGroupWithinEachSpread() {
    Scenario scenario = scenario(3, group(0, 0, 4, 200), group(1000, -500, 1, 100));

    List<Scenario.Spawn> spawns = scenario.spawns();

    assertEquals(300, spawns.size());
    assertEquals(300, scenario.entities());
    for (int i = 0; i < spawns.size(); i++) {
      Scenario.Spawn spawn = spawns.get(i);
      Scenario.Group group = scenario.groups().get(spawn.group());
      assertEquals(i < 200 ? 0 : 1, spawn.group());
      assertTrue(Math.abs(spawn.x() - group.x()) <= group.spread(), spawn.toString());
      assertTrue(Math.abs(spawn.z() - group.z()) <= group.spread(), spawn.toString());
    }
  }

  @Test
  void spawnsReachEveryEdgeOfTheSpread() {
    List<Scenario.Spawn> spawns = scenario(5, group(10, 20, 2, 500)).spawns();

    assertEquals(8, spawns.stream().mapToInt(Scenario.Spawn::x).min().orElseThrow());
    assertEquals(12, spawns.stream().mapToInt(Scenario.Spawn::x).max().orElseThrow());
    assertEquals(18, spawns.stream().mapToInt(Scenario.Spawn::z).min().orElseThrow());
    assertEquals(22, spawns.stream().mapToInt(Scenario.Spawn::z).max().orElseThrow());
  }

  @Test
  void noSpreadPlacesEveryEntityOnItsBlock() {
    List<Scenario.Spawn> spawns = scenario(9, group(-10, 12, 0, 3)).spawns();

    assertEquals(List.of(new Scenario.Spawn(0, -10, 12)), spawns.stream().distinct().toList());
  }

  @Test
  void spawnsStayOffTheKeepsAndCoverTheRest() {
    Scenario.Area keep = new Scenario.Area(3, -2, 2);
    Scenario scenario = siege(keep, group(0, 0, 6, 5000));

    Set<List<Integer>> blocks = new HashSet<>();
    for (Scenario.Spawn spawn : scenario.spawns()) {
      assertFalse(keep.contains(spawn.x(), spawn.z(), 0), spawn.toString());
      blocks.add(List.of(spawn.x(), spawn.z()));
    }
    assertEquals(13 * 13 - 5 * 5, blocks.size());
    assertEquals(scenario.spawns(), siege(keep, group(0, 0, 6, 5000)).spawns());
  }

  @Test
  void aGroupMustReachPastTheKeeps() {
    Scenario.Area keep = new Scenario.Area(0, 0, 3);
    siege(keep, group(0, 0, 4, 1));
    siege(keep, group(4, 0, 0, 1));

    assertThrows(IllegalArgumentException.class, () -> siege(keep, group(0, 0, 3, 1)));
    assertThrows(IllegalArgumentException.class, () -> siege(keep, group(-3, 3, 0, 1)));
  }

  @Test
  void keepsThatTogetherCoverAGroupLeaveItNowhereToStand() {
    List<Scenario.Area> keeps = List.of(new Scenario.Area(-2, 0, 2), new Scenario.Area(3, 0, 2));

    new Scenario(1, 0, 0, List.of(TERRAIN), keeps, List.of(), List.of(group(0, 0, 3, 1)), null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Scenario(
                1, 0, 0, List.of(TERRAIN), keeps, List.of(), List.of(group(0, 0, 2, 1)), null));
  }

  @Test
  void theListsCannotChange() {
    Scenario scenario = scenario(1, group(0, 0, 0, 1));

    assertThrows(
        UnsupportedOperationException.class, () -> scenario.groups().add(group(0, 0, 0, 1)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> scenario.players().add(new Scenario.Player(0, 0, 0, false)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> scenario.terrain().add(new Scenario.Area(0, 0, 0)));
  }

  @Test
  void aMobIsCaughtWithinItsDistanceOfThePlayer() {
    Scenario.Reset reset = new Scenario.Reset(2, 24);

    assertTrue(reset.caught(0));
    assertTrue(reset.caught(4));
    assertFalse(reset.caught(4.01));
  }

  @Test
  void aBlockIsClearOnlyAtItsDistanceFromEveryPlayer() {
    Scenario.Reset reset = new Scenario.Reset(2, 10);
    List<Vec3> players = List.of(new Vec3(0.5, 64, 0.5), new Vec3(40.5, 64, 0.5));

    // Centre to centre across the ground, whatever the height.
    assertTrue(reset.clearOf(10, 0, players));
    assertFalse(reset.clearOf(9, 0, players));
    assertFalse(reset.clearOf(6, 7, players));
    assertTrue(reset.clearOf(20, 0, players));
    assertFalse(reset.clearOf(31, 0, players));
    assertTrue(reset.clearOf(20, 0, List.of()));
    assertTrue(new Scenario.Reset(2, 0).clearOf(0, 0, players));
  }

  @Test
  void aResetMobGoesWithinItsGroupsSpreadOffTheKeepsWhereItFits() {
    Scenario.Area keep = new Scenario.Area(3, -2, 2);
    Scenario scenario = siege(keep, group(0, 0, 6, 1));
    SplittableRandom random = new SplittableRandom(4);

    Set<List<Integer>> blocks = new HashSet<>();
    for (int i = 0; i < 5000; i++) {
      Scenario.Spawn spot = scenario.respawn(0, random, (x, z) -> x >= -5);
      assertEquals(0, spot.group());
      assertTrue(Math.abs(spot.x()) <= 6 && Math.abs(spot.z()) <= 6, spot.toString());
      assertFalse(keep.contains(spot.x(), spot.z(), 0), spot.toString());
      blocks.add(List.of(spot.x(), spot.z()));
    }
    // The 12 columns from x = -5 by 13 rows, less the keep.
    assertEquals(12 * 13 - 5 * 5, blocks.size());
  }

  @Test
  void aResetMobWithNowhereToGoStaysAfterItsTries() {
    Scenario scenario = scenario(1, group(0, 0, 6, 1));
    List<Integer> tried = new ArrayList<>();

    assertNull(
        scenario.respawn(
            0,
            new SplittableRandom(1),
            (x, z) -> {
              tried.add(x);
              return false;
            }));
    assertEquals(Scenario.RESET_TRIES, tried.size());
  }

  @Test
  void theSameDrawsResetMobsToTheSameBlocks() {
    Scenario scenario = scenario(1, group(0, 0, 50, 1), group(200, 0, 50, 1));

    assertEquals(spots(scenario, 9), spots(scenario, 9));
    assertNotEquals(spots(scenario, 9), spots(scenario, 10));
  }

  private static List<Scenario.Spawn> spots(Scenario scenario, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    List<Scenario.Spawn> spots = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      spots.add(scenario.respawn(i % 2, random, (x, z) -> true));
    }
    return spots;
  }

  private static final Scenario.Area TERRAIN = new Scenario.Area(0, 0, 64);

  /** One keep in a terrain square, with {@code group} around it. */
  private static Scenario siege(Scenario.Area keep, Scenario.Group group) {
    return new Scenario(1, 0, 0, List.of(TERRAIN), List.of(keep), List.of(), List.of(group), null);
  }

  private static Scenario scenario(long seed, Scenario.Group... groups) {
    return new Scenario(seed, 0, 0, List.of(), List.of(), List.of(), List.of(groups), null);
  }

  private static Scenario.Group group(int x, int z, int spread, int count) {
    return new Scenario.Group("minecraft:husk", count, x, z, spread, null);
  }

  private static void assertInvalid(Path path, String expected) {
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> Scenario.load(path));
    assertTrue(thrown.getMessage().contains(expected), thrown.getMessage());
  }

  private static Path write(Path dir, String json) throws IOException {
    Path path = dir.resolve("scenario.json");
    Files.writeString(path, json);
    return path;
  }
}
