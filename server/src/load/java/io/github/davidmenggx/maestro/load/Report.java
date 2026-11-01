package io.github.davidmenggx.maestro.load;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.davidmenggx.maestro.bridge.OffloadStats;
import io.github.davidmenggx.maestro.config.MaestroConfig;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What one run measured, as JSON for comparing runs. A tick's time is its work, from the start to
 * the end of the server's tick events. {@code ticksLate} counts answers by ticks from request to
 * answer, the last entry holding any later. Percentiles are by nearest rank, and null with nothing
 * to rank.
 */
final class Report {

  /** A tick whose work takes longer than this puts the server behind. */
  static final long TICK_NANOS = 50_000_000;

  private static final Gson GSON = new Gson();

  /** Keeps empty values as null, so every field is in the file. */
  private static final Gson FILE = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

  private final String name;
  private final Scenario scenario;
  private final String started;
  private final String version;
  private final MaestroConfig config;
  private final long run;
  private final int players;
  private final int spawned;
  private final int alive;
  private final long[] tickNanos;
  private final long wallNanos;
  private final OffloadStats stats;

  /**
   * A run of scenario {@code name} that began measuring at {@code started} (ISO-8601) and measured
   * the ticks taking {@code tickNanos} over {@code wallNanos}, with {@code stats} the requests
   * offloaded meanwhile. {@code run} is the active fleet's run, which names its telemetry, or 0 if
   * none was active.
   */
  Report(
      String name,
      Scenario scenario,
      String started,
      String version,
      MaestroConfig config,
      long run,
      int players,
      int spawned,
      int alive,
      long[] tickNanos,
      long wallNanos,
      OffloadStats stats) {
    this.name = name;
    this.scenario = scenario;
    this.started = started;
    this.version = version;
    this.config = config;
    this.run = run;
    this.players = players;
    this.spawned = spawned;
    this.alive = alive;
    this.tickNanos = tickNanos.clone();
    this.wallNanos = wallNanos;
    this.stats = stats;
  }

  int measuredTicks() {
    return tickNanos.length;
  }

  /** Writes {@link #toJson} to {@code writer}. */
  void write(Writer writer) {
    FILE.toJson(toJson(), writer);
  }

  JsonObject toJson() {
    JsonObject json = new JsonObject();
    json.addProperty("scenario", name);
    json.addProperty("started", started);
    json.add("maestro", maestro());
    json.add("spec", GSON.toJsonTree(scenario));
    json.addProperty("players", players);
    JsonObject entities = new JsonObject();
    entities.addProperty("spawned", spawned);
    entities.addProperty("alive", alive);
    json.add("entities", entities);
    json.add("ticks", ticks());
    JsonObject requests = new JsonObject();
    stats.counts().forEach(requests::addProperty);
    json.add("requests", requests);
    json.add("ticksLate", ticksLate());
    return json;
  }

  private JsonObject maestro() {
    JsonObject maestro = new JsonObject();
    maestro.addProperty("version", version);
    maestro.addProperty("offloading", run != 0);
    maestro.addProperty("run", run == 0 ? null : Long.toHexString(run));
    maestro.addProperty("offload", config.offload().name().toLowerCase(Locale.ROOT));
    maestro.addProperty("subMode", config.subMode().name().toLowerCase(Locale.ROOT));
    maestro.addProperty("nodes", config.nodes().size());
    maestro.addProperty("nodeCapacity", config.nodeCapacity());
    maestro.addProperty("areaCells", config.areaCells());
    maestro.addProperty("spares", config.spares());
    return maestro;
  }

  private JsonObject ticks() {
    long[] sorted = tickNanos.clone();
    Arrays.sort(sorted);
    JsonObject ticks = new JsonObject();
    ticks.addProperty("measured", sorted.length);
    ticks.addProperty("wallSeconds", round(wallNanos / 1e9));
    ticks.addProperty(
        "meanMillis",
        sorted.length == 0 ? null : millis(Arrays.stream(sorted).sum() / sorted.length));
    ticks.addProperty("p50Millis", millis(percentile(sorted, 50)));
    ticks.addProperty("p99Millis", millis(percentile(sorted, 99)));
    ticks.addProperty("maxMillis", sorted.length == 0 ? null : millis(sorted[sorted.length - 1]));
    ticks.addProperty("overBudget", Arrays.stream(sorted).filter(n -> n > TICK_NANOS).count());
    return ticks;
  }

  private JsonObject ticksLate() {
    JsonObject late = new JsonObject();
    late.addProperty("answers", stats.ticksLate().stream().mapToLong(Long::longValue).sum());
    late.addProperty("p50", percentile(stats.ticksLate(), 50));
    late.addProperty("p99", percentile(stats.ticksLate(), 99));
    JsonArray histogram = new JsonArray();
    stats.ticksLate().forEach(histogram::add);
    late.add("histogram", histogram);
    return late;
  }

  /** The {@code p}th percentile of {@code sorted}, or null if it is empty. */
  static Long percentile(long[] sorted, double p) {
    if (sorted.length == 0) {
      return null;
    }
    return sorted[rank(sorted.length, p) - 1];
  }

  /**
   * The {@code p}th percentile of the values counted in {@code counts}, where entry {@code i}
   * counts the values equal to {@code i}, or null if it counts none.
   */
  static Integer percentile(List<Long> counts, double p) {
    long total = counts.stream().mapToLong(Long::longValue).sum();
    if (total == 0) {
      return null;
    }
    long rank = rank(total, p);
    long seen = 0;
    for (int value = 0; ; value++) {
      seen += counts.get(value);
      if (seen >= rank) {
        return value;
      }
    }
  }

  /** The 1-based nearest rank of the {@code p}th percentile of {@code n} values. */
  private static int rank(long n, double p) {
    return (int) Math.max(1, Math.ceil(p / 100 * n));
  }

  private static Double millis(Long nanos) {
    return nanos == null ? null : round(nanos / 1e6);
  }

  /** Rounded to three decimals. */
  private static double round(double value) {
    return Math.round(value * 1000) / 1000.0;
  }
}
