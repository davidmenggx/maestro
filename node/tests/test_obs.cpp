#include "maestro/obs.hpp"

#include <gtest/gtest.h>

#if MAESTRO_WITH_OTEL
#include "opentelemetry/trace/provider.h"
#endif

TEST(Obs, ASpanStartedAfterShutdownIsDropped) {
  maestro::obs::init("maestro-test", "localhost:1");
  maestro::obs::shutdown();
#if MAESTRO_WITH_OTEL
  const auto Tracer =
      opentelemetry::trace::Provider::GetTracerProvider()->GetTracer("test");
  const auto Span = Tracer->StartSpan("late");
  EXPECT_FALSE(Span->GetContext().IsValid());
  Span->End();
#endif
}
