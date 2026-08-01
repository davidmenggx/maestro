package com.smpler.maestro.obs;

import com.smpler.maestro.config.MaestroConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
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

public final class Telemetry {

  public static final String SERVICE_NAME = "maestro-server";

  /** Kept short so a missing collector never stalls a shutdown or an export cycle. */
  private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * Held so the JUL level set below is not lost to garbage collection. OpenTelemetry logs export
   * failures through JUL, which would otherwise repeat on every cycle whenever no collector is
   * listening -- the normal case in development.
   */
  private static final java.util.logging.Logger EXPORTER_LOGGER =
      java.util.logging.Logger.getLogger("io.opentelemetry.exporter");

  private static OpenTelemetrySdk sdk;

  private Telemetry() {}

  public static synchronized void init(MaestroConfig config, String version) {
    if (sdk != null) {
      return;
    }
    EXPORTER_LOGGER.setLevel(Level.SEVERE);

    Resource resource =
        Resource.getDefault()
            .merge(
                Resource.create(
                    Attributes.of(
                        AttributeKey.stringKey("service.name"), SERVICE_NAME,
                        AttributeKey.stringKey("service.version"), version)));

    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(
                BatchSpanProcessor.builder(
                        OtlpGrpcSpanExporter.builder()
                            .setEndpoint(config.otlpEndpoint())
                            .setTimeout(EXPORT_TIMEOUT)
                            .build())
                    .build())
            .build();

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
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .buildAndRegisterGlobal();
  }

  public static synchronized void shutdown() {
    if (sdk == null) {
      return;
    }
    sdk.close();
    sdk = null;
  }
}
