#pragma once

#include <cstdint>
#include <vector>

#include <flatbuffers/flatbuffers.h>

#include "control_generated.h"
#include "maestro/serve/node_core.hpp"

namespace maestro::test {

using Bytes = std::vector<std::uint8_t>;

/// The link and area the frames below name unless told otherwise. The flat
/// test world of the server and node tests is assigned with these.
inline constexpr std::uint64_t TestRun = 9;
inline constexpr std::uint32_t TestRegion = 2;
inline constexpr std::uint16_t TestClass = 3;

/// The `Join` a controller sends first on a connection to `On`.
Bytes joinFrame(serve::Port On, std::uint64_t Run, std::uint64_t Link);

/// A `MobClass` with the default profile.
Bytes mobClassFrame(std::uint16_t ClassId = TestClass);

/// A batch of requests across the flat test world's floor.
Bytes requestFrame(const std::vector<std::uint64_t> &Ids,
                   std::uint16_t ClassId = TestClass,
                   std::uint64_t Run = TestRun,
                   std::uint32_t RegionId = TestRegion);

/// `Wire` again under `Id`, in a frame naming `Run` and `RegionId`.
Bytes requestFrame(const fbs::PathRequest &Wire, std::uint64_t Id,
                   std::uint64_t Run, std::uint32_t RegionId);

Bytes cancelFrame(const std::vector<std::uint64_t> &Ids,
                  std::uint64_t Run = TestRun,
                  std::uint32_t RegionId = TestRegion);

/// `Frame` as a `T`, or null if it does not verify as one.
template <typename T> const T *root(const Bytes &Frame) {
  flatbuffers::Verifier Verifier(Frame.data(), Frame.size());
  if (!Verifier.VerifyBuffer<T>(nullptr))
    return nullptr;
  return flatbuffers::GetRoot<T>(Frame.data());
}

} // namespace maestro::test
