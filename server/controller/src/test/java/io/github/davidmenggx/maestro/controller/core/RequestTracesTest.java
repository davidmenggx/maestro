package io.github.davidmenggx.maestro.controller.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.Metrics;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Offload;
import io.github.davidmenggx.maestro.controller.env.RequestTrace;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.wire.Reply;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RequestTracesTest {

  private static final long RUN = 0x5eed;
  private static final NodeId NODE = NodeId.of(2);
  private static final long NEVER = RequestTrace.NEVER;

  private long tick;
  private long nanos;
  private final List<RequestTrace> reported = new ArrayList<>();
  private final RequestTraces traces = new RequestTraces(env(), 1);

  @Test
  void theSampleRateIsTheShareOfIdsTraced() {
    for (double rate : List.of(0.01, 0.25, 0.5)) {
      int sampled = 0;
      int ids = 100_000;
      for (long id = 1; id <= ids; id++) {
        sampled += RequestTraces.sampled(RUN, id, rate) ? 1 : 0;
      }
      assertEquals(rate, (double) sampled / ids, 0.01, "rate " + rate);
    }
  }

  @Test
  void aRateOfOneTracesEveryRequestAndZeroNone() {
    for (long id = 1; id <= 1000; id++) {
      assertTrue(RequestTraces.sampled(RUN, id, 1));
      assertFalse(RequestTraces.sampled(RUN, id, 0));
      assertFalse(RequestTraces.sampled(RUN, id, Double.NaN));
    }
  }

  @Test
  void anotherRunSamplesOtherIds() {
    int same = 0;
    for (long id = 1; id <= 1000; id++) {
      same +=
          RequestTraces.sampled(RUN, id, 0.5) == RequestTraces.sampled(RUN + 1, id, 0.5) ? 1 : 0;
    }
    assertTrue(same > 400 && same < 600, same + " of 1000 agree");
  }

  @Test
  void anAnsweredRequestReportsEveryStageAtTheNextFlush() {
    tick = 4;
    nanos = 100;
    traces.admitted(7, 30, (short) 3, NODE, 5, true, 4);
    nanos = 200;
    traces.encoding(7);
    // A resend is handed to the encoder again, which keeps the first time.
    nanos = 250;
    traces.sent(7, 300);
    traces.encoding(7);
    traces.sent(7, 400);
    tick = 6;
    nanos = 600;
    traces.answered(7, answer(7), 500);
    traces.ended(7, RequestTrace.ANSWERED);
    assertEquals(List.of(), reported);

    traces.flush();
    assertEquals(1, reported.size());
    RequestTrace trace = reported.get(0);
    assertEquals(RUN, trace.run());
    assertEquals(7, trace.requestId());
    assertEquals(30, trace.mobId());
    assertEquals(3, trace.classId());
    assertEquals(NODE, trace.node());
    assertEquals(5, trace.regionId());
    assertTrue(trace.hunting());
    assertEquals(4, trace.askedTick());
    assertEquals(6, trace.endedTick());
    assertEquals(100, trace.askedNanos());
    assertEquals(200, trace.encodingNanos());
    assertEquals(300, trace.sentNanos());
    assertEquals(List.of(400L), trace.resentNanos());
    assertEquals(new RequestTrace.Answer(500, 80, 40, 9), trace.answer());
    assertEquals(600, trace.endedNanos());
    assertEquals(RequestTrace.ANSWERED, trace.outcome());

    traces.flush();
    assertEquals(1, reported.size());
  }

  @Test
  void aSendHandedBackAfterTheAnswerStillCountsIfItLeftFirst() {
    traces.admitted(7, 30, (short) 3, NODE, 5, false, 0);
    nanos = 50;
    traces.encoding(7);
    nanos = 90;
    traces.answered(7, answer(7), 80);
    traces.ended(7, RequestTrace.ANSWERED);
    traces.sent(7, 60);
    traces.flush();
    assertEquals(60, reported.get(0).sentNanos());
  }

  @Test
  void anAnswerWithNoSendYetWaitsOneFlushForIt() {
    traces.admitted(7, 30, (short) 3, NODE, 5, false, 0);
    traces.admitted(8, 31, (short) 3, NODE, 5, false, 0);
    nanos = 50;
    traces.encoding(7);
    nanos = 90;
    traces.answered(7, answer(7), 80);
    traces.ended(7, RequestTrace.ANSWERED);
    traces.ended(8, "TIMEOUT");
    traces.flush();
    assertEquals(List.of(8L), reported.stream().map(RequestTrace::requestId).toList());

    // The send is handed over a hand-over after the answer.
    traces.sent(7, 60);
    traces.flush();
    assertEquals(List.of(8L, 7L), reported.stream().map(RequestTrace::requestId).toList());
    assertEquals(60, reported.get(1).sentNanos());
  }

  @Test
  void anAnswerWhoseSendNeverComesIsReportedAfterOneFlush() {
    traces.admitted(7, 30, (short) 3, NODE, 5, false, 0);
    traces.encoding(7);
    nanos = 90;
    traces.answered(7, answer(7), 80);
    traces.ended(7, RequestTrace.ANSWERED);
    traces.flush();
    assertEquals(List.of(), reported);
    traces.flush();
    assertEquals(1, reported.size());
    assertEquals(NEVER, reported.get(0).sentNanos());
  }

  @Test
  void onlyAnsweredRequestsNotYetReportedCountAsUnreported() {
    traces.admitted(7, 30, (short) 3, NODE, 5, false, 0);
    traces.admitted(8, 31, (short) 3, NODE, 5, false, 0);
    traces.answered(7, answer(7), 80);
    traces.ended(7, RequestTrace.ANSWERED);
    traces.ended(8, "TIMEOUT");
    assertEquals(1, traces.answeredUnreported());

    // The answer with no send yet is held back.
    traces.flush();
    assertEquals(1, traces.answeredUnreported());
    traces.flush();
    assertEquals(0, traces.answeredUnreported());
  }

  @Test
  void aSendAfterTheOutcomeIsIgnored() {
    traces.admitted(7, 30, (short) 3, NODE, 5, false, 0);
    traces.encoding(7);
    nanos = 90;
    traces.ended(7, "CANCELED");
    traces.sent(7, 91);
    traces.flush();
    RequestTrace trace = reported.get(0);
    assertEquals(NEVER, trace.sentNanos());
    assertNull(trace.answer());
    assertEquals("CANCELED", trace.outcome());
  }

  @Test
  void anOutcomeIsTakenOnceAndAnAnswerOnlyWhileTheMobWaits() {
    traces.admitted(7, 30, (short) 3, NODE, 5, false, 0);
    traces.ended(7, "MOB_GONE");
    traces.answered(7, answer(7), 80);
    traces.ended(7, RequestTrace.ANSWERED);
    traces.flush();
    assertEquals(1, reported.size());
    assertEquals("MOB_GONE", reported.get(0).outcome());
    assertNull(reported.get(0).answer());
  }

  @Test
  void requestsAreReportedInTheOrderTheyFinished() {
    traces.admitted(1, 30, (short) 3, NODE, 5, false, 0);
    traces.admitted(2, 31, (short) 3, NODE, 5, false, 0);
    traces.ended(2, "TIMEOUT");
    traces.ended(1, "TIMEOUT");
    traces.flush();
    assertEquals(List.of(2L, 1L), reported.stream().map(RequestTrace::requestId).toList());
  }

  @Test
  void aRequestNotSampledLeavesNoTrace() {
    RequestTraces none = new RequestTraces(env(), 0);
    none.admitted(7, 30, (short) 3, NODE, 5, false, 0);
    none.encoding(7);
    none.sent(7, 1);
    none.answered(7, answer(7), 2);
    none.ended(7, RequestTrace.ANSWERED);
    none.flush();
    assertEquals(List.of(), reported);
  }

  @Test
  void aTraceLeftOpenForARequestNoLongerLiveIsFlagged() {
    traces.admitted(7, 30, (short) 3, NODE, 5, false, 0);
    traces.admitted(8, 31, (short) 3, NODE, 5, false, 0);
    traces.ended(8, "TIMEOUT");
    List<String> found = new ArrayList<>();
    traces.check(set(7L), found);
    assertEquals(List.of(), found);
    traces.check(set(), found);
    assertEquals(List.of("request 7 is traced but no longer live"), found);
  }

  private static Set<Long> set(Long... ids) {
    return new LinkedHashSet<>(List.of(ids));
  }

  private static Reply.Answer answer(long requestId) {
    return new Reply.Answer(requestId, true, List.of(), 1f, 9, 80, 40);
  }

  private ControllerEnv env() {
    Clock clock =
        new Clock() {
          @Override
          public long tick() {
            return tick;
          }

          @Override
          public long nowNanos() {
            return nanos;
          }
        };
    Offload offload =
        new Offload() {
          @Override
          public <T> void submit(
              String name, Lane lane, Supplier<T> work, Consumer<T> completion, Runnable failure) {}
        };
    Transport transport =
        new Transport() {
          @Override
          public void send(NodeId node, Channel channel, ByteBuffer frame) {}

          @Override
          public void connect(NodeId node) {}

          @Override
          public void listen(Listener listener) {}
        };
    Metrics metrics =
        new Metrics() {
          @Override
          public void count(String name, long delta, String... labels) {}

          @Override
          public void record(String name, double value, String... labels) {}

          @Override
          public void gauge(String name, double value, String... labels) {}
        };
    return new ControllerEnv(
        clock, SeededRng.of(1), offload, transport, metrics, reported::add, RUN);
  }
}
