#include "maestro/net/framing.hpp"

#include <cstdint>
#include <vector>

#include <gtest/gtest.h>

using maestro::net::frame;
using maestro::net::HeaderSize;
using maestro::net::peekLength;

TEST(Framing, RoundTripsLengthHeader) {
  const std::vector<std::uint8_t> Payload{0xDE, 0xAD, 0xBE, 0xEF, 0x01};
  const auto Framed = frame(Payload);

  ASSERT_EQ(Framed.size(), HeaderSize + Payload.size());
  EXPECT_EQ(peekLength(Framed), Payload.size());

  const std::vector<std::uint8_t> Body(Framed.begin() + HeaderSize,
                                       Framed.end());
  EXPECT_EQ(Body, Payload);
}

TEST(Framing, EmptyPayloadIsAZeroLengthFrame) {
  const auto Framed = frame({});
  ASSERT_EQ(Framed.size(), HeaderSize);
  EXPECT_EQ(peekLength(Framed), 0u);
}

TEST(Framing, PeekHasNoAnswerUntilTheHeaderIsComplete) {
  const std::vector<std::uint8_t> Partial{0x01, 0x02};
  EXPECT_FALSE(peekLength(Partial).has_value());
}

TEST(Framing, EncodesLengthLittleEndian) {
  const std::vector<std::uint8_t> Payload(0x0102, 0xAB);
  const auto Framed = frame(Payload);
  EXPECT_EQ(Framed[0], 0x02);
  EXPECT_EQ(Framed[1], 0x01);
  EXPECT_EQ(Framed[2], 0x00);
  EXPECT_EQ(Framed[3], 0x00);
  EXPECT_EQ(peekLength(Framed), Payload.size());
}
