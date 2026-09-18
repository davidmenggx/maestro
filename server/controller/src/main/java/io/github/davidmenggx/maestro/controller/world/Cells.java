package io.github.davidmenggx.maestro.controller.world;

/** What one cell carries besides its position: bounds shared by the boundary and the encoder. */
public final class Cells {

  /** Collision heights are sixteenths of a block in one byte; a fence is 24, a full block 16. */
  public static final int MAX_FLOOR = 255;

  /** Collision height of a full cube. */
  public static final int FULL_FLOOR = 16;

  private Cells() {}
}
