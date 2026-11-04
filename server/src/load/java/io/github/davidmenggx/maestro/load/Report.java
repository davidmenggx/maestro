package io.github.davidmenggx.maestro.load;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

/**
 * What one run measured, as JSON for comparing runs. A tick's time is its work, from the start to
 * the end of the server's tick events, and the mean of each half of the measured ticks shows
 * whether the run kept a steady load. {@code ticksLate} counts answers by ticks from request to
 * answer, the last entry holding any later. {@code nodeRates} is each node's measured rate as the
 * run ended, in requests a tick. Percentiles are by nearest rank, and null with nothing to rank. On
 * a server without the released mod, what it would describe is null, and so are the request counts
 * and node rates if the active fleet changed while the run measured.
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
  private final Maestro.Release release;
  private final long run;
  private final int players;
  private final int spawned;
  private final int alive;
  private final int resets;
  private final long[] tickNanos;
  private final long wallNanos;
  private final Offloads stats;

  /**
   * A run of scenario {@code name} that began measuring at {@code started} (ISO-8601) and measured
   * the ticks taking {@code tickNanos}, in order, over {@code wallNanos}, with {@code resets} the
   * mobs reset and {@code stats} the requests offloaded meanwhile, or null if the active fleet
   * changed. {@code release} is the released mod, or null on a server without it. {@code run} is
   * the active fleet's run as measuring began, which names its telemetry, or 0 if none was active.
   */
  Report(
      String name,
      Scenario scenario,
      String started,
      Maestro.Release release,
      long run,
      int players,
      int spawned,
      int alive,
      int resets,
      long[] tickNanos,
      long wallNanos,
      Offloads stats) {
    this.name = name;
    this.scenario = scenario;
    this.started = started;
    this.release = release;
    this.run = run;
    this.players = players;
    this.spawned = spawned;
    this.alive = alive;
    this.resets = resets;
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
    json.add("maestro", release == null ? JsonNull.INSTANCE : maestro());
    json.add("spec", GSON.toJsonTree(scenario));
    json.addProperty("players", players);
    JsonObject entities = new JsonObject();
    entities.addProperty("spawned", spawned);
    entities.addProperty("alive", alive);
    entities.addProperty("resets", resets);
    json.add("entities", entities);
    json.add("ticks", ticks());
    boolean counted = release != null && stats != null;
    json.add("requests", counted ? requests() : JsonNull.INSTANCE);
    json.add("ticksLate", counted ? ticksLate() : JsonNull.INSTANCE);
    json.add("nodeRates", counted ? nodeRates() : JsonNull.INSTANCE);
    return json;
  }

  private JsonArray nodeRates() {
    JsonArray rates = new JsonArray();
    for (Double rate : stats.nodeRates()) {
      rates.add(rate == null ? null : round(rate));
    }
    return rates;
  }

  private JsonObject maestro() {
    JsonObject maestro = new JsonObject();
    maestro.addProperty("version", release.version());
    maestro.addProperty("offloading", run != 0);
    maestro.addProperty("run", run == 0 ? null : Long.toHexString(run));
    maestro.addProperty("offload", release.offload());
    maestro.addProperty("subMode", release.subMode());
    maestro.addProperty("nodes", release.nodes());
    maestro.addProperty("nodeCapacity", release.nodeCapacity());
    maestro.addProperty("areaCells", release.areaCells());
    maestro.addProperty("spares", release.spares());
    maestro.addProperty("traceSampleRate", release.traceSampleRate());
    return maestro;
  }

  private JsonObject requests() {
    JsonObject requests = new JsonObject();
    stats.counts().forEach(requests::addProperty);
    return requests;
  }

  private JsonObject ticks() {
    long[] sorted = tickNanos.clone();
    Arrays.sort(sorted);
    JsonObject ticks = new JsonObject();
    ticks.addProperty("measured", sorted.length);
    ticks.addProperty("wallSeconds", round(wallNanos / 1e9));
    ticks.addProperty("meanMillis", mean(tickNanos, 0, tickNanos.length));
    int half = tickNanos.length / 2;
    ticks.addProperty("firstHalfMeanMillis", mean(tickNanos, 0, half));
    ticks.addProperty("secondHalfMeanMillis", mean(tickNanos, half, tickNanos.length));
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

  /** The mean of {@code nanos} from {@code from} up to {@code to}, or null if none lie there. */
  private static Double mean(long[] nanos, int from, int to) {
    return to <= from ? null : millis(Arrays.stream(nanos, from, to).sum() / (to - from));
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
