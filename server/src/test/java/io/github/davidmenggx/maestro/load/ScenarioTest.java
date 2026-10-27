package io.github.davidmenggx.maestro.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
              "players": [{"x": 0, "z": 24}, {"x": 256, "z": 24}],
              "groups": [
                {"entity": "minecraft:husk", "count": 8, "x": 1, "z": 2, "spread": 6,
                 "nbt": "{DeathLootTable:\\"minecraft:empty\\"}"}
              ]
            }
            """);

    Scenario scenario = Scenario.load(path);

    assertEquals(42, scenario.seed());
    assertEquals(5, scenario.warmupSeconds());
    assertEquals(60, scenario.durationSeconds());
    assertEquals(
        List.of(new Scenario.Player(0, 24), new Scenario.Player(256, 24)), scenario.players());
    assertEquals(
        List.of(
            new Scenario.Group(
                "minecraft:husk", 8, 1, 2, 6, "{DeathLootTable:\"minecraft:empty\"}")),
        scenario.groups());
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
    assertEquals(List.of(), scenario.players());
    Scenario.Group group = scenario.groups().get(0);
    assertEquals(0, group.x());
    assertEquals(0, group.z());
    assertEquals(0, group.spread());
    assertEquals("{}", group.nbt());
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
        write(dir, "{\"groups\": [{\"entity\": \"minecraft:husk\", \"count\": 1, \"type\": 1}]}"),
        "`type` is not a group setting");
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
    assertInvalid(write(dir, "{\"players\": [null]}"), "`players`");
    assertInvalid(write(dir, "{\"groups\": [null]}"), "`groups`");
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
  void theListsCannotChange() {
    Scenario scenario = scenario(1, group(0, 0, 0, 1));

    assertThrows(
        UnsupportedOperationException.class, () -> scenario.groups().add(group(0, 0, 0, 1)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> scenario.players().add(new Scenario.Player(0, 0)));
  }

  private static Scenario scenario(long seed, Scenario.Group... groups) {
    return new Scenario(seed, 0, 0, List.of(), List.of(groups));
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
