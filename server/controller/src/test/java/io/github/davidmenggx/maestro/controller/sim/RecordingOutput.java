package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Records what came back out of the controller, and folds it into the run digest. It also keeps a
 * ledger per mob of the requests that went in, so each outcome can be matched to one of them.
 */
public final class RecordingOutput implements WorldOutput {

  private final Simulation sim;
  private final List<PathResult> results = new ArrayList<>();
  private final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);

  /** Requests per mob that have no outcome yet. */
  private final Map<Integer, Integer> open = new TreeMap<>();

  private final List<String> unmatched = new ArrayList<>();

  public RecordingOutput(Simulation sim) {
    this.sim = sim;
  }

  /** A request for {@code mobId} went in. Called before the controller sees it. */
  public void issued(int mobId) {
    open.merge(mobId, 1, Integer::sum);
  }

  @Override
  public void pathComputed(PathResult result) {
    sim.digest().mix(sim.now()).mix(result.toString());
    results.add(result);
    settle(result.mobId());
  }

  @Override
  public void pathDropped(int mobId, DropReason reason) {
    sim.digest().mix(sim.now()).mix("drop").mix(mobId).mix(reason.name());
    drops.merge(reason, 1, Integer::sum);
    settle(mobId);
  }

  private void settle(int mobId) {
    Integer count = open.get(mobId);
    if (count == null) {
      unmatched.add("mob " + mobId + " got an outcome with no request open");
    } else if (count == 1) {
      open.remove(mobId);
    } else {
      open.put(mobId, count - 1);
    }
  }

  /**
   * Every outcome that matched no open request, then every mob with requests that have none yet.
   * Exactly one outcome per request leaves this empty once the run has drained.
   */
  public List<String> ledgerViolations() {
    List<String> violations = new ArrayList<>(unmatched);
    for (Map.Entry<Integer, Integer> entry : open.entrySet()) {
      violations.add("mob " + entry.getKey() + " has " + entry.getValue() + " requests open");
    }
    return violations;
  }

  public List<PathResult> results() {
    return List.copyOf(results);
  }

  public int answered() {
    return results.size();
  }

  public int dropped() {
    return drops.values().stream().mapToInt(Integer::intValue).sum();
  }

  public int dropped(DropReason reason) {
    return drops.getOrDefault(reason, 0);
  }

  public Map<DropReason, Integer> drops() {
    return new EnumMap<>(drops);
  }
}
