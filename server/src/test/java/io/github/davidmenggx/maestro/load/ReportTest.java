package io.github.davidmenggx.maestro.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.davidmenggx.maestro.bridge.OffloadStats;
import io.github.davidmenggx.maestro.config.MaestroConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportTest {

  private static final Scenario SCENARIO =
      new Scenario(
          3,
          1,
          10,
          List.of(new Scenario.Player(0, 24)),
          List.of(new Scenario.Group("minecraft:husk", 2, 0, 0, 4, null)));

  private static final MaestroConfig CONFIG =
      new MaestroConfig(
          MaestroConfig.SubMode.STREAM,
          MaestroConfig.Offload.SHADOW,
          List.of(new MaestroConfig.NodeAddress("127.0.0.1", 0, 0, 0)),
          40,
          3,
          1,
          "http://localhost:4317",
          0.01);

  @Test
  void nearestRankPercentilesOfSortedValues() {
    long[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    assertEquals(5, Report.percentile(sorted, 50));
    assertEquals(10, Report.percentile(sorted, 99));
    assertEquals(1, Report.percentile(sorted, 0));
    assertEquals(7, Report.percentile(new long[] {7}, 99));
    assertNull(Report.percentile(new long[0], 50));
  }

  @Test
  void percentilesOfCountsIndexedByValue() {
    // 90 answers on time, 9 a tick late, 1 two ticks late.
    List<Long> counts = List.of(90L, 9L, 1L, 0L);

    assertEquals(0, Report.percentile(counts, 50));
    assertEquals(0, Report.percentile(counts, 90));
    assertEquals(1, Report.percentile(counts, 99));
    assertEquals(2, Report.percentile(counts, 100));
    assertNull(Report.percentile(List.of(0L, 0L), 99));
    assertNull(Report.percentile(List.of(), 99));
  }

  @Test
  void describesTheReleasedModAndTheScenario() {
    JsonObject json = report(new long[] {1_000_000}, OffloadStats.NONE).toJson();

    assertEquals("e2e", json.get("scenario").getAsString());
    assertEquals("2026-10-27T12:00:00Z", json.get("started").getAsString());
    JsonObject maestro = json.getAsJsonObject("maestro");
    assertEquals("0.1.0", maestro.get("version").getAsString());
    assertTrue(maestro.get("offloading").getAsBoolean());
    assertEquals("shadow", maestro.get("offload").getAsString());
    assertEquals("stream", maestro.get("subMode").getAsString());
    assertEquals(1, maestro.get("nodes").getAsInt());
    assertEquals(40, maestro.get("nodeCapacity").getAsInt());
    assertEquals(3, maestro.get("areaCells").getAsInt());
    assertEquals(1, maestro.get("spares").getAsInt());
    JsonObject spec = json.getAsJsonObject("spec");
    assertEquals(3, spec.get("seed").getAsLong());
    assertEquals(
        "minecraft:husk",
        spec.getAsJsonArray("groups").get(0).getAsJsonObject().get("entity").getAsString());
    assertEquals(1, json.get("players").getAsInt());
    assertEquals(2, json.getAsJsonObject("entities").get("spawned").getAsInt());
    assertEquals(1, json.getAsJsonObject("entities").get("alive").getAsInt());
  }

  @Test
  void summarizesTheMeasuredTicks() {
    long[] nanos = new long[100];
    for (int i = 0; i < nanos.length; i++) {
      nanos[i] = (i + 1) * 1_000_000L;
    }
    // Out of order, as a run records them.
    nanos[0] = 60_000_000;

    JsonObject ticks = report(nanos, OffloadStats.NONE).toJson().getAsJsonObject("ticks");

    assertEquals(100, ticks.get("measured").getAsInt());
    assertEquals(5.0, ticks.get("wallSeconds").getAsDouble());
    assertEquals(51.09, ticks.get("meanMillis").getAsDouble());
    assertEquals(51.0, ticks.get("p50Millis").getAsDouble());
    assertEquals(99.0, ticks.get("p99Millis").getAsDouble());
    assertEquals(100.0, ticks.get("maxMillis").getAsDouble());
    // 51 through 100, and the 60 put first.
    assertEquals(51, ticks.get("overBudget").getAsInt());
  }

  @Test
  void aRunThatMeasuredNoTickHasNoTickTimes() {
    JsonObject ticks = report(new long[0], OffloadStats.NONE).toJson().getAsJsonObject("ticks");

    assertEquals(0, ticks.get("measured").getAsInt());
    assertTrue(ticks.get("meanMillis").isJsonNull());
    assertTrue(ticks.get("p99Millis").isJsonNull());
    assertTrue(ticks.get("maxMillis").isJsonNull());
    assertEquals(0, ticks.get("overBudget").getAsInt());
  }

  @Test
  void copiesTheRequestCountsInOrderAndTheirLateness() {
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("requested", 120L);
    counts.put("shed", 3L);
    counts.put("failover_full", 2L);
    OffloadStats stats = new OffloadStats(counts, List.of(90L, 9L, 1L));

    JsonObject json = report(new long[] {1}, stats).toJson();

    JsonObject requests = json.getAsJsonObject("requests");
    assertEquals(List.of("requested", "shed", "failover_full"), List.copyOf(requests.keySet()));
    assertEquals(120, requests.get("requested").getAsLong());
    assertEquals(3, requests.get("shed").getAsLong());
    JsonObject late = json.getAsJsonObject("ticksLate");
    assertEquals(100, late.get("answers").getAsLong());
    assertEquals(0, late.get("p50").getAsInt());
    assertEquals(1, late.get("p99").getAsInt());
    assertEquals(3, late.getAsJsonArray("histogram").size());
    assertEquals(9, late.getAsJsonArray("histogram").get(1).getAsLong());
  }

  @Test
  void withNoFleetNothingIsOffloaded() {
    Report report =
        new Report(
            "baseline",
            SCENARIO,
            "2026-10-27T12:00:00Z",
            "0.1.0",
            MaestroConfig.DEFAULT,
            false,
            1,
            2,
            2,
            new long[] {1},
            50_000_000,
            OffloadStats.NONE);

    JsonObject json = report.toJson();

    assertFalse(json.getAsJsonObject("maestro").get("offloading").getAsBoolean());
    assertEquals(0, json.getAsJsonObject("requests").size());
    assertEquals(0, json.getAsJsonObject("ticksLate").get("answers").getAsLong());
    assertTrue(json.getAsJsonObject("ticksLate").get("p99").isJsonNull());
  }

  @Test
  void keepsItsOwnCopyOfTheTickTimes() {
    long[] nanos = {1_000_000};
    Report report = report(nanos, OffloadStats.NONE);
    nanos[0] = 9_000_000;

    assertEquals(1, report.measuredTicks());
    assertEquals(1.0, report.toJson().getAsJsonObject("ticks").get("maxMillis").getAsDouble());
  }

  private static Report report(long[] tickNanos, OffloadStats stats) {
    return new Report(
        "e2e",
        SCENARIO,
        "2026-10-27T12:00:00Z",
        "0.1.0",
        CONFIG,
        true,
        1,
        2,
        1,
        tickNanos,
        5_000_000_000L,
        stats);
  }
}
