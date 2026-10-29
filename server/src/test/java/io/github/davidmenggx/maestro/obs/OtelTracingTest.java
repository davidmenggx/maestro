package io.github.davidmenggx.maestro.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.RequestTrace;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OtelTracingTest {

  private static final long RUN = 0xabcdef;
  private static final long OFFSET = 1_000_000_000L;
  private static final long NEVER = RequestTrace.NEVER;

  private final List<SpanData> spans = new ArrayList<>();

  private final SdkTracerProvider provider =
      SdkTracerProvider.builder()
          .setIdGenerator(RequestTraceIds.INSTANCE)
          .addSpanProcessor(SimpleSpanProcessor.create(new Collector()))
          .build();

  private final OtelTracing tracing = new OtelTracing(provider.get("test"), OFFSET, "stream");

  @AfterEach
  void close() {
    provider.close();
  }

  @Test
  void anAnsweredRequestIsARootWithAChildPerStage() {
    tracing.finished(
        trace(
            100,
            200,
            300,
            List.of(350L),
            new RequestTrace.Answer(400, 80, 40, 9),
            500,
            RequestTrace.ANSWERED));

    Map<String, SpanData> byName =
        spans.stream().collect(Collectors.toMap(SpanData::getName, Function.identity()));
    assertEquals(
        List.of("dispatch.wait", "handover", "node", "path.request", "send.wait"),
        byName.keySet().stream().sorted().toList());
    SpanData root = byName.get("path.request");
    assertEquals(RequestTraceIds.traceId(RUN, 7), root.getTraceId());
    assertFalse(root.getParentSpanContext().isValid());
    assertEquals(OFFSET + 100, root.getStartEpochNanos());
    assertEquals(OFFSET + 500, root.getEndEpochNanos());
    assertEquals("node-2", root.getAttributes().get(AttributeKey.stringKey("maestro.node")));
    assertEquals("stream", root.getAttributes().get(AttributeKey.stringKey("maestro.sub_mode")));
    assertEquals(
        RequestTrace.ANSWERED, root.getAttributes().get(AttributeKey.stringKey("maestro.outcome")));
    assertEquals(3L, root.getAttributes().get(AttributeKey.longKey("maestro.ticks")));
    assertEquals(1L, root.getAttributes().get(AttributeKey.longKey("maestro.resends")));
    assertEquals(1, root.getEvents().size());
    assertEquals("resent", root.getEvents().get(0).getName());
    assertEquals(OFFSET + 350, root.getEvents().get(0).getEpochNanos());

    for (String stage : List.of("dispatch.wait", "send.wait", "node", "handover")) {
      SpanData span = byName.get(stage);
      assertEquals(root.getTraceId(), span.getTraceId(), stage);
      assertEquals(root.getSpanId(), span.getParentSpanId(), stage);
    }
    assertStage(byName.get("dispatch.wait"), 100, 200);
    assertStage(byName.get("send.wait"), 200, 300);
    assertStage(byName.get("node"), 300, 400);
    assertStage(byName.get("handover"), 400, 500);
    SpanData node = byName.get("node");
    assertEquals(80L, node.getAttributes().get(AttributeKey.longKey("maestro.node.wait_micros")));
    assertEquals(40L, node.getAttributes().get(AttributeKey.longKey("maestro.node.search_micros")));
    assertEquals(9L, node.getAttributes().get(AttributeKey.longKey("maestro.world.sequence")));
  }

  @Test
  void aStageNotReachedHasNoSpan() {
    tracing.finished(trace(100, 200, NEVER, List.of(), null, 250, "CANCELED"));

    assertEquals(
        List.of("dispatch.wait", "path.request"),
        spans.stream().map(SpanData::getName).sorted().toList());
  }

  /** A send never handed over leaves no span at the node, but the answer's hand-over has one. */
  @Test
  void anAnswerWithNoSendHasNoNodeSpan() {
    tracing.finished(
        trace(
            100,
            200,
            NEVER,
            List.of(),
            new RequestTrace.Answer(400, 80, 40, 9),
            500,
            RequestTrace.ANSWERED));

    assertEquals(
        List.of("dispatch.wait", "handover", "path.request"),
        spans.stream().map(SpanData::getName).sorted().toList());
  }

  @Test
  void otherSpansTakeRandomTraceIds() {
    tracing.finished(trace(100, NEVER, NEVER, List.of(), null, 150, "SUPERSEDED"));
    provider.get("test").spanBuilder("other").startSpan().end();
    assertEquals(2, spans.size());
    assertEquals(RequestTraceIds.traceId(RUN, 7), spans.get(0).getTraceId());
    assertNotEquals(RequestTraceIds.traceId(RUN, 7), spans.get(1).getTraceId());
  }

  @Test
  void aTraceIdIsTheRunThenAHashOfTheRequest() {
    String id = RequestTraceIds.traceId(RUN, 7);
    // Fixed, so a seed names the same traces in every version.
    assertEquals("0000000000abcdef587c5437268b75de", id);

    // Sequential ids give distinct low halves, spread over the whole range.
    int requests = 10_000;
    Set<String> lows = new HashSet<>();
    int upperHalf = 0;
    for (long requestId = 1; requestId <= requests; requestId++) {
      String low = RequestTraceIds.traceId(RUN, requestId).substring(16);
      lows.add(low);
      upperHalf += Character.digit(low.charAt(0), 16) >= 8 ? 1 : 0;
    }
    assertEquals(requests, lows.size());
    assertTrue(upperHalf > 4_500 && upperHalf < 5_500, upperHalf + " of " + requests);
  }

  private void assertStage(SpanData span, long from, long to) {
    assertEquals(OFFSET + from, span.getStartEpochNanos(), span.getName());
    assertEquals(OFFSET + to, span.getEndEpochNanos(), span.getName());
  }

  private static RequestTrace trace(
      long asked,
      long encoding,
      long sent,
      List<Long> resent,
      RequestTrace.Answer answer,
      long ended,
      String outcome) {
    return new RequestTrace(
        RUN,
        7,
        30,
        (short) 3,
        NodeId.of(2),
        5,
        true,
        4,
        7,
        asked,
        encoding,
        sent,
        resent,
        answer,
        ended,
        outcome);
  }

  private final class Collector implements SpanExporter {

    @Override
    public CompletableResultCode export(Collection<SpanData> exported) {
      spans.addAll(exported);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
