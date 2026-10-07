#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace maestro::search {

/// Open-addressed map from a block position to a small value. Clearing keeps
/// the slots, so a map reused across searches stops allocating once it has
/// grown to the largest search. Coordinates must lie in [-2^20, 2^20).
template <typename V> class CoordMap {
public:
  void clear() {
    for (Slot &S : Slots)
      S.Key = EmptyKey;
    Size = 0;
  }

  /// The value at the position, or null if there is none.
  const V *find(int X, int Y, int Z) const {
    if (Slots.empty())
      return nullptr;
    const Slot &S = slotFor(pack(X, Y, Z));
    return S.Key == EmptyKey ? nullptr : &S.Value;
  }

  /// The value at the position, inserting `Fallback` first if there is none.
  /// The pointer is valid until the next insertion. The flag says whether
  /// this call inserted.
  std::pair<V *, bool> tryEmplace(int X, int Y, int Z, V Fallback) {
    if ((Size + 1) * 2 > Slots.size())
      grow();
    const std::uint64_t Key = pack(X, Y, Z);
    Slot &S = slotFor(Key);
    if (S.Key == Key)
      return {&S.Value, false};
    S.Key = Key;
    S.Value = Fallback;
    ++Size;
    return {&S.Value, true};
  }

private:
  /// Packing never sets the top bit, so no position packs to this.
  static constexpr std::uint64_t EmptyKey = ~std::uint64_t{0};
  static constexpr std::size_t MinSlots = 64;

  struct Slot {
    std::uint64_t Key = EmptyKey;
    V Value{};
  };

  static std::uint64_t pack(int X, int Y, int Z) {
    constexpr std::uint64_t Mask = (std::uint64_t{1} << 21) - 1;
    const auto Field = [](int Value) {
      return static_cast<std::uint64_t>(static_cast<std::uint32_t>(Value)) &
             Mask;
    };
    return Field(X) << 42 | Field(Y) << 21 | Field(Z);
  }

  /// The splitmix64 finalizer, so neighbouring positions scatter.
  static std::uint64_t mix(std::uint64_t Z) {
    Z ^= Z >> 30;
    Z *= 0xbf58476d1ce4e5b9ULL;
    Z ^= Z >> 27;
    Z *= 0x94d049bb133111ebULL;
    return Z ^ (Z >> 31);
  }

  /// The slot holding `Key`, or the empty slot where it would go.
  Slot &slotFor(std::uint64_t Key) {
    const std::size_t Mask = Slots.size() - 1;
    for (std::size_t I = mix(Key) & Mask;; I = (I + 1) & Mask)
      if (Slots[I].Key == Key || Slots[I].Key == EmptyKey)
        return Slots[I];
  }

  const Slot &slotFor(std::uint64_t Key) const {
    return const_cast<CoordMap *>(this)->slotFor(Key);
  }

  void grow() {
    std::vector<Slot> Old = std::move(Slots);
    Slots.assign(std::max(MinSlots, Old.size() * 2), Slot{});
    for (const Slot &S : Old)
      if (S.Key != EmptyKey)
        slotFor(S.Key) = S;
  }

  std::vector<Slot> Slots;
  std::size_t Size = 0;
};

} // namespace maestro::search
