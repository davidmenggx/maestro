package io.github.davidmenggx.maestro.controller.world;

/** Push side of the world boundary. */
@FunctionalInterface
public interface WorldInput {

  void accept(WorldEvent event);
}
