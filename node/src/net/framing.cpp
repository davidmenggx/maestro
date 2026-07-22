#include "maestro/net/framing.hpp"

namespace maestro::net {

std::vector<std::uint8_t> frame(std::span<const std::uint8_t> Payload) {
  const auto Len = static_cast<std::uint32_t>(Payload.size());
  std::vector<std::uint8_t> Out;
  Out.reserve(HeaderSize + Payload.size());

  // Little-endian length header, least-significant byte first. For byte `i`,
  // `Len >> (8 * i)` shifts that byte down into the low 8 bits and `& 0xFF`
  // masks off the higher bytes.
  Out.push_back(static_cast<std::uint8_t>(Len & 0xFFu));
  Out.push_back(static_cast<std::uint8_t>((Len >> 8) & 0xFFu));
  Out.push_back(static_cast<std::uint8_t>((Len >> 16) & 0xFFu));
  Out.push_back(static_cast<std::uint8_t>((Len >> 24) & 0xFFu));

  Out.insert(Out.end(), Payload.begin(), Payload.end());
  return Out;
}

std::uint32_t peekLength(std::span<const std::uint8_t> Buffer) noexcept {
  if (Buffer.size() < HeaderSize)
    return 0;

  // Reassemble the little-endian header: shift each byte back up into its lane
  // (`Buffer[0]` is least-significant) and OR them together.
  return static_cast<std::uint32_t>(Buffer[0]) |
         (static_cast<std::uint32_t>(Buffer[1]) << 8) |
         (static_cast<std::uint32_t>(Buffer[2]) << 16) |
         (static_cast<std::uint32_t>(Buffer[3]) << 24);
}

} // namespace maestro::net
