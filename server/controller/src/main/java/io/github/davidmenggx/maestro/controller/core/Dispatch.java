package io.github.davidmenggx.maestro.controller.core;

/** When an admitted request leaves for its node. */
public enum Dispatch {
  /** With the tick's other requests, when the tick ends. */
  BATCH,
  /** On its own, as soon as it is encoded. */
  STREAM
}
