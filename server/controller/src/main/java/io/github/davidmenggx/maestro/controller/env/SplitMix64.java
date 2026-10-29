package io.github.davidmenggx.maestro.controller.env;

/** SplitMix64's output function, for hashing ids where drawing from an {@link Rng} would not do. */
public final class SplitMix64 {

  /** The generator's increment. */
  private static final long GAMMA = 0x9e3779b97f4a7c15L;

  private SplitMix64() {}

  /** The finalizer: a bijection that spreads every input bit over the result. */
  public static long mix(long value) {
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
  }

  /** The {@code n}th output, counting from 1, of a generator seeded with {@code seed}. */
  public static long nth(long seed, long n) {
    return mix(seed + n * GAMMA);
  }
}
