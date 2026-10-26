package io.github.davidmenggx.maestro.controller.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CapacityTest {

  private static final NodeId NODE = NodeId.of(0);
  private static final int ASSUMED = 100;

  private final Capacity capacity = new Capacity(ASSUMED, List.of(NODE, NodeId.of(1)));

  @Test
  void aNodeIsTakenAtTheAssumedCapacityUntilItsSearchesAreMeasured() {
    assertEquals(ASSUMED, capacity.of(NODE));
    // Short of a sample: one search took a whole tick.
    capacity.heartbeat(NODE, Capacity.MIN_SEARCHES - 1, (Capacity.MIN_SEARCHES - 1) * tick(1), 1);
    assertEquals(ASSUMED, capacity.of(NODE));
  }

  @Test
  void heartbeatsArePooledIntoASampleOfTheWorkersOverTheMeanSearchTime() {
    int half = Capacity.MIN_SEARCHES / 2;
    // Two workers whose searches take a tenth of a tick: twenty a tick.
    capacity.heartbeat(NODE, half, half * tick(10), 2);
    assertEquals(ASSUMED, capacity.of(NODE));
    capacity.heartbeat(NODE, half, half * tick(10), 2);
    assertEquals(20.0, capacity.of(NODE), 1e-9);
  }

  @Test
  void theRateIsTheMedianOfTheLastSamplesSoOneStallDoesNotCount() {
    sample(NODE, 20);
    sample(NODE, 20);
    // A search paused for seconds makes one sample far slower.
    sample(NODE, 0.5);
    assertEquals(20.0, capacity.of(NODE), 1e-9);
    // Once most samples are slow, the node is.
    sample(NODE, 5);
    sample(NODE, 5);
    assertEquals(5.0, capacity.of(NODE), 1e-9);
    // Only the last samples count.
    for (int i = 0; i < Capacity.SAMPLES; i++) {
      sample(NODE, 40);
    }
    assertEquals(40.0, capacity.of(NODE), 1e-9);
  }

  @Test
  void ofTwoSamplesTheFasterCounts() {
    sample(NODE, 20);
    sample(NODE, 2);
    assertEquals(20.0, capacity.of(NODE), 1e-9);
  }

  @Test
  void theAssumedCapacityCapsTheRate() {
    sample(NODE, 10 * ASSUMED);
    assertEquals(ASSUMED, capacity.of(NODE));
  }

  @Test
  void aNewLinkStartsOver() {
    sample(NODE, 20);
    capacity.heartbeat(NODE, Capacity.MIN_SEARCHES - 1, tick(1), 1);
    capacity.linkLost(NODE);
    assertEquals(ASSUMED, capacity.of(NODE));
    // The searches pooled before the link went do not count toward the next sample.
    capacity.heartbeat(NODE, 1, tick(20), 1);
    assertEquals(ASSUMED, capacity.of(NODE));
  }

  @Test
  void eachNodeIsMeasuredApart() {
    sample(NODE, 20);
    assertEquals(ASSUMED, capacity.of(NodeId.of(1)));
  }

  @Test
  void aHeartbeatWithNoWorkersOrNoTimeMeasuresNothing() {
    capacity.heartbeat(NODE, Capacity.MIN_SEARCHES, Capacity.MIN_SEARCHES * tick(10), 0);
    assertEquals(ASSUMED, capacity.of(NODE));
    NodeId other = NodeId.of(1);
    capacity.heartbeat(other, Capacity.MIN_SEARCHES, 0, 1);
    assertEquals(ASSUMED, capacity.of(other));
  }

  @Test
  void aNodeOutsideTheFleetIsRefused() {
    assertThrows(IllegalStateException.class, () -> capacity.of(NodeId.of(9)));
    assertThrows(IllegalArgumentException.class, () -> new Capacity(0, List.of(NODE)));
  }

  /** One sample of a single worker searching at {@code perTick} requests a tick. */
  private void sample(NodeId node, double perTick) {
    long micros = Math.round(Capacity.MIN_SEARCHES * Capacity.TICK_MICROS / perTick);
    capacity.heartbeat(node, Capacity.MIN_SEARCHES, micros, 1);
  }

  /** Microseconds of a search that serves {@code perTick} a tick on one worker. */
  private static long tick(int perTick) {
    return Capacity.TICK_MICROS / perTick;
  }
}
