package io.github.davidmenggx.maestro.controller.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.core.NodeHealth.Circuit;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.sim.SimMetrics;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NodeHealthTest {

  private static final NodeId NODE = NodeId.of(0);

  private static final NodeId OTHER = NodeId.of(1);

  private static final long NANOS_PER_TICK = 50_000_000L;

  private static final long PROBE = 7;

  /** Heartbeats the node sends at its own interval of 1 s. */
  private static final int HEARTBEAT_TICKS = 20;

  private final SimMetrics metrics = new SimMetrics();

  private final NodeHealth health = new NodeHealth(metrics, List.of(NODE, OTHER));

  private long tick;

  @Test
  void phiIsLowAtTheMeanAndRisesWithoutBoundPastIt() {
    double second = 1e9;
    double deviation = 0.2e9;
    // At the mean, the next heartbeat is as likely to come as not.
    assertEquals(StrictMath.log10(2), NodeHealth.phi(second, second, deviation), 1e-9);
    assertTrue(NodeHealth.phi(0.5 * second, second, deviation) < 0.01);
    double previous = 0;
    for (double elapsed = second; elapsed < 4 * second; elapsed += 0.1 * second) {
      double phi = NodeHealth.phi(elapsed, second, deviation);
      assertTrue(phi >= previous, "phi fell at " + elapsed);
      previous = phi;
    }
    assertEquals(Double.POSITIVE_INFINITY, NodeHealth.phi(100 * second, second, deviation));
  }

  @Test
  void aNodeWhoseHeartbeatsStopIsSuspectedWithinAFewSecondsAndClearedByTheNext() {
    health.greeted(NODE, nanos());
    for (int beat = 0; beat < 10; beat++) {
      ticks(HEARTBEAT_TICKS);
      health.heartbeat(NODE, nanos());
    }
    assertEquals(List.of(), ticks(2 * HEARTBEAT_TICKS));
    assertFalse(health.suspected(NODE));

    List<NodeId> lost = ticks(2 * HEARTBEAT_TICKS);
    assertEquals(List.of(NODE), lost);
    assertTrue(health.suspected(NODE));
    assertFalse(health.admits(NODE));
    assertEquals(1, metrics.counter("node.suspected", "node", NODE.toString()));
    assertEquals(0, metrics.gauge("node.in_service", "node", NODE.toString()));

    health.heartbeat(NODE, nanos());
    ticks(1);
    assertFalse(health.suspected(NODE));
    assertTrue(health.admits(NODE));
    assertEquals(1, metrics.gauge("node.in_service", "node", NODE.toString()));
  }

  @Test
  void aNodeJustGreetedIsGivenTheAssumedIntervalBeforeItIsSuspected() {
    health.greeted(NODE, nanos());
    assertEquals(List.of(), ticks(2 * HEARTBEAT_TICKS));
    assertEquals(List.of(NODE), ticks(3 * HEARTBEAT_TICKS));
  }

  @Test
  void heartbeatsHeardDuringAServerStallKeepTheTimesTheyArrived() {
    health.greeted(NODE, nanos());
    // The server stalls for 5 s, and hears the heartbeats of that time at once afterwards.
    long stalledFrom = nanos();
    // No tick is judged while the server stalls.
    tick += 5 * HEARTBEAT_TICKS;
    for (int beat = 1; beat <= 5; beat++) {
      health.heartbeat(NODE, stalledFrom + beat * HEARTBEAT_TICKS * NANOS_PER_TICK);
    }
    assertEquals(7, health.samples(NODE));
    assertEquals(List.of(), ticks(1));

    // It learned 1 s intervals, so a dead node is found as fast as with no stall.
    assertEquals(List.of(), ticks(2 * HEARTBEAT_TICKS));
    assertEquals(List.of(NODE), ticks(2 * HEARTBEAT_TICKS));
  }

  @Test
  void aHeartbeatNoLaterThanTheLastIsIgnored() {
    health.greeted(NODE, nanos());
    ticks(HEARTBEAT_TICKS);
    long arrived = nanos();
    health.heartbeat(NODE, arrived);
    health.heartbeat(NODE, arrived);
    health.heartbeat(NODE, arrived - 1);
    assertEquals(3, health.samples(NODE));
  }

  @Test
  void aLostLinkTakesItsHeartbeatsWithItButNotItsRequestPath() {
    health.greeted(NODE, nanos());
    quietRequests(NodeHealth.QUIET_REQUESTS);
    assertEquals(List.of(NODE), ticks(NodeHealth.QUIET_TICKS));

    health.disconnected(NODE);
    assertEquals(List.of(), ticks(10 * HEARTBEAT_TICKS));
    assertFalse(health.suspected(NODE));
    // The request path is not part of the link, so a new link does not close its circuit.
    health.greeted(NODE, nanos());
    assertEquals(Circuit.HALF_OPEN, health.circuit(NODE));
  }

  @Test
  void requestsLeftUnansweredPastTheBudgetTakeTheNodeOutOfService() {
    quietRequests(NodeHealth.QUIET_REQUESTS);

    assertEquals(List.of(), ticks(NodeHealth.QUIET_TICKS - 1));
    assertTrue(health.admits(NODE));
    assertEquals(List.of(NODE), ticks(1));
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    assertFalse(health.admits(NODE));
    // Only once.
    assertEquals(List.of(), ticks(1));
    assertTrue(health.admits(OTHER));
  }

  @Test
  void oneLostRequestDoesNotTakeANodeOutOfService() {
    // One request and its resends, all lost to the network.
    quietRequests(1);
    health.sent(NODE, tick + 1, 0);
    health.sent(NODE, tick + 2, 0);

    assertEquals(List.of(), ticks(5 * NodeHealth.QUIET_TICKS));
    assertEquals(Circuit.CLOSED, health.circuit(NODE));
  }

  @Test
  void anOldUnansweredRequestDoesNotTakeTheNodeOutWithTheNextOne() {
    // A request whose answer was lost, long ago.
    quietRequests(1);
    ticks(NodeHealth.QUIET_WINDOW_TICKS + 1);
    quietRequests(1);
    assertEquals(List.of(), ticks(1));
    // The quiet is judged from the newer requests alone.
    quietRequests(1);
    assertEquals(List.of(), ticks(NodeHealth.QUIET_TICKS - 2));
    assertEquals(List.of(NODE), ticks(1));
  }

  @Test
  void twoUnansweredRequestsWithinTheWindowAreEnough() {
    quietRequests(1);
    ticks(NodeHealth.QUIET_WINDOW_TICKS - 1);
    // The older one is long past its budget, so the newer one shows the path is still quiet.
    quietRequests(1);
    assertEquals(List.of(NODE), ticks(1));
  }

  @Test
  void aLostLinkForgetsTheRequestsSentOnIt() {
    health.greeted(NODE, nanos());
    quietRequests(1);
    health.disconnected(NODE);
    health.greeted(NODE, nanos());
    quietRequests(1);
    assertEquals(List.of(), ticks(5 * NodeHealth.QUIET_TICKS));
    assertEquals(Circuit.CLOSED, health.circuit(NODE));
  }

  @Test
  void anAnswerInTimeEndsTheQuiet() {
    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS - 1);
    health.answered(NODE);
    assertEquals(List.of(), ticks(5 * NodeHealth.QUIET_TICKS));
    // The quiet starts over at the next request.
    quietRequests(NodeHealth.QUIET_REQUESTS);
    assertEquals(List.of(NODE), ticks(NodeHealth.QUIET_TICKS));
  }

  @Test
  void afterTheBackoffOneRequestIsLetThroughAsAProbe() {
    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);

    ticks(NodeHealth.FIRST_BACKOFF_TICKS - 1);
    assertFalse(health.admits(NODE));
    ticks(1);
    assertEquals(Circuit.HALF_OPEN, health.circuit(NODE));
    assertTrue(health.admits(NODE));

    health.admitted(NODE, PROBE, tick);
    assertEquals(Circuit.PROBING, health.circuit(NODE));
    assertFalse(health.admits(NODE));
    // The probe is out, so the node stays in service and its probe is not dropped.
    assertEquals(List.of(), ticks(NodeHealth.PROBE_TICKS));

    health.answered(NODE);
    assertEquals(Circuit.CLOSED, health.circuit(NODE));
    assertTrue(health.admits(NODE));
    assertEquals(1, metrics.counter("node.circuit", "node", NODE.toString(), "state", "closed"));
  }

  @Test
  void aProbeWithdrawnBeforeItLeftLetsTheNextRequestProbe() {
    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);
    ticks(NodeHealth.FIRST_BACKOFF_TICKS);
    health.admitted(NODE, PROBE, tick);

    // Another request going changes nothing.
    health.withdrawn(NODE, PROBE + 1);
    assertEquals(Circuit.PROBING, health.circuit(NODE));
    health.withdrawn(NODE, PROBE);
    assertEquals(Circuit.HALF_OPEN, health.circuit(NODE));
    assertTrue(health.admits(NODE));

    // The next one probes, and its failure is the first.
    health.admitted(NODE, PROBE + 2, tick);
    ticks(NodeHealth.PROBE_TICKS + 1);
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    ticks(2 * NodeHealth.FIRST_BACKOFF_TICKS - 1);
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    ticks(1);
    assertEquals(Circuit.HALF_OPEN, health.circuit(NODE));
  }

  @Test
  void eachFailedProbeDoublesTheBackoffUpToItsCap() {
    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);

    int backoff = NodeHealth.FIRST_BACKOFF_TICKS;
    for (int probe = 0; probe < 6; probe++) {
      ticks(backoff - 1);
      assertEquals(Circuit.OPEN, health.circuit(NODE), "probe " + probe);
      ticks(1);
      assertEquals(Circuit.HALF_OPEN, health.circuit(NODE), "probe " + probe);
      health.admitted(NODE, PROBE + probe, tick);
      assertEquals(List.of(), ticks(NodeHealth.PROBE_TICKS));
      // Its time runs out, and the node goes out of service again.
      assertEquals(List.of(NODE), ticks(1));
      backoff = Math.min(2 * backoff, NodeHealth.MAX_BACKOFF_TICKS);
    }
    assertEquals(NodeHealth.MAX_BACKOFF_TICKS, backoff);
  }

  @Test
  void aProbeAnsweredAfterABackoffStartsTheNextQuietAtTheFirstBackoff() {
    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);
    ticks(NodeHealth.FIRST_BACKOFF_TICKS);
    health.admitted(NODE, PROBE, tick);
    ticks(NodeHealth.PROBE_TICKS + 1);
    ticks(2 * NodeHealth.FIRST_BACKOFF_TICKS);
    health.admitted(NODE, PROBE + 1, tick);
    health.answered(NODE);

    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    ticks(NodeHealth.FIRST_BACKOFF_TICKS);
    assertEquals(Circuit.HALF_OPEN, health.circuit(NODE));
  }

  @Test
  void aNodeAlreadyOutOfServiceIsNotReportedAgain() {
    health.greeted(NODE, nanos());
    ticks(2 * HEARTBEAT_TICKS);
    quietRequests(NodeHealth.QUIET_REQUESTS);
    assertEquals(List.of(NODE), ticks(NodeHealth.QUIET_TICKS));
    // Its heartbeats are overdue too, while its circuit is still open.
    assertEquals(List.of(), ticks(HEARTBEAT_TICKS - 2));
    assertTrue(health.suspected(NODE));
    assertEquals(Circuit.OPEN, health.circuit(NODE));
  }

  @Test
  void requestsLeftWhenANodeIsSuspectedShowNothingOnceItIsBack() {
    health.greeted(NODE, nanos());
    ticks(3 * HEARTBEAT_TICKS);
    // A request it took before it went silent, which is canceled when it is suspected.
    quietRequests(1);
    assertEquals(List.of(NODE), ticks(HEARTBEAT_TICKS / 2));
    assertTrue(health.suspected(NODE));

    health.heartbeat(NODE, nanos());
    assertEquals(List.of(), ticks(1));
    quietRequests(1);
    assertEquals(List.of(), ticks(NodeHealth.QUIET_TICKS - 1));
    assertEquals(Circuit.CLOSED, health.circuit(NODE));
  }

  /** {@code count} requests leave for the node in this tick, each for the first time. */
  private void quietRequests(int count) {
    health.sent(NODE, tick, count);
  }

  /** Runs {@code count} ticks and returns the nodes that went out of service in them. */
  private List<NodeId> ticks(int count) {
    List<NodeId> lost = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      tick++;
      lost.addAll(health.evaluate(tick, nanos()));
    }
    return lost;
  }

  private long nanos() {
    return tick * NANOS_PER_TICK;
  }
}
