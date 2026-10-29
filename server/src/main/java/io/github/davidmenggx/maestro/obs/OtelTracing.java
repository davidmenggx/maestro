package io.github.davidmenggx.maestro.obs;

import io.github.davidmenggx.maestro.controller.env.RequestTrace;
import io.github.davidmenggx.maestro.controller.env.Tracing;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link Tracing}: one root span per request, from its ask to its outcome, with a child
 * span for each stage it reached: waiting to be handed to the encoder, from there until it left (in
 * batch mode, until the hand-over that sends it), at the node, and waiting for the hand-over that
 * takes its answer. Each resend is an event. Trace ids come from {@link RequestTraceIds}.
 */
public final class OtelTracing implements Tracing {

  private static final long NEVER = RequestTrace.NEVER;

  private final Tracer tracer;

  /** Added to a monotonic reading to make it epoch nanoseconds. */
  private final long epochOffsetNanos;

  private final String subMode;

  /** {@code subMode} is how requests are dispatched, as the config names it. */
  public OtelTracing(String subMode) {
    this(GlobalOpenTelemetry.getTracer(Telemetry.SERVICE_NAME), epochOffset(), subMode);
  }

  OtelTracing(Tracer tracer, long epochOffsetNanos, String subMode) {
    this.tracer = tracer;
    this.epochOffsetNanos = epochOffsetNanos;
    this.subMode = subMode;
  }

  private static long epochOffset() {
    Instant now = Instant.now();
    return now.getEpochSecond() * 1_000_000_000L + now.getNano() - System.nanoTime();
  }

  @Override
  public void finished(RequestTrace trace) {
    Span root =
        RequestTraceIds.with(
            RequestTraceIds.traceId(trace.run(), trace.requestId()),
            () ->
                tracer
                    .spanBuilder("path.request")
                    .setNoParent()
                    .setStartTimestamp(epoch(trace.askedNanos()), TimeUnit.NANOSECONDS)
                    .setAllAttributes(attributes(trace))
                    .startSpan());
    Context parent = Context.root().with(root);
    stage(parent, "dispatch.wait", trace.askedNanos(), trace.encodingNanos(), Attributes.empty());
    stage(parent, "send.wait", trace.encodingNanos(), trace.sentNanos(), Attributes.empty());
    RequestTrace.Answer answer = trace.answer();
    if (answer != null) {
      stage(
          parent,
          "node",
          trace.sentNanos(),
          answer.arrivedNanos(),
          Attributes.builder()
              .put("maestro.node.wait_micros", answer.waitMicros())
              .put("maestro.node.search_micros", answer.searchMicros())
              .put("maestro.world.sequence", answer.worldSequence())
              .build());
      stage(parent, "handover", answer.arrivedNanos(), trace.endedNanos(), Attributes.empty());
    }
    for (long resent : trace.resentNanos()) {
      root.addEvent("resent", epoch(resent), TimeUnit.NANOSECONDS);
    }
    root.end(epoch(trace.endedNanos()), TimeUnit.NANOSECONDS);
  }

  /** A child span from {@code from} to {@code to}, if the request reached both. */
  private void stage(Context parent, String name, long from, long to, Attributes attributes) {
    if (from == NEVER || to == NEVER) {
      return;
    }
    tracer
        .spanBuilder(name)
        .setParent(parent)
        .setStartTimestamp(epoch(from), TimeUnit.NANOSECONDS)
        .setAllAttributes(attributes)
        .startSpan()
        .end(epoch(to), TimeUnit.NANOSECONDS);
  }

  private Attributes attributes(RequestTrace trace) {
    return Attributes.builder()
        .put("maestro.run", Long.toHexString(trace.run()))
        .put("maestro.request.id", trace.requestId())
        .put("maestro.mob.id", trace.mobId())
        .put("maestro.mob.class", trace.classId())
        .put("maestro.mob.hunting", trace.hunting())
        .put("maestro.node", trace.node().toString())
        .put("maestro.region", trace.regionId())
        .put("maestro.sub_mode", subMode)
        .put("maestro.outcome", trace.outcome())
        .put("maestro.ticks", trace.endedTick() - trace.askedTick())
        .put("maestro.resends", trace.resentNanos().size())
        .build();
  }

  private long epoch(long nanos) {
    return nanos + epochOffsetNanos;
  }
}
