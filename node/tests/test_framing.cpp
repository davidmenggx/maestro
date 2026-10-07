#include "maestro/net/framing.hpp"

#include <cstdint>
#include <vector>

#include <gtest/gtest.h>

using maestro::net::frame;
using maestro::net::HeaderSize;
using maestro::net::HeaderStatus;
using maestro::net::MaxPayloadSize;
using maestro::net::peekHeader;

namespace {

/// A bare header declaring `Length`.
std::vector<std::uint8_t> header(std::uint32_t Length) {
  return {static_cast<std::uint8_t>(Length),
          static_cast<std::uint8_t>(Length >> 8),
          static_cast<std::uint8_t>(Length >> 16),
          static_cast<std::uint8_t>(Length >> 24)};
}

} // namespace

TEST(Framing, RoundTripsLengthHeader) {
  const std::vector<std::uint8_t> Payload{0xDE, 0xAD, 0xBE, 0xEF, 0x01};
  const auto Framed = frame(Payload);

  ASSERT_EQ(Framed.size(), HeaderSize + Payload.size());
  const auto Peeked = peekHeader(Framed);
  EXPECT_EQ(Peeked.Status, HeaderStatus::Ready);
  EXPECT_EQ(Peeked.Length, Payload.size());

  const std::vector<std::uint8_t> Body(Framed.begin() + HeaderSize,
                                       Framed.end());
  EXPECT_EQ(Body, Payload);
}

TEST(Framing, EmptyPayloadIsAZeroLengthFrame) {
  const auto Framed = frame({});
  ASSERT_EQ(Framed.size(), HeaderSize);
  EXPECT_EQ(peekHeader(Framed).Status, HeaderStatus::Ready);
  EXPECT_EQ(peekHeader(Framed).Length, 0u);
}

TEST(Framing, PeekHasNoAnswerUntilTheHeaderIsComplete) {
  const std::vector<std::uint8_t> Partial{0x01, 0x02};
  EXPECT_EQ(peekHeader(Partial).Status, HeaderStatus::Incomplete);
}

TEST(Framing, EncodesLengthLittleEndian) {
  const std::vector<std::uint8_t> Payload(0x0102, 0xAB);
  const auto Framed = frame(Payload);
  EXPECT_EQ(Framed[0], 0x02);
  EXPECT_EQ(Framed[1], 0x01);
  EXPECT_EQ(Framed[2], 0x00);
  EXPECT_EQ(Framed[3], 0x00);
  EXPECT_EQ(peekHeader(Framed).Length, Payload.size());
}

TEST(Framing, PeekReadsOnlyTheHeader) {
  const std::vector<std::uint8_t> Payload{0xFF, 0xFF, 0xFF, 0xFF};
  EXPECT_EQ(peekHeader(frame(Payload)).Length, Payload.size());
}

TEST(Framing, ALengthAtTheCapIsAccepted) {
  const auto Peeked = peekHeader(header(MaxPayloadSize));
  EXPECT_EQ(Peeked.Status, HeaderStatus::Ready);
  EXPECT_EQ(Peeked.Length, MaxPayloadSize);
}

TEST(Framing, ALengthPastTheCapIsRefused) {
  EXPECT_EQ(peekHeader(header(MaxPayloadSize + 1)).Status,
            HeaderStatus::TooLarge);
  // The largest length a header can declare, which is what a stream that
  // lost its framing tends to read.
  const auto Peeked = peekHeader(header(UINT32_MAX));
  EXPECT_EQ(Peeked.Status, HeaderStatus::TooLarge);
  EXPECT_EQ(Peeked.Length, UINT32_MAX);
}
