package com.smpler.maestro.controller.env;

import java.util.random.RandomGenerator;

/**
 * Randomness seam. Every draw on the controller path comes from here, so a run replays from its
 * seed alone. {@link #derive} hands a component its own stream: adding a draw in one component then
 * does not shift the values every other component sees.
 */
public interface Rng extends RandomGenerator {

  /** Independent stream, stable for a given name and parent seed. */
  Rng derive(String name);
}
