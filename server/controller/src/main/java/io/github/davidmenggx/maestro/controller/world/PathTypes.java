package io.github.davidmenggx.maestro.controller.world;

import maestro.fbs.PathType;

/**
 * The half of the {@code PathType} contract the controller is responsible for. A cell may only
 * carry a type {@code getPathTypeFromState} can return: the rest are composed on the node, from the
 * cell below and the 24 surrounding cells, and cannot be known from one block state.
 *
 * <p>Sending a derived type would silently disagree with vanilla, so it is rejected at the boundary.
 */
public final class PathTypes {

  private static final boolean[] PER_BLOCK = new boolean[PathType.names.length];

  static {
    int[] perBlock = {
      PathType.BLOCKED,
      PathType.OPEN,
      PathType.TRAPDOOR,
      PathType.POWDER_SNOW,
      PathType.FENCE,
      PathType.LAVA,
      PathType.WATER,
      PathType.RAIL,
      PathType.FIRE,
      PathType.DAMAGING,
      PathType.DOOR_OPEN,
      PathType.DOOR_WOOD_CLOSED,
      PathType.DOOR_IRON_CLOSED,
      PathType.LEAVES,
      PathType.STICKY_HONEY,
      PathType.COCOA,
      PathType.DAMAGE_CAUTIOUS
    };
    for (int ordinal : perBlock) {
      PER_BLOCK[ordinal] = true;
    }
  }

  private PathTypes() {}

  /** Number of ordinals in the shared enum. The per-mob malus table is this long. */
  public static int count() {
    return PathType.names.length;
  }

  /** Whether a cell on the wire may carry this ordinal. */
  public static boolean isPerBlock(byte pathType) {
    return pathType >= 0 && pathType < PER_BLOCK.length && PER_BLOCK[pathType];
  }

  public static String name(byte pathType) {
    return pathType >= 0 && pathType < PathType.names.length
        ? PathType.names[pathType]
        : "invalid(" + pathType + ")";
  }
}
