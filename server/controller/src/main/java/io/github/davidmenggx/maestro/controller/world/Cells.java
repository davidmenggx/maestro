package io.github.davidmenggx.maestro.controller.world;

/**
 * What one cell holds besides its position. Bounds shared by the boundary and the encoder, and the
 * packed form a {@link WorldSource} reads cells in: {@code (floor << 8) | pathType}.
 */
public final class Cells {

  /**
   * Tallest collision height, in sixteenths of a block, that a cell may carry. The node refuses a
   * frame with a taller one, because its collision check looks only one layer below a box. A fence
   * is 24, a full block 16.
   */
  public static final int MAX_FLOOR = 32;

  /** Collision height of a full cube. */
  public static final int FULL_FLOOR = 16;

  private Cells() {}

  /** A cell in its packed form. */
  public static int pack(int floor, byte pathType) {
    return (floor << 8) | (pathType & 0xFF);
  }

  public static int floorOf(int cell) {
    return cell >> 8;
  }

  public static byte typeOf(int cell) {
    return (byte) cell;
  }
}
