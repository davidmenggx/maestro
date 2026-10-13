package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Records what came back out of the controller, and folds it into the run digest. It also keeps a
 * ledger of the requests that went in, so each outcome is matched to exactly one of them, and the
 * latency of each answer is measured here rather than taken from the controller.
 *
 * <p>A repeat request replaces its mob's older one, so between events a mob has at most one request
 * open. While the controller handles a repeat, the older one must be closed first, as superseded.
 * An outcome therefore always names its request: the older one if it is superseded, otherwise the
 * only one.
 */
public final class RecordingOutput implements WorldOutput {

  /** One request with no outcome yet. */
  private record Open(long tick, Pos target) {}

  private final Simulation sim;
  private final List<PathResult> results = new ArrayList<>();
  private final List<Double> latencies = new ArrayList<>();
  private final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);

  /** Open requests per mob, oldest first. */
  private final Map<Integer, Deque<Open>> open = new TreeMap<>();

  private final List<String> unmatched = new ArrayList<>();

  public RecordingOutput(Simulation sim) {
    this.sim = sim;
  }

  /** A request went in. Called before the controller sees it. */
  public void issued(WorldEvent.PathRequested request) {
    open.computeIfAbsent(request.mobId(), mob -> new ArrayDeque<>())
        .addLast(new Open(request.tick(), request.target()));
  }

  /** The controller has handled a request, so the older one for its mob must be closed. */
  public void handled(WorldEvent.PathRequested request) {
    Deque<Open> requests = open.get(request.mobId());
    if (requests != null && requests.size() > 1) {
      unmatched.add(
          "mob " + request.mobId() + " has " + requests.size() + " requests open after a repeat");
    }
  }

  @Override
  public void pathComputed(PathResult result) {
    sim.digest().mix(sim.now()).mix(result.toString());
    results.add(result);
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
    close(mobId, reason);
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

  /**
   * Every outcome that matched no open request or the wrong one, then every request with none yet.
   * Exactly one outcome per request leaves this empty once the run has drained.
   */
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

  /** Ticks from each answered request to its answer, in answer order. */
  public List<Double> latencies() {
    return List.copyOf(latencies);
  }

  /** Requests the SLO counts as late: rejected for queue depth, or never answered in time. */
  public long sloMisses() {
    return dropped(DropReason.QUEUE_DEPTH) + dropped(DropReason.TIMEOUT);
  }

  public int answered() {
    return results.size();
  }

  public int dropped(DropReason reason) {
    return drops.getOrDefault(reason, 0);
  }

  public Map<DropReason, Integer> drops() {
    return new EnumMap<>(drops);
  }
}
