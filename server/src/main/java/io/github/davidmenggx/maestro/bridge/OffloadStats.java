package io.github.davidmenggx.maestro.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What became of the offloaded requests: each count by name, as the fleet's summary names it, and
 * answers by ticks from request to answer, the last entry holding any later.
 */
public record OffloadStats(Map<String, Long> counts, List<Long> ticksLate) {

  /** Nothing offloaded, as when no fleet is active. */
  public static final OffloadStats NONE = new OffloadStats(Map.of(), List.of());

  public OffloadStats {
    counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    ticksLate = List.copyOf(ticksLate);
  }

  /** What happened after {@code earlier}, taken from the same fleet. */
  public OffloadStats since(OffloadStats earlier) {
    Map<String, Long> counted = new LinkedHashMap<>();
    counts.forEach(
        (name, count) -> counted.put(name, count - earlier.counts.getOrDefault(name, 0L)));
    List<Long> late = new ArrayList<>();
    for (int ticks = 0; ticks < ticksLate.size(); ticks++) {
      long before = ticks < earlier.ticksLate.size() ? earlier.ticksLate.get(ticks) : 0;
      late.add(ticksLate.get(ticks) - before);
    }
    return new OffloadStats(counted, late);
  }
}
