package io.github.davidmenggx.maestro.controller.env;

import java.util.random.RandomGenerator;

/**
 * Source of all controller randomness. {@link #derive} gives a component its own stream, so a new
 * draw in one component does not shift another's.
 */
public interface Rng extends RandomGenerator {

  /** Independent stream, the same for a given name and parent seed. */
  Rng derive(String name);
}
