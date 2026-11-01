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
    assertFalse(health.inService(NODE));
    assertEquals(1, metrics.counter("node.suspected", "node", NODE.toString()));
    assertEquals(0, metrics.gauge("node.in_service", "node", NODE.toString()));

    health.heartbeat(NODE, nanos());
    ticks(1);
    assertFalse(health.suspected(NODE));
    assertTrue(health.inService(NODE));
    assertEquals(1, metrics.gauge("node.in_service", "node", NODE.toString()));
  }

  @Test
  void eachHeardNodesPhiIsReportedEveryTickAndCrossesTheThresholdWhenItsHeartbeatsStop() {
    String label = NODE.toString();
    health.greeted(NODE, nanos());
    for (int beat = 0; beat < 10; beat++) {
      ticks(HEARTBEAT_TICKS);
      health.heartbeat(NODE, nanos());
    }
    // One value per tick judged, and none for a node never heard from.
    assertEquals(10 * HEARTBEAT_TICKS, metrics.gaugeValues("node.phi", "node", label).size());
    assertEquals(List.of(), metrics.gaugeValues("node.phi", "node", OTHER.toString()));
    assertTrue(metrics.gauge("node.phi", "node", label) < NodeHealth.PHI_THRESHOLD);

    ticks(4 * HEARTBEAT_TICKS);
    assertTrue(metrics.gauge("node.phi", "node", label) >= NodeHealth.PHI_THRESHOLD);

    health.heartbeat(NODE, nanos());
    ticks(1);
    assertTrue(metrics.gauge("node.phi", "node", label) < NodeHealth.PHI_THRESHOLD);

    // A node with no link is not judged, so its phi reads 0 once and is not reported again.
    int reported = metrics.gaugeValues("node.phi", "node", label).size();
    health.disconnected(NODE);
    ticks(HEARTBEAT_TICKS);
    assertEquals(reported + 1, metrics.gaugeValues("node.phi", "node", label).size());
    assertEquals(0, metrics.gauge("node.phi", "node", label));
    health.disconnected(NODE);
    assertEquals(reported + 1, metrics.gaugeValues("node.phi", "node", label).size());

    // One never heard from has no phi to clear.
    health.disconnected(OTHER);
    assertEquals(List.of(), metrics.gaugeValues("node.phi", "node", OTHER.toString()));
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
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    assertEquals(List.of(NODE), probes());
  }

  @Test
  void requestsLeftUnansweredPastTheBudgetTakeTheNodeOutOfService() {
    quietRequests(NodeHealth.QUIET_REQUESTS);

    assertEquals(List.of(), ticks(NodeHealth.QUIET_TICKS - 1));
    assertTrue(health.inService(NODE));
    assertEquals(List.of(NODE), ticks(1));
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    assertFalse(health.inService(NODE));
    // Only once.
    assertEquals(List.of(), ticks(1));
    assertTrue(health.inService(OTHER));
  }

  @Test
  void oneLostRequestDoesNotTakeANodeOutOfService() {
    // One request and its resends, all lost to the network. Only its first copy falling due counts.
    quietRequests(1);

    assertEquals(List.of(), ticks(5 * NodeHealth.QUIET_TICKS));
    assertEquals(Circuit.CLOSED, health.circuit(NODE));
  }

  @Test
  void aQuietPathIsTimedFromWhenItsRequestsFirstLeftNotWhenTheyFellDue() {
    long left = tick;
    ticks(3);
    // A queue ahead of them put their due tick three ticks after they left.
    health.missed(NODE, left);
    health.missed(NODE, left);

    assertEquals(List.of(), ticks(NodeHealth.QUIET_TICKS - 4));
    assertEquals(List.of(NODE), ticks(1));
  }

  @Test
  void theOldestMissedRequestTimesTheQuietWhateverOrderTheyFellDueIn() {
    long older = tick;
    ticks(2);
    quietRequests(1);
    health.missed(NODE, older);

    assertEquals(List.of(), ticks(NodeHealth.QUIET_TICKS - 3));
    assertEquals(List.of(NODE), ticks(1));
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
    // The older one is long past its budget, and the newer one fell due unanswered too, so the
    // path is still quiet.
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
    health.answered(NODE, tick);
    assertEquals(List.of(), ticks(5 * NodeHealth.QUIET_TICKS));
    // The quiet starts over at the next request.
    quietRequests(NodeHealth.QUIET_REQUESTS);
    assertEquals(List.of(NODE), ticks(NodeHealth.QUIET_TICKS));
  }

  @Test
  void afterTheBackoffTheNodeIsProbedAndAnAnswerClosesItsCircuit() {
    health.answered(NODE, tick);
    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);
    assertFalse(health.verified(NODE));

    ticks(NodeHealth.FIRST_BACKOFF_TICKS - 1);
    assertEquals(List.of(), probes());
    ticks(1);
    assertEquals(List.of(NODE), probes());
    assertEquals(Circuit.PROBING, health.circuit(NODE));
    assertFalse(health.inService(NODE));
    health.probeSent(NODE, PROBE, tick);
    assertEquals(PROBE, health.probeOut(NODE));
    // One probe at a time.
    assertEquals(List.of(), probes());
    assertEquals(List.of(), ticks(NodeHealth.PROBE_TICKS));

    // An answer to another probe shows nothing.
    health.probeAnswered(NODE, PROBE + 1, tick);
    assertEquals(Circuit.PROBING, health.circuit(NODE));
    health.probeAnswered(NODE, PROBE, tick);
    assertEquals(Circuit.CLOSED, health.circuit(NODE));
    assertTrue(health.inService(NODE));
    assertTrue(health.verified(NODE));
    assertEquals(Long.MIN_VALUE, health.probeOut(NODE));
    assertEquals(1, metrics.counter("node.circuit", "node", NODE.toString(), "state", "closed"));
  }

  @Test
  void aLostLinkWithdrawsTheProbeWhichIsSentAgainOnceTheNodeIsReachable() {
    health.greeted(NODE, nanos());
    health.answered(NODE, tick);
    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);
    ticks(NodeHealth.FIRST_BACKOFF_TICKS);
    assertEquals(List.of(NODE), probes());
    health.probeSent(NODE, PROBE, tick);

    health.disconnected(NODE);
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    assertEquals(List.of(), health.probesDue(tick, node -> false));
    assertEquals(List.of(NODE), probes());
    // A withdrawn probe is no failure, so the backoff did not double.
    health.probeSent(NODE, PROBE + 1, tick);
    ticks(NodeHealth.PROBE_TICKS + 1);
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    ticks(2 * NodeHealth.FIRST_BACKOFF_TICKS - 1);
    assertEquals(List.of(), probes());
    ticks(1);
    assertEquals(List.of(NODE), probes());
  }

  @Test
  void eachFailedProbeDoublesTheBackoffUpToItsCap() {
    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);

    int backoff = NodeHealth.FIRST_BACKOFF_TICKS;
    for (int probe = 0; probe < 6; probe++) {
      ticks(backoff - 1);
      assertEquals(List.of(), probes(), "probe " + probe);
      ticks(1);
      assertEquals(List.of(NODE), probes(), "probe " + probe);
      health.probeSent(NODE, PROBE + probe, tick);
      assertEquals(List.of(), ticks(NodeHealth.PROBE_TICKS));
      assertEquals(Circuit.PROBING, health.circuit(NODE));
      // Its time runs out, and the circuit opens again.
      ticks(1);
      assertEquals(Circuit.OPEN, health.circuit(NODE));
      backoff = Math.min(2 * backoff, NodeHealth.MAX_BACKOFF_TICKS);
    }
    assertEquals(NodeHealth.MAX_BACKOFF_TICKS, backoff);
  }

  @Test
  void aProbeAnsweredAfterABackoffStartsTheNextQuietAtTheFirstBackoff() {
    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);
    ticks(NodeHealth.FIRST_BACKOFF_TICKS);
    probes();
    health.probeSent(NODE, PROBE, tick);
    ticks(NodeHealth.PROBE_TICKS + 1);
    ticks(2 * NodeHealth.FIRST_BACKOFF_TICKS);
    probes();
    health.probeSent(NODE, PROBE + 1, tick);
    health.probeAnswered(NODE, PROBE + 1, tick);

    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    ticks(NodeHealth.FIRST_BACKOFF_TICKS);
    assertEquals(List.of(NODE), probes());
  }

  @Test
  void anAnswerInTimeClosesAnOpenCircuit() {
    quietRequests(NodeHealth.QUIET_REQUESTS);
    ticks(NodeHealth.QUIET_TICKS);
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    // A late copy of a request answered within its budget.
    health.answered(NODE, tick);
    assertEquals(Circuit.CLOSED, health.circuit(NODE));
    assertTrue(health.verified(NODE));
  }

  @Test
  void aNodeNothingHasShownToWorkIsCheckedOnceASecond() {
    // Never answered: checked as soon as it can be reached.
    assertEquals(List.of(), health.probesDue(tick, node -> false));
    assertEquals(List.of(NODE, OTHER), health.probesDue(tick, node -> true));
    health.probeSent(NODE, PROBE, tick);
    health.probeSent(OTHER, PROBE + 1, tick);
    ticks(2);
    health.probeAnswered(NODE, PROBE, tick);
    health.probeAnswered(OTHER, PROBE + 1, tick);
    assertTrue(health.verified(NODE));
    assertEquals(Circuit.CLOSED, health.circuit(NODE));

    // Answers keep a busy node from being checked; an idle one is checked a second on.
    for (int i = 1; i < NodeHealth.CHECK_TICKS; i++) {
      ticks(1);
      health.answered(NODE, tick);
      assertEquals(List.of(), health.probesDue(tick, node -> true));
    }
    ticks(1);
    health.answered(NODE, tick);
    assertEquals(List.of(OTHER), health.probesDue(tick, node -> true));
  }

  @Test
  void aCheckLeftUnansweredQuietsThePath() {
    health.answered(NODE, tick);
    ticks(NodeHealth.CHECK_TICKS);
    assertTrue(probes().contains(NODE));
    health.probeSent(NODE, PROBE, tick);
    // The node stays in service while it is checked.
    assertEquals(List.of(), ticks(NodeHealth.PROBE_TICKS));
    assertTrue(health.inService(NODE));
    assertEquals(List.of(NODE), ticks(1));
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    assertFalse(health.verified(NODE));
    assertEquals(1, metrics.counter("node.check.failed", "node", NODE.toString()));
    // Its first probe comes after the first backoff.
    ticks(NodeHealth.FIRST_BACKOFF_TICKS - 1);
    assertFalse(probes().contains(NODE));
    ticks(1);
    assertTrue(probes().contains(NODE));
  }

  @Test
  void anAnswerInTimeAnswersACheckWhoseProbeWasLost() {
    health.answered(NODE, tick);
    ticks(NodeHealth.CHECK_TICKS);
    assertTrue(probes().contains(NODE));
    health.probeSent(NODE, PROBE, tick);
    // The probe and its copies are lost, but requests are answered in time.
    for (int i = 0; i < 2 * NodeHealth.PROBE_TICKS; i++) {
      health.answered(NODE, tick);
      assertEquals(List.of(), ticks(1));
    }
    assertTrue(health.inService(NODE));
    assertEquals(Circuit.CLOSED, health.circuit(NODE));
    assertEquals(Long.MIN_VALUE, health.probeOut(NODE));
    assertEquals(0, metrics.counter("node.check.failed", "node", NODE.toString()));
    // A late answer to the lost probe changes nothing.
    health.probeAnswered(NODE, PROBE, tick);
    assertEquals(Circuit.CLOSED, health.circuit(NODE));
  }

  @Test
  void aSuspectedNodeIsNotProbed() {
    health.greeted(NODE, nanos());
    ticks(4 * HEARTBEAT_TICKS);
    assertTrue(health.suspected(NODE));
    assertFalse(probes().contains(NODE));
    health.heartbeat(NODE, nanos());
    ticks(1);
    assertTrue(probes().contains(NODE));
  }

  @Test
  void aLostLinkUnverifiesTheNode() {
    health.answered(NODE, tick);
    assertTrue(health.verified(NODE));
    health.disconnected(NODE);
    assertFalse(health.verified(NODE));
    assertEquals(Circuit.CLOSED, health.circuit(NODE));
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
  void requestsThatFellDueBeforeASuspicionQuietThePathWhileItLasts() {
    health.greeted(NODE, nanos());
    ticks(3 * HEARTBEAT_TICKS);
    // Requests fall due unanswered, and the heartbeats stop before the path counts as quiet.
    quietRequests(NodeHealth.QUIET_REQUESTS);
    assertEquals(List.of(NODE), ticks(HEARTBEAT_TICKS / 2));
    assertTrue(health.suspected(NODE));
    assertEquals(Circuit.CLOSED, health.circuit(NODE));

    ticks(NodeHealth.QUIET_TICKS - HEARTBEAT_TICKS / 2);
    assertEquals(Circuit.OPEN, health.circuit(NODE));
    // Heartbeats alone do not show the request path works again.
    health.heartbeat(NODE, nanos());
    assertEquals(List.of(), ticks(1));
    assertFalse(health.suspected(NODE));
    assertFalse(health.inService(NODE));
  }

  @Test
  void aRequestThatFellDueBeforeASuspicionCountsWithOneAfterIt() {
    health.greeted(NODE, nanos());
    ticks(3 * HEARTBEAT_TICKS);
    quietRequests(1);
    assertEquals(List.of(NODE), ticks(HEARTBEAT_TICKS / 2));
    health.heartbeat(NODE, nanos());
    assertEquals(List.of(), ticks(1));
    assertTrue(health.inService(NODE));

    quietRequests(1);
    assertEquals(List.of(NODE), ticks(1));
    assertEquals(Circuit.OPEN, health.circuit(NODE));
  }

  /** Whether {@link #NODE} is due a probe now; the other node cannot be reached. */
  private List<NodeId> probes() {
    return health.probesDue(tick, NODE::equals);
  }

  /** {@code count} requests first sent to the node this tick fall due unanswered at once. */
  private void quietRequests(int count) {
    for (int i = 0; i < count; i++) {
      health.missed(NODE, tick);
    }
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
