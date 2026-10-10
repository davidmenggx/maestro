package io.github.davidmenggx.maestro.controller.env;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * The only {@link Rng} implementation. Production seeds it once at startup and the simulator seeds
 * it per run. The same seed always gives the same sequence, so a failing run can be replayed.
 */
public final class SeededRng implements Rng {

  // Named explicitly. Replay needs the same algorithm and seed, so it must not
  // depend on the platform default.
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
