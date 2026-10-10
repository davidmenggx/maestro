package io.github.davidmenggx.maestro.controller.env;

import java.util.random.RandomGenerator;

/**
 * Randomness seam. Every random draw on the controller path comes from here, so a run replays from
 * its seed alone. {@link #derive} gives a component its own stream, so adding a draw in one
 * component does not change the values other components see.
 */
public interface Rng extends RandomGenerator {

  /** Independent stream, the same for a given name and parent seed. */
  Rng derive(String name);
}
