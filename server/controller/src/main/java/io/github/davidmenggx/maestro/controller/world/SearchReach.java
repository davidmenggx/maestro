package io.github.davidmenggx.maestro.controller.world;

/**
 * How far from its start a search reads the world. A node reads everything outside its area as air,
 * which turns real ground into a cliff and gives a wrong path instead of none. A request is
 * therefore sent only to a node whose area contains every block its search can read. Height is not
 * checked, because areas are full height.
 */
public final class SearchReach {

  private SearchReach() {}

  /**
   * Blocks a search may read past its start along either horizontal axis. A node is expanded only
   * while it is nearer the start than {@code maxPathLength}. Its neighbours are one block further.
   * The footprint test reads {@code floor(width + 1) - 1} blocks past each neighbour, and each of
   * those cells reads the cells around it to find its type.
   */
  public static int blocks(float maxPathLength, float width) {
    if (!(maxPathLength > 0 && maxPathLength <= WorldEvent.Search.MAX_PATH_LENGTH)) {
      throw new IllegalArgumentException("not a path length bound: " + maxPathLength);
    }
    if (!Float.isFinite(width) || width <= 0) {
      throw new IllegalArgumentException("width must be positive: " + width);
    }
    int footprint = (int) Math.floor(width + 1.0f);
    return (int) Math.ceil(maxPathLength) + footprint + 1;
  }

  /**
   * Whether the square area with corner {@code (minX, minZ)} and side {@code size} contains every
   * block a search from {@code start} can read.
   */
  public static boolean fits(
      int minX, int minZ, int size, Pos start, float maxPathLength, float width) {
    if (size <= 0) {
      throw new IllegalArgumentException("an area has a positive size: " + size);
    }
    long reach = blocks(maxPathLength, width);
    return start.x() - reach >= minX
        && start.x() + reach < (long) minX + size
        && start.z() - reach >= minZ
        && start.z() + reach < (long) minZ + size;
  }
}
