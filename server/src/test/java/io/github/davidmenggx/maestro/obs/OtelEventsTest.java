package io.github.davidmenggx.maestro.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OtelEventsTest {

  private final List<LogRecordData> records = new ArrayList<>();

  private final SdkLoggerProvider provider =
      SdkLoggerProvider.builder()
          .addLogRecordProcessor(SimpleLogRecordProcessor.create(new Collector()))
          .build();

  private final OtelEvents events = new OtelEvents(provider.get("test"));

  @AfterEach
  void close() {
    provider.close();
  }

  @Test
  void anEventIsOneRecordNamedByItsNameWithItsAttributes() {
    events.event("holder.added", "run", "ab", "tick", "7", "region", "1", "node", "node-0");

    assertEquals(1, records.size());
    LogRecordData record = records.get(0);
    assertEquals("holder.added", record.getEventName());
    assertEquals("holder.added", record.getBodyValue().asString());
    assertEquals(Severity.INFO, record.getSeverity());
    assertEquals(4, record.getAttributes().size());
    assertEquals("7", record.getAttributes().get(AttributeKey.stringKey("tick")));
    assertEquals("node-0", record.getAttributes().get(AttributeKey.stringKey("node")));
    assertTrue(record.getTimestampEpochNanos() > 0);
  }

  @Test
  void attributesMustComeInKeyAndValuePairs() {
    assertThrows(IllegalArgumentException.class, () -> events.event("node.up", "node"));
  }

  private final class Collector implements LogRecordExporter {

    @Override
    public CompletableResultCode export(Collection<LogRecordData> batch) {
      records.addAll(batch);
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
