package io.github.davidmenggx.maestro.obs;

import io.github.davidmenggx.maestro.config.MaestroConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Telemetry {

  public static final String SERVICE_NAME = "maestro-server";

  /** Short, so a missing collector does not stall shutdown or an export cycle. */
  private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * Held so the JUL level set below is not garbage collected. Export failures log at {@code SEVERE}
   * every cycle when no collector runs (normal in development), so it is turned off.
   */
  private static final Logger EXPORTER_LOGGER = Logger.getLogger("io.opentelemetry.exporter");

  private static OpenTelemetrySdk sdk;

  private Telemetry() {}

  public static synchronized void init(MaestroConfig config, String version) {
    if (sdk != null) {
      return;
    }
    EXPORTER_LOGGER.setLevel(Level.OFF);

    Resource resource =
        Resource.getDefault()
            .merge(
                Resource.create(
                    Attributes.of(
                        AttributeKey.stringKey("service.name"), SERVICE_NAME,
                        AttributeKey.stringKey("service.version"), version)));

    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(
                PeriodicMetricReader.builder(
                        OtlpGrpcMetricExporter.builder()
                            .setEndpoint(config.otlpEndpoint())
                            .setTimeout(EXPORT_TIMEOUT)
                            .build())
                    .build())
            .build();

    sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider(resource, config.otlpEndpoint(), meterProvider))
            .setMeterProvider(meterProvider)
            .buildAndRegisterGlobal();
  }

  /**
   * Spans exported to {@code endpoint}. Request traces take their ids from {@link RequestTraceIds}.
   * Spans the full export queue drops show in the processor's own metrics.
   */
  static SdkTracerProvider tracerProvider(
      Resource resource, String endpoint, MeterProvider meterProvider) {
    return SdkTracerProvider.builder()
        .setResource(resource)
        .setIdGenerator(RequestTraceIds.INSTANCE)
        .addSpanProcessor(
            BatchSpanProcessor.builder(
                    OtlpGrpcSpanExporter.builder()
                        .setEndpoint(endpoint)
                        .setTimeout(EXPORT_TIMEOUT)
                        .build())
                .setMeterProvider(meterProvider)
                .build())
        .build();
  }

  public static synchronized void shutdown() {
    if (sdk == null) {
      return;
    }
    sdk.close();
    sdk = null;
  }
}
