package com.smpler.maestro.controller.world;

/** Block position in world coordinates. */
public record Pos(int x, int y, int z) {

  public int chunkX() {
    return x >> 4;
  }

  public int chunkZ() {
    return z >> 4;
  }
}
