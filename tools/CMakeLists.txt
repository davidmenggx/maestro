# Builds *only* the FlatBuffers compiler (flatc), pinned to the same tag the
# node's runtime library uses. The top-level Makefile invokes the resulting
# binary to generate both the C++ and Java bindings, so schema generation has a
# single source of truth.
cmake_minimum_required(VERSION 3.24)
project(maestro_tools LANGUAGES CXX)

include(FetchContent)

set(FLATBUFFERS_TAG "v25.2.10" CACHE STRING "FlatBuffers git tag")

set(FLATBUFFERS_BUILD_FLATC   ON  CACHE BOOL "" FORCE)
set(FLATBUFFERS_BUILD_FLATLIB OFF CACHE BOOL "" FORCE)
set(FLATBUFFERS_BUILD_FLATHASH OFF CACHE BOOL "" FORCE)
set(FLATBUFFERS_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(FLATBUFFERS_INSTALL       OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
  flatbuffers
  GIT_REPOSITORY https://github.com/google/flatbuffers.git
  GIT_TAG        ${FLATBUFFERS_TAG}
  GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(flatbuffers)

# Emit flatc at a stable, predictable path: <build>/bin/flatc
if(TARGET flatc)
  set_target_properties(flatc PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
endif()
