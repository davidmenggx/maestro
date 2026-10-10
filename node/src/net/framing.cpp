#include "maestro/net/framing.hpp"

#include <cassert>

namespace maestro::net {

std::vector<std::uint8_t> frame(std::span<const std::uint8_t> Payload) {
  assert(Payload.size() <= MaxPayloadSize && "payload over the frame cap");
  const auto Len = static_cast<std::uint32_t>(Payload.size());
  std::vector<std::uint8_t> Out;
  Out.reserve(HeaderSize + Payload.size());

  for (std::size_t I = 0; I < HeaderSize; ++I)
    Out.push_back(static_cast<std::uint8_t>((Len >> (8 * I)) & 0xFFu));

  Out.insert(Out.end(), Payload.begin(), Payload.end());
  return Out;
}

Header peekHeader(std::span<const std::uint8_t> Buffer) noexcept {
  if (Buffer.size() < HeaderSize)
    return {};

  std::uint32_t Len = 0;
  for (std::size_t I = 0; I < HeaderSize; ++I)
    Len |= static_cast<std::uint32_t>(Buffer[I]) << (8 * I);
  return {Len > MaxPayloadSize ? HeaderStatus::TooLarge : HeaderStatus::Ready,
          Len};
}

} // namespace maestro::net
