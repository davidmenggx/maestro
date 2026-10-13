#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace maestro::net {

/// Every frame on a stream is `[uint32 little-endian payload length][payload]`,
/// where the payload is a FlatBuffers buffer.
inline constexpr std::size_t HeaderSize = 4;

/// Largest allowed payload. Must match the controller's
/// `Frames.MAX_FRAME_BYTES`. A chunk's state with every block in its own run is
/// about 400 KiB at the overworld's 384 blocks and about 4 MiB at the tallest
/// world Minecraft allows. The controller splits a tick's block changes into
/// deltas that stay far below it.
inline constexpr std::uint32_t MaxPayloadSize = 8u << 20;

/// What is at the front of a receive buffer.
enum class HeaderStatus {
  /// Fewer than `HeaderSize` bytes have arrived.
  Incomplete,
  /// `Length` bytes of payload follow the header.
  Ready,
  /// The declared length is over `MaxPayloadSize`. Drop the connection
  /// instead of allocating.
  TooLarge,
};

struct Header {
  HeaderStatus Status = HeaderStatus::Incomplete;
  /// The declared payload length, unless `Incomplete`.
  std::uint32_t Length = 0;
};

/// Frames `Payload`, which must be at most `MaxPayloadSize` bytes: the header,
/// then the payload.
std::vector<std::uint8_t> frame(std::span<const std::uint8_t> Payload);

/// Reads the header at the front of `Buffer`.
Header peekHeader(std::span<const std::uint8_t> Buffer) noexcept;

} // namespace maestro::net
