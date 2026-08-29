package io.github.davidmenggx.maestro.controller.env;

/**
 * Identity of one compute node. A value type, so maps keyed on it iterate the same way every run.
 */
public record NodeId(int id) {

  public NodeId {
    if (id < 0) {
      throw new IllegalArgumentException("node id must not be negative: " + id);
    }
  }

  public static NodeId of(int id) {
    return new NodeId(id);
  }

  @Override
  public String toString() {
    return "node-" + id;
  }
}
