#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

// Length-prefixed message framing for the Maestro streams. Every frame on the
// wire is `[uint32 little-endian payload length][payload]`, where the payload
// is a FlatBuffers buffer.
namespace maestro::net {

inline constexpr std::size_t HeaderSize = 4;

/// Serializes `Payload` into a framed message (header followed by payload).
std::vector<std::uint8_t> frame(std::span<const std::uint8_t> Payload);

/// Reads the declared payload length from the front of `Buffer`, or 0 if fewer
/// than `HeaderSize` bytes are available yet.
std::uint32_t peekLength(std::span<const std::uint8_t> Buffer) noexcept;

} // namespace maestro::net
