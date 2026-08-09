package com.smpler.maestro.controller.sim;

import com.smpler.maestro.controller.world.WorldOutput;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Records what came back out of the controller, and folds it into the run digest. */
public final class RecordingOutput implements WorldOutput {

  private final Simulation sim;
  private final List<PathResult> results = new ArrayList<>();
  private final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);

  public RecordingOutput(Simulation sim) {
    this.sim = sim;
  }

  @Override
  public void pathComputed(PathResult result) {
    sim.digest().mix(sim.now()).mix(result.toString());
    results.add(result);
  }

  @Override
  public void pathDropped(int mobId, DropReason reason) {
    sim.digest().mix(sim.now()).mix("drop").mix(mobId).mix(reason.name());
    drops.merge(reason, 1, Integer::sum);
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
