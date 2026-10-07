#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace maestro::net {

/// Every frame on a stream is `[uint32 little-endian payload length][payload]`,
/// where the payload is a FlatBuffers buffer.
inline constexpr std::size_t HeaderSize = 4;

/// Largest payload a frame may declare. The biggest frame is one chunk's
/// state: even with every block a run of its own, that is about 400 KiB at the
/// overworld's 384 blocks and about 4 MiB at the tallest world Minecraft
/// allows.
inline constexpr std::uint32_t MaxPayloadSize = 8u << 20;

/// What the front of a receive buffer holds.
enum class HeaderStatus {
  /// Fewer than `HeaderSize` bytes have arrived.
  Incomplete,
  /// `Length` bytes of payload follow the header.
  Ready,
  /// The declared length is over `MaxPayloadSize`, so the stream cannot be
  /// trusted and the connection should be dropped rather than allocated for.
  TooLarge,
};

struct Header {
  HeaderStatus Status = HeaderStatus::Incomplete;
  /// The declared payload length, unless `Incomplete`.
  std::uint32_t Length = 0;
};

/// Serializes `Payload`, which must be at most `MaxPayloadSize` bytes, into a
/// framed message (header followed by payload).
std::vector<std::uint8_t> frame(std::span<const std::uint8_t> Payload);

/// Reads the header at the front of `Buffer`.
Header peekHeader(std::span<const std::uint8_t> Buffer) noexcept;

} // namespace maestro::net
