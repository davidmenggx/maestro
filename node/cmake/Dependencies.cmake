include(FetchContent)

find_package(Threads REQUIRED)

set(FLATBUFFERS_TAG "v25.2.10"    CACHE STRING "FlatBuffers git tag")
set(ASIO_TAG        "asio-1-34-2" CACHE STRING "Asio git tag")
set(GOOGLETEST_TAG  "v1.16.0"     CACHE STRING "GoogleTest git tag")
set(OTEL_CPP_TAG    "v1.19.0"     CACHE STRING "opentelemetry-cpp git tag")

# ---- FlatBuffers (runtime library only, flatc is built under tools/) --------
set(FLATBUFFERS_BUILD_FLATC   OFF CACHE BOOL "" FORCE)
set(FLATBUFFERS_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(FLATBUFFERS_INSTALL       OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
  flatbuffers
  GIT_REPOSITORY https://github.com/google/flatbuffers.git
  GIT_TAG        ${FLATBUFFERS_TAG}
  GIT_SHALLOW    TRUE
)

# ---- Asio (standalone, header-only, no CMake project of its own) ------------
FetchContent_Declare(
  asio
  GIT_REPOSITORY https://github.com/chriskohlhoff/asio.git
  GIT_TAG        ${ASIO_TAG}
  GIT_SHALLOW    TRUE
)

# ---- GoogleTest -------------------------------------------------------------
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_Declare(
  googletest
  GIT_REPOSITORY https://github.com/google/googletest.git
  GIT_TAG        ${GOOGLETEST_TAG}
  GIT_SHALLOW    TRUE
)

FetchContent_MakeAvailable(flatbuffers asio googletest)

# Asio ships no CMakeLists, so expose a standalone interface target ourselves.
if(NOT TARGET asio::asio)
  add_library(asio_standalone INTERFACE)
  target_include_directories(asio_standalone INTERFACE ${asio_SOURCE_DIR}/asio/include)
  target_compile_definitions(asio_standalone INTERFACE ASIO_STANDALONE ASIO_NO_DEPRECATED)
  target_link_libraries(asio_standalone INTERFACE Threads::Threads)
  add_library(asio::asio ALIAS asio_standalone)
endif()

# FlatBuffers' namespaced alias is not always defined in a subdirectory build.
if(NOT TARGET flatbuffers::flatbuffers)
  add_library(flatbuffers::flatbuffers ALIAS flatbuffers)
endif()

# ---- OpenTelemetry C++ SDK --------------------------------------------------
# Exports over OTLP/gRPC using the system gRPC + protobuf (both have dev headers
# here, and the distro built gRPC against the same abseil, so they're ABI
# consistent). This avoids needing libcurl-dev, which the OTLP/HTTP exporter
# would require.
if(MAESTRO_WITH_OTEL)
  set(WITH_OTLP_GRPC   ON  CACHE BOOL "" FORCE)
  set(WITH_OTLP_HTTP   OFF CACHE BOOL "" FORCE)
  set(WITH_ABSEIL      OFF CACHE BOOL "" FORCE)
  set(WITH_EXAMPLES    OFF CACHE BOOL "" FORCE)
  set(WITH_FUNC_TESTS  OFF CACHE BOOL "" FORCE)
  set(WITH_BENCHMARK   OFF CACHE BOOL "" FORCE)
  set(BUILD_TESTING    OFF CACHE BOOL "" FORCE)
  set(OPENTELEMETRY_INSTALL OFF CACHE BOOL "" FORCE)
  # Submodules (opentelemetry-proto, nlohmann-json) are required for OTLP, so
  # this fetch is intentionally not shallow.
  FetchContent_Declare(
    opentelemetry-cpp
    GIT_REPOSITORY https://github.com/open-telemetry/opentelemetry-cpp.git
    GIT_TAG        ${OTEL_CPP_TAG}
  )
  FetchContent_MakeAvailable(opentelemetry-cpp)
endif()
