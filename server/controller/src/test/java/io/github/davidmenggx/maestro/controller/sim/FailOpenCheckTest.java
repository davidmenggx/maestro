package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.davidmenggx.maestro.controller.core.NodeHealth;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.sim.FailOpenCheck.Missed;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The fail-open check against traces of what a controller sent, good and bad. */
class FailOpenCheckTest {

  private static final NodeId NODE = NodeId.of(0);

  private static final long RUN = 1;

  private static final long START = 10;

  /** Ticks from a request's first copy to its resend when the node holds nothing ahead of it. */
  private static final long RESEND_AFTER = 2;

  private final FailOpenCheck check = new FailOpenCheck();

  private long nextRequest = 1;

  @Test
  void aControllerThatKeepsSendingToANodeThatCannotAnswerIsCaught() {
    check.unresponsive(NODE, RUN, START, true);
    for (long tick = START + 2; tick < START + 100; tick++) {
      unanswered(tick);
    }
    check.unresponsive(NODE, RUN, START + 100, false);
    assertEquals(1, check.violations(START + 100).size());
    assertEquals(1, check.judged());
  }

  @Test
  void probesAfterEachDoubledBackoffAreAllowed() {
    check.unresponsive(NODE, RUN, START, true);
    unanswered(START + 2);
    unanswered(START + 3);
    long at = FailOpenCheck.noticed(missed(START + 2, START + 3)) + 1;
    probe(at);
    long backoff = NodeHealth.FIRST_BACKOFF_TICKS;
    for (int probe = 0; probe < 5; probe++) {
      backoff = Math.min(2 * backoff, NodeHealth.MAX_BACKOFF_TICKS);
      at += NodeHealth.PROBE_TICKS + 1 + backoff;
      probe(at);
      // A copy of a probe is no new one.
      check.probeSent(NODE, RUN, nextRequest - 1, at + 2);
    }
    check.unresponsive(NODE, RUN, at + 1, false);
    assertEquals(List.of(), check.violations(at + 1));
    assertEquals(1, check.judged());
  }

  @Test
  void probesThatNeverBackOffFurtherAreCaught() {
    check.unresponsive(NODE, RUN, START, true);
    unanswered(START + 2);
    unanswered(START + 3);
    long at = FailOpenCheck.noticed(missed(START + 2, START + 3)) + 1;
    for (int probe = 0; probe < 8; probe++) {
      probe(at);
      at += NodeHealth.PROBE_TICKS + 1 + NodeHealth.FIRST_BACKOFF_TICKS;
    }
    assertEquals(1, check.violations(at).size());
  }

  @Test
  void noRequestIsAllowedOnceTheControllerNoticed() {
    check.unresponsive(NODE, RUN, START, true);
    unanswered(START + 2);
    unanswered(START + 3);
    long noticed = FailOpenCheck.noticed(missed(START + 2, START + 3));
    send(noticed);
    assertEquals(List.of(), check.violations(noticed + 1));
    send(noticed + 1);
    assertEquals(1, check.violations(noticed + 2).size());
  }

  @Test
  void eachNewLinkMayBeProbedAtOnce() {
    check.unresponsive(NODE, RUN, START, true);
    unanswered(START + 2);
    unanswered(START + 3);
    long noticed = FailOpenCheck.noticed(missed(START + 2, START + 3));
    probe(noticed + 1);
    for (long lost = noticed + 5; lost < noticed + 50; lost += 5) {
      check.linkLost(NODE, RUN, lost);
      probe(lost + 1);
    }
    assertEquals(List.of(), check.violations(noticed + 60));
    probe(noticed + 52);
    assertEquals(1, check.violations(noticed + 60).size());
  }

  @Test
  void requestsTooFarApartAreNeverJudged() {
    check.unresponsive(NODE, RUN, START, true);
    long tick = START + 2;
    for (int i = 0; i < 4; i++) {
      unanswered(tick);
      tick += NodeHealth.QUIET_WINDOW_TICKS;
    }
    check.unresponsive(NODE, RUN, tick, false);
    assertEquals(List.of(), check.violations(tick));
    assertEquals(0, check.judged());
  }

  @Test
  void requestsNeverSentAgainShowNothing() {
    // Each was given up before it fell due, so its cancel may have been honored.
    check.unresponsive(NODE, RUN, START, true);
    for (long tick = START + 2; tick < START + 100; tick++) {
      send(tick);
    }
    check.unresponsive(NODE, RUN, START + 100, false);
    assertEquals(List.of(), check.violations(START + 100));
    assertEquals(0, check.judged());
  }

  @Test
  void requestsSentOnALinkThatWentDownShowNothingOnTheNext() {
    check.unresponsive(NODE, RUN, START, true);
    unanswered(START + 2);
    unanswered(START + 3);
    // The link goes down before the controller could have noticed, and the requests go with it.
    check.linkLost(NODE, RUN, START + 6);
    long reconnected = START + 25;
    unanswered(reconnected);
    unanswered(reconnected + 1);
    long noticed = FailOpenCheck.noticed(missed(reconnected, reconnected + 1));
    // Requests until it notices on the new link are allowed, and none after it.
    for (long tick = reconnected + 2; tick <= noticed; tick++) {
      send(tick);
    }
    assertEquals(List.of(), check.violations(noticed + 2));
    send(noticed + 1);
    assertEquals(1, check.violations(noticed + 3).size());
  }

  @Test
  void aRequestResentAfterItsLinkWasLostShowsNothingOnIt() {
    check.unresponsive(NODE, RUN, START, true);
    long first = send(START + 2);
    long second = send(START + 3);
    check.linkLost(NODE, RUN, START + 4);
    resend(first, START + 4);
    resend(second, START + 5);
    for (long tick = START + 6; tick < START + 60; tick++) {
      send(tick);
    }
    assertEquals(List.of(), check.violations(START + 60));
  }

  @Test
  void aLinkLostAfterTheControllerNoticedChangesNothing() {
    check.unresponsive(NODE, RUN, START, true);
    unanswered(START + 2);
    unanswered(START + 3);
    long noticed = FailOpenCheck.noticed(missed(START + 2, START + 3));
    check.linkLost(NODE, RUN, noticed + 1);
    send(noticed + 30);
    send(noticed + 31);
    assertEquals(1, check.violations(noticed + 32).size());
  }

  @Test
  void requestsFurtherApartThanTheQuietWindowShowNothingTogether() {
    long first = START + 2;
    assertEquals(
        Long.MAX_VALUE,
        FailOpenCheck.noticed(missed(first, first + NodeHealth.QUIET_WINDOW_TICKS)));
    assertNotEquals(
        Long.MAX_VALUE,
        FailOpenCheck.noticed(missed(first, first + NodeHealth.QUIET_WINDOW_TICKS - RESEND_AFTER)));
    // A later pair still counts.
    long later = first + 2 * NodeHealth.QUIET_WINDOW_TICKS;
    assertEquals(
        later + NodeHealth.QUIET_TICKS + FailOpenCheck.LATE_SEND_TICKS,
        FailOpenCheck.noticed(missed(first, later, later + 1)));
  }

  @Test
  void theControllerNoticesOnlyOnceTheNewerRequestFellDue() {
    long first = START + 2;
    long newer = first + NodeHealth.QUIET_TICKS + 4;
    assertEquals(
        newer + RESEND_AFTER + FailOpenCheck.LATE_SEND_TICKS,
        FailOpenCheck.noticed(missed(first, newer)));
    // A queue ahead of the newer one put its due tick later.
    assertEquals(
        newer + 6 + FailOpenCheck.LATE_SEND_TICKS,
        FailOpenCheck.noticed(List.of(new Missed(first, first + 2), new Missed(newer, newer + 6))));
  }

  /** Sends a new request at {@code tick} and returns its id. */
  private long send(long tick) {
    long id = nextRequest++;
    check.requestSent(NODE, RUN, id, tick);
    return id;
  }

  /** Sends a new probe at {@code tick}. */
  private void probe(long tick) {
    check.probeSent(NODE, RUN, nextRequest++, tick);
  }

  /** Sends a copy of request {@code id} again at {@code tick}. */
  private void resend(long id, long tick) {
    check.requestSent(NODE, RUN, id, tick);
  }

  /** Sends a new request at {@code tick}, and again once it falls due unanswered. */
  private void unanswered(long tick) {
    resend(send(tick), tick + RESEND_AFTER);
  }

  /** Requests first sent at {@code ticks}, each resent once it fell due unanswered. */
  private static List<Missed> missed(long... ticks) {
    return Arrays.stream(ticks).mapToObj(tick -> new Missed(tick, tick + RESEND_AFTER)).toList();
  }
}
