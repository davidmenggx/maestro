package io.github.davidmenggx.maestro.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OffloadsTest {

  @Test
  void sinceSubtractsEachCountAndEachTicksLateEntry() {
    Offloads before =
        new Offloads(ordered("requested", 3, "applied", 1), List.of(1L, 2L), List.of());
    Offloads after =
        new Offloads(ordered("requested", 10, "applied", 4), List.of(5L, 2L), List.of());

    Offloads delta = after.since(before);

    assertEquals(ordered("requested", 7, "applied", 3), delta.counts());
    assertEquals(List.of(4L, 0L), delta.ticksLate());
  }

  @Test
  void sinceNothingIsEverything() {
    Offloads stats =
        new Offloads(ordered("requested", 3, "shed", 2), List.of(1L, 0L, 6L), List.of());

    assertEquals(stats, stats.since(Offloads.NONE));
  }

  @Test
  void aCountFirstSeenLaterCountsFromZero() {
    Offloads before = new Offloads(ordered("requested", 3), List.of(1L), List.of());
    Offloads after = new Offloads(ordered("requested", 5, "shed", 2), List.of(1L, 4L), List.of());

    Offloads delta = after.since(before);

    assertEquals(ordered("requested", 2, "shed", 2), delta.counts());
    assertEquals(List.of(0L, 4L), delta.ticksLate());
  }

  @Test
  void countsKeepTheirOrder() {
    Offloads stats =
        new Offloads(ordered("requested", 1, "refused", 2, "answered", 3), List.of(), List.of());

    assertEquals(List.of("requested", "refused", "answered"), List.copyOf(stats.counts().keySet()));
  }

  @Test
  void isACopyThatCannotChange() {
    Map<String, Long> counts = ordered("requested", 1);
    Offloads stats = new Offloads(counts, List.of(1L), List.of());
    counts.put("requested", 5L);

    assertEquals(1, stats.counts().get("requested"));
    assertThrows(UnsupportedOperationException.class, () -> stats.counts().put("shed", 1L));
  }

  @Test
  void sinceKeepsTheLaterRatesAndANodeNotMeasuredYet() {
    Offloads before = new Offloads(ordered("requested", 1), List.of(), List.of(40.0, 30.0));
    Offloads after = new Offloads(ordered("requested", 2), List.of(), Arrays.asList(55.5, null));

    Offloads delta = after.since(before);

    assertEquals(Arrays.asList(55.5, null), delta.nodeRates());
    assertThrows(UnsupportedOperationException.class, () -> delta.nodeRates().add(1.0));
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
