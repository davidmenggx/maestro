package io.github.davidmenggx.maestro.obs;

import io.github.davidmenggx.maestro.controller.env.Events;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import java.time.Instant;

/**
 * Production {@link Events}: each event is one log record, its name also the record's body. Export
 * is best effort: a record the collector cannot take, as while it is down, is lost, so a replay of
 * the records may miss changes. The gauges the events mirror are exported in full every interval.
 */
public final class OtelEvents implements Events {

  private final Logger logger;

  public OtelEvents() {
    this(GlobalOpenTelemetry.get().getLogsBridge().get(Telemetry.SERVICE_NAME));
  }

  OtelEvents(Logger logger) {
    this.logger = logger;
  }

  @Override
  public void event(String name, String... attributes) {
    logger
        .logRecordBuilder()
        .setTimestamp(Instant.now())
        .setSeverity(Severity.INFO)
        .setEventName(name)
        .setBody(name)
        .setAllAttributes(OtelMetrics.attributes(attributes))
        .emit();
  }
}
