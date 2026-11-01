package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.core.WorldStreams;
import io.github.davidmenggx.maestro.controller.env.RequestTrace;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.SearchReach;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Records what came back out of the controller and folds it into the run digest. A ledger of the
 * requests that went in matches each outcome to exactly one, and answer latency is measured here,
 * not taken from the controller.
 *
 * <p>A repeat request replaces its mob's older one, which must be closed first as superseded, so a
 * mob has at most one open request between events and an outcome always names its request.
 *
 * <p>A request open when its controller process restarts is closed as lost with the process.
 *
 * <p>A request is servable when it starts in a region far enough inside the region's area that the
 * search cannot read past it, in a chunk loaded long enough for chunk reads to have reached it.
 * Dropping a servable request for want of a node counts against the SLO, unless its chunk left
 * meanwhile. So does losing a request with its node, and shedding one.
 */
public final class RecordingOutput implements WorldOutput {

  /** One request with no outcome yet. {@code loadedSince} is when its start chunk loaded. */
  private record Open(long tick, Pos start, Pos target, boolean servable, long loadedSince) {}

  /** An outcome a mob was told, named as a trace names it. */
  public record Told(long tick, int mobId, String outcome) {}

  /**
   * Ticks after which a chunk loaded, or readable again, is resident on its holders: greet, read,
   * and margin.
   */
  private static final int SETTLE_TICKS = 4;

  private final Simulation sim;
  private final SimWorld world;
  private final Supplier<Regions> regions;
  private final List<PathResult> results = new ArrayList<>();
  private final List<Told> told = new ArrayList<>();
  private final List<Double> latencies = new ArrayList<>();
  private final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);

  /** Open requests per mob, oldest first. */
  private final Map<Integer, Deque<Open>> open = new TreeMap<>();

  private final List<String> unmatched = new ArrayList<>();

  /** The most chunk reads a full send of every area took so far: a chunk once per area. */
  private long mostAreaChunks;

  private int issued;
  private int lostToRestart;
  private int unservedServable;
  private int answeredOverAir;

  /** {@code regions} gives the regions as they are when a request is issued. */
  public RecordingOutput(Simulation sim, SimWorld world, Supplier<Regions> regions) {
    this.sim = sim;
    this.world = world;
    this.regions = regions;
  }

  /** A request went in. Called before the controller sees it. */
  public void issued(WorldEvent.PathRequested request) {
    issued++;
    boolean servable = servable(request);
    long since =
        servable
            ? world.loadedSince(Chunks.of(request.start().x()), Chunks.of(request.start().z()))
            : -1;
    open.computeIfAbsent(request.mobId(), mob -> new ArrayDeque<>())
        .addLast(new Open(request.tick(), request.start(), request.target(), servable, since));
  }

  private boolean servable(WorldEvent.PathRequested request) {
    Pos start = request.start();
    Region region = regions.get().regionAt(start.x(), start.z());
    MobClass profile = world.mobClass(request.classId());
    if (region == null
        || profile == null
        || !world.contains(start.x(), start.z())
        || !SearchReach.fits(
            region.areaMinX(),
            region.areaMinZ(),
            region.areaSizeX(),
            region.areaSizeZ(),
            start,
            request.search().maxPathLength(),
            profile.shape().width())) {
      return false;
    }
    int chunkX = Chunks.of(start.x());
    int chunkZ = Chunks.of(start.z());
    // A chunk about to leave, or one that stayed after it, may never have been read.
    return world.chunkLoaded(chunkX, chunkZ)
        && world.readable(chunkX, chunkZ)
        && request.tick() - world.readableSince(chunkX, chunkZ) >= readTicks() + SETTLE_TICKS;
  }

  /** Ticks the read budget takes to send every area in full, at the most chunks seen loaded. */
  long readTicks() {
    long reads = Math.max(world.mostLoaded(), mostAreaChunks);
    return (reads + WorldStreams.MAX_CHUNK_READS_PER_TICK - 1)
        / WorldStreams.MAX_CHUNK_READS_PER_TICK;
  }

  /** A tick ended: counts the chunk reads a full send of every area would take now. */
  public void tickEnded() {
    long areaChunks = 0;
    for (int i = 0; i < world.loadedCount(); i++) {
      long chunk = world.loadedChunk(i);
      areaChunks +=
          regions
              .get()
              .reaching((int) (chunk >> 32) * Chunks.SIZE, (int) chunk * Chunks.SIZE)
              .size();
    }
    mostAreaChunks = Math.max(mostAreaChunks, areaChunks);
  }

  /** The controller has handled a request, so the older one for its mob must be closed. */
  public void handled(WorldEvent.PathRequested request) {
    Deque<Open> requests = open.get(request.mobId());
    if (requests != null && requests.size() > 1) {
      unmatched.add(
          "mob " + request.mobId() + " has " + requests.size() + " requests open after a repeat");
    }
  }

  /** The controller process restarted, and every open request went with it. */
  public void restarted() {
    for (Deque<Open> requests : open.values()) {
      lostToRestart += requests.size();
    }
    open.clear();
  }

  @Override
  public void pathComputed(PathResult result) {
    sim.digest().mix(sim.now()).mix(result.toString());
    results.add(result);
    told.add(new Told(sim.env().clock().tick(), result.mobId(), RequestTrace.ANSWERED));
    if (result.cost() == ModeledNode.SEARCHED_OVER_AIR) {
      answeredOverAir++;
    }
    // The modeled node's paths are straight lines, so this checks the controller's world positions.
    if (result.reached()
        && (result.nodes().isEmpty()
            || !result.nodes().get(result.nodes().size() - 1).pos().equals(result.target()))) {
      unmatched.add(
          "mob " + result.mobId() + " reached " + result.target() + " but its path ends elsewhere");
    }
    Open request = close(result.mobId(), null);
    if (request == null) {
      return;
    }
    if (result.issuedTick() != request.tick() || !result.target().equals(request.target())) {
      unmatched.add(
          "mob "
              + result.mobId()
              + " was answered for a request from tick "
              + result.issuedTick()
              + " to "
              + result.target()
              + ", but its open one is from tick "
              + request.tick()
              + " to "
              + request.target());
    }
    latencies.add((double) (sim.env().clock().tick() - request.tick()));
  }

  @Override
  public void pathDropped(int mobId, DropReason reason) {
    sim.digest().mix(sim.now()).mix("drop").mix(mobId).mix(reason.name());
    drops.merge(reason, 1, Integer::sum);
    told.add(new Told(sim.env().clock().tick(), mobId, reason.name()));
    Open request = close(mobId, reason);
    if ((reason == DropReason.NO_NODE || reason == DropReason.UNAVAILABLE)
        && request != null
        && request.servable()
        && world.loadedSince(Chunks.of(request.start().x()), Chunks.of(request.start().z()))
            == request.loadedSince()) {
      unservedServable++;
    }
  }

  /**
   * Closes the request an outcome belongs to and returns it, or null if there is none. {@code
   * reason} is null for an answer.
   */
  private Open close(int mobId, DropReason reason) {
    String outcome = reason == null ? "an answer" : reason.name();
    Deque<Open> requests = open.get(mobId);
    if (requests == null) {
      unmatched.add("mob " + mobId + " got " + outcome + " with no request open");
      return null;
    }
    boolean superseded = reason == DropReason.SUPERSEDED;
    if (superseded != requests.size() > 1) {
      unmatched.add(
          "mob "
              + mobId
              + " got "
              + outcome
              + " with "
              + requests.size()
              + " requests open; only a newer request supersedes an older one");
    }
    Open request = requests.pollFirst();
    if (requests.isEmpty()) {
      open.remove(mobId);
    }
    return request;
  }

  /** Outcomes that matched no open request or the wrong one, then requests with no outcome. */
  public List<String> ledgerViolations() {
    List<String> violations = new ArrayList<>(unmatched);
    for (Map.Entry<Integer, Deque<Open>> entry : open.entrySet()) {
      violations.add("mob " + entry.getKey() + " has " + entry.getValue().size() + " open");
    }
    return violations;
  }

  public List<PathResult> results() {
    return List.copyOf(results);
  }

  /** Every outcome, answers and drops, in the order told. */
  public List<Told> told() {
    return List.copyOf(told);
  }

  /** Ticks from each answered request to its answer, in answer order. */
  public List<Double> latencies() {
    return List.copyOf(latencies);
  }

  /**
   * Requests the SLO counts as late: shed, never answered in time, lost with their node, or
   * servable and dropped for want of a node.
   */
  public long sloMisses() {
    return dropped(DropReason.SHED) + admittedMisses();
  }

  /** As {@link #sloMisses}, of the requests not shed. */
  public long admittedMisses() {
    return dropped(DropReason.TIMEOUT) + dropped(DropReason.NODE_LOST) + unservedServable;
  }

  /** Answers the controller accepted although the node searched them over air. */
  public int answeredOverAir() {
    return answeredOverAir;
  }

  public int answered() {
    return results.size();
  }

  public int issuedCount() {
    return issued;
  }

  /** Requests open when their controller process restarted. */
  public int lostToRestart() {
    return lostToRestart;
  }

  public int dropped(DropReason reason) {
    return drops.getOrDefault(reason, 0);
  }

  public Map<DropReason, Integer> drops() {
    return new EnumMap<>(drops);
  }
}
