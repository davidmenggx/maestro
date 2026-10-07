#pragma once

#include <string_view>

namespace maestro::obs {

/// Installs the process-wide tracer provider, exporting over OTLP/gRPC to
/// `OtlpEndpoint` (e.g. localhost:4317). Spans are exported in batches off the
/// calling thread, so ending one never waits on the network. A build without
/// `MAESTRO_WITH_OTEL` makes this and `shutdown` no-ops.
void init(std::string_view ServiceName, std::string_view OtlpEndpoint);

/// Flushes and tears down the tracer provider.
void shutdown();

} // namespace maestro::obs
