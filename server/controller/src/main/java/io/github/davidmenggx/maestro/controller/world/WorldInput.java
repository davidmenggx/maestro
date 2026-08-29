package io.github.davidmenggx.maestro.controller.world;

/** Push side of the world boundary: what the mod, or a recorded trace, feeds the controller. */
@FunctionalInterface
public interface WorldInput {

  void accept(WorldEvent event);
}
