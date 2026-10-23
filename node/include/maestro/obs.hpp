#pragma once

#include <string_view>

namespace maestro::obs {

/// Installs the process-wide tracer provider, exporting over OTLP/gRPC to
/// `OtlpEndpoint`. Without `MAESTRO_WITH_OTEL`, this and `shutdown` do nothing.
void init(std::string_view ServiceName, std::string_view OtlpEndpoint);

/// Flushes and shuts down the tracer provider. Spans started afterwards are
/// dropped.
void shutdown();

} // namespace maestro::obs
