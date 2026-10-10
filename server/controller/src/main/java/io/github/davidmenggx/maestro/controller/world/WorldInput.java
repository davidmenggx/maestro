package io.github.davidmenggx.maestro.controller.world;

/** Push side of the world boundary: events the mod, or a recorded trace, sends to the controller. */
@FunctionalInterface
public interface WorldInput {

  void accept(WorldEvent event);
}
