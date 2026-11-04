package io.github.davidmenggx.maestro.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What became of the offloaded requests: each count by name, as the fleet's summary names it, and
 * answers by ticks from request to answer, the last entry holding any later.
 */
public record OffloadStats(Map<String, Long> counts, List<Long> ticksLate) {

  public OffloadStats {
    counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    ticksLate = List.copyOf(ticksLate);
  }
}
