package io.github.davidmenggx.maestro.obs;

import io.github.davidmenggx.maestro.controller.env.SplitMix64;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.sdk.trace.IdGenerator;
import java.util.function.Supplier;

/**
 * Trace ids for request traces: the run's id, then a hash of the request's, so the two name a trace
 * and a simulation seed names its traces. Other spans get random ids.
 */
public final class RequestTraceIds implements IdGenerator {

  public static final RequestTraceIds INSTANCE = new RequestTraceIds();

  /** The trace id the next root span started on this thread takes, if set. */
  private static final ThreadLocal<String> NEXT = new ThreadLocal<>();

  private RequestTraceIds() {}

  /** The low half is distinct per request, and random to a sampler reading trace id randomness. */
  public static String traceId(long run, long requestId) {
    return TraceId.fromLongs(run, SplitMix64.nth(run, requestId));
  }

  /** Runs {@code start}, where a root span started takes {@code traceId}. */
  static <T> T with(String traceId, Supplier<T> start) {
    NEXT.set(traceId);
    try {
      return start.get();
    } finally {
      NEXT.remove();
    }
  }

  @Override
  public String generateTraceId() {
    String next = NEXT.get();
    return next != null ? next : IdGenerator.random().generateTraceId();
  }

  @Override
  public String generateSpanId() {
    return IdGenerator.random().generateSpanId();
  }
}
