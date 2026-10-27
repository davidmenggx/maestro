package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OffloadStatsTest {

  @Test
  void sinceSubtractsEachCountAndEachTicksLateEntry() {
    OffloadStats before = new OffloadStats(ordered("requested", 3, "applied", 1), List.of(1L, 2L));
    OffloadStats after = new OffloadStats(ordered("requested", 10, "applied", 4), List.of(5L, 2L));

    OffloadStats delta = after.since(before);

    assertEquals(ordered("requested", 7, "applied", 3), delta.counts());
    assertEquals(List.of(4L, 0L), delta.ticksLate());
  }

  @Test
  void sinceNothingIsEverything() {
    OffloadStats stats = new OffloadStats(ordered("requested", 3, "shed", 2), List.of(1L, 0L, 6L));

    assertEquals(stats, stats.since(OffloadStats.NONE));
  }

  @Test
  void countsKeepTheirOrder() {
    OffloadStats stats =
        new OffloadStats(ordered("requested", 1, "refused", 2, "answered", 3), List.of());

    assertEquals(List.of("requested", "refused", "answered"), List.copyOf(stats.counts().keySet()));
  }

  @Test
  void isACopyThatCannotChange() {
    Map<String, Long> counts = ordered("requested", 1);
    OffloadStats stats = new OffloadStats(counts, List.of(1L));
    counts.put("requested", 5L);

    assertEquals(1, stats.counts().get("requested"));
    assertThrows(UnsupportedOperationException.class, () -> stats.counts().put("shed", 1L));
  }

  /** Alternating names and counts, in order. */
  private static Map<String, Long> ordered(Object... namesAndCounts) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (int i = 0; i < namesAndCounts.length; i += 2) {
      counts.put((String) namesAndCounts[i], ((Integer) namesAndCounts[i + 1]).longValue());
    }
    return counts;
  }
}
