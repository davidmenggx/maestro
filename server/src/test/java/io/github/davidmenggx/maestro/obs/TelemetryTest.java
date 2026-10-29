package io.github.davidmenggx.maestro.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

class TelemetryTest {

  /** Request traces must take their ids from the run and request, so a seed names its traces. */
  @Test
  void aRequestTraceTakesItsIdFromItsRunAndRequestAndIsSampled() {
    // No span ends, so nothing is exported, and nothing listens on port 1 anyway.
    try (SdkTracerProvider provider =
        Telemetry.tracerProvider(Resource.empty(), "http://127.0.0.1:1", MeterProvider.noop())) {
      Tracer tracer = provider.get(Telemetry.SERVICE_NAME);
      String id = RequestTraceIds.traceId(5, 9);
      Span traced =
          RequestTraceIds.with(id, () -> tracer.spanBuilder("traced").setNoParent().startSpan());
      Span other = tracer.spanBuilder("other").setNoParent().startSpan();

      assertEquals(id, traced.getSpanContext().getTraceId());
      assertTrue(traced.getSpanContext().isSampled());
      assertNotEquals(id, other.getSpanContext().getTraceId());
    }
  }
}
