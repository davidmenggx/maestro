package io.github.davidmenggx.maestro.controller.env;

/**
 * Discrete changes worth a record each, such as a region moving between nodes. {@code attributes}
 * are alternating key and value strings, in a fixed order per name.
 */
public interface Events {

  void event(String name, String... attributes);
}
