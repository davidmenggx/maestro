#include "maestro/obs.hpp"

#if MAESTRO_WITH_OTEL

#include <memory>
#include <string>

#include "opentelemetry/exporters/otlp/otlp_grpc_exporter_factory.h"
#include "opentelemetry/exporters/otlp/otlp_grpc_exporter_options.h"
#include "opentelemetry/sdk/resource/resource.h"
#include "opentelemetry/sdk/trace/batch_span_processor_factory.h"
#include "opentelemetry/sdk/trace/batch_span_processor_options.h"
#include "opentelemetry/sdk/trace/tracer_provider_factory.h"
#include "opentelemetry/trace/noop.h"
#include "opentelemetry/trace/provider.h"

namespace {
namespace otlp = opentelemetry::exporter::otlp;
namespace trace_sdk = opentelemetry::sdk::trace;
namespace trace_api = opentelemetry::trace;
namespace resource = opentelemetry::sdk::resource;

std::shared_ptr<trace_api::TracerProvider> GlobalProvider;
} // namespace

namespace maestro::obs {

void init(std::string_view ServiceName, std::string_view OtlpEndpoint) {
  otlp::OtlpGrpcExporterOptions Opts;
  Opts.endpoint = std::string(OtlpEndpoint);
  auto Exporter = otlp::OtlpGrpcExporterFactory::Create(Opts);
  auto Processor = trace_sdk::BatchSpanProcessorFactory::Create(
      std::move(Exporter), trace_sdk::BatchSpanProcessorOptions{});

  auto ServiceResource = resource::Resource::Create({
      {"service.name", std::string(ServiceName)},
  });

  GlobalProvider = trace_sdk::TracerProviderFactory::Create(
      std::move(Processor), ServiceResource);
  trace_api::Provider::SetTracerProvider(GlobalProvider);
}

void shutdown() {
  // Releasing the last reference flushes the old provider.
  trace_api::Provider::SetTracerProvider(
      std::shared_ptr<trace_api::TracerProvider>(
          std::make_shared<trace_api::NoopTracerProvider>()));
  GlobalProvider.reset();
}

} // namespace maestro::obs

#else // MAESTRO_WITH_OTEL

namespace maestro::obs {
void init(std::string_view, std::string_view) {}
void shutdown() {}
} // namespace maestro::obs

#endif // MAESTRO_WITH_OTEL
