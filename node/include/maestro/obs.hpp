#pragma once

#include <string_view>

// Thin observability facade over the OpenTelemetry C++ SDK. Built with
// `MAESTRO_WITH_OTEL`, `init` wires up an OTLP-over-gRPC trace pipeline;
// without it every function is a no-op.
namespace maestro::obs {

/// Installs the process-wide tracer provider. `OtlpEndpoint` is the collector's
/// OTLP/gRPC endpoint (e.g. localhost:4317).
void init(std::string_view ServiceName, std::string_view OtlpEndpoint);

/// Flushes and tears down the tracer provider.
void shutdown();

} // namespace maestro::obs
