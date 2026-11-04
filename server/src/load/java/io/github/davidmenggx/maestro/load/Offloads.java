package io.github.davidmenggx.maestro.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What became of the offloaded requests: each count by name, as the fleet's summary names it, and
 * answers by ticks from request to answer, the last entry holding any later. {@code nodeRates} is
 * the requests a tick each node's heartbeats show it serves, not capped by `nodeCapacity`, in fleet
 * order, null for a node not measured yet.
 */
record Offloads(Map<String, Long> counts, List<Long> ticksLate, List<Double> nodeRates) {

  /** Nothing offloaded, as when no fleet is active. */
  static final Offloads NONE = new Offloads(Map.of(), List.of(), List.of());

  Offloads {
    counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    ticksLate = List.copyOf(ticksLate);
    // Holds nulls, which `List.copyOf` refuses.
    nodeRates = Collections.unmodifiableList(new ArrayList<>(nodeRates));
  }

  /** What happened after {@code earlier}, taken from the same fleet, with the rates as of now. */
  Offloads since(Offloads earlier) {
    Map<String, Long> counted = new LinkedHashMap<>();
    counts.forEach(
        (name, count) -> counted.put(name, count - earlier.counts.getOrDefault(name, 0L)));
    List<Long> late = new ArrayList<>();
    for (int ticks = 0; ticks < ticksLate.size(); ticks++) {
      long before = ticks < earlier.ticksLate.size() ? earlier.ticksLate.get(ticks) : 0;
      late.add(ticksLate.get(ticks) - before);
    }
    return new Offloads(counted, late, nodeRates);
  }
}
