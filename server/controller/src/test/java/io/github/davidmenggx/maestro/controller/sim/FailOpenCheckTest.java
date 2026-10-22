package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.davidmenggx.maestro.controller.core.NodeHealth;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The fail-open check against traces of what a controller sent, good and bad. */
class FailOpenCheckTest {

  private static final NodeId NODE = NodeId.of(0);

  private static final long RUN = 1;

  private static final long START = 10;

  private final FailOpenCheck check = new FailOpenCheck();

  private long nextRequest = 1;

  @Test
  void aControllerThatKeepsSendingToANodeThatCannotAnswerIsCaught() {
    check.unresponsive(NODE, RUN, START, true);
    for (long tick = START + 2; tick < START + 100; tick++) {
      send(tick);
    }
    check.unresponsive(NODE, RUN, START + 100, false);
    assertEquals(1, check.violations(START + 100).size());
    assertEquals(1, check.judged());
  }

  @Test
  void probesAfterEachDoubledBackoffAreAllowed() {
    check.unresponsive(NODE, RUN, START, true);
    send(START + 2);
    send(START + 3);
    long at = FailOpenCheck.noticed(List.of(START + 2, START + 3)) + 1;
    send(at);
    long backoff = NodeHealth.FIRST_BACKOFF_TICKS;
    for (int probe = 0; probe < 5; probe++) {
      backoff = Math.min(2 * backoff, NodeHealth.MAX_BACKOFF_TICKS);
      at += NodeHealth.PROBE_TICKS + 1 + backoff;
      send(at);
    }
    check.unresponsive(NODE, RUN, at + 1, false);
    assertEquals(List.of(), check.violations(at + 1));
    assertEquals(1, check.judged());
  }

  @Test
  void probesThatNeverBackOffFurtherAreCaught() {
    check.unresponsive(NODE, RUN, START, true);
    send(START + 2);
    send(START + 3);
    long at = FailOpenCheck.noticed(List.of(START + 2, START + 3)) + 1;
    for (int probe = 0; probe < 8; probe++) {
      send(at);
      at += NodeHealth.PROBE_TICKS + 1 + NodeHealth.FIRST_BACKOFF_TICKS;
    }
    assertEquals(1, check.violations(at).size());
  }

  @Test
  void requestsTooFarApartAreNeverJudged() {
    check.unresponsive(NODE, RUN, START, true);
    long tick = START + 2;
    for (int i = 0; i < 4; i++) {
      send(tick);
      tick += NodeHealth.QUIET_WINDOW_TICKS;
    }
    check.unresponsive(NODE, RUN, tick, false);
    assertEquals(List.of(), check.violations(tick));
    assertEquals(0, check.judged());
  }

  @Test
  void requestsSentOnALinkThatWentDownShowNothingOnTheNext() {
    check.unresponsive(NODE, RUN, START, true);
    send(START + 2);
    send(START + 3);
    // The link goes down before the controller could have noticed, and the requests go with it.
    check.linkLost(NODE, RUN, START + 5);
    long reconnected = START + 25;
    send(reconnected);
    send(reconnected + 1);
    long noticed = FailOpenCheck.noticed(List.of(reconnected, reconnected + 1));
    // Requests until it notices on the new link are allowed, and one after it is a probe.
    for (long tick = reconnected + 2; tick <= noticed + 1; tick++) {
      send(tick);
    }
    assertEquals(List.of(), check.violations(noticed + 2));
    send(noticed + 2);
    assertEquals(1, check.violations(noticed + 3).size());
  }

  @Test
  void aLinkLostAfterTheControllerNoticedChangesNothing() {
    check.unresponsive(NODE, RUN, START, true);
    send(START + 2);
    send(START + 3);
    long noticed = FailOpenCheck.noticed(List.of(START + 2, START + 3));
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
        FailOpenCheck.noticed(List.of(first, first + NodeHealth.QUIET_WINDOW_TICKS)));
    assertNotEquals(
        Long.MAX_VALUE,
        FailOpenCheck.noticed(List.of(first, first + NodeHealth.QUIET_WINDOW_TICKS - 1)));
    // A later pair still counts.
    assertEquals(
        first
            + 2 * NodeHealth.QUIET_WINDOW_TICKS
            + NodeHealth.QUIET_TICKS
            + FailOpenCheck.LATE_SEND_TICKS,
        FailOpenCheck.noticed(
            List.of(
                first,
                first + 2 * NodeHealth.QUIET_WINDOW_TICKS,
                first + 2 * NodeHealth.QUIET_WINDOW_TICKS + 1)));
  }

  /** Sends a new request at {@code tick} and returns its id. */
  private long send(long tick) {
    long id = nextRequest++;
    check.requestSent(NODE, RUN, id, tick);
    return id;
  }
}
