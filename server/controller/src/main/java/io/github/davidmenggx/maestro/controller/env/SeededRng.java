package io.github.davidmenggx.maestro.controller.env;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * The only {@link Rng} implementation. Production seeds it once at startup and the simulator seeds
 * it per run; both then produce the same sequence for the same seed, which is what makes a failing
 * run replayable.
 */
public final class SeededRng implements Rng {

  // Pinned by name. The replay contract is "algorithm plus seed", so it must not
  // follow whatever the platform happens to default to.
  private static final String ALGORITHM = "L64X128MixRandom";

  private static final RandomGeneratorFactory<RandomGenerator> FACTORY =
      RandomGeneratorFactory.of(ALGORITHM);

  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  private final long seed;
  private final RandomGenerator delegate;

  private SeededRng(long seed) {
    this.seed = seed;
    this.delegate = FACTORY.create(seed);
  }

  public static SeededRng of(long seed) {
    return new SeededRng(seed);
  }

  @Override
  public Rng derive(String name) {
    return new SeededRng(mix(seed, name));
  }

  @Override
  public long nextLong() {
    return delegate.nextLong();
  }

  private static long mix(long seed, String name) {
    long hash = FNV_OFFSET ^ seed;
    for (int i = 0; i < name.length(); i++) {
      hash = (hash ^ name.charAt(i)) * FNV_PRIME;
    }
    return hash;
  }
}
