package io.github.davidmenggx.maestro.controller.world;

import maestro.fbs.PathType;

/**
 * The part of the {@code PathType} contract the controller is responsible for. A cell may carry
 * only a type that {@code getPathTypeFromState} can return. The other types are computed on the
 * node from the cell below and the 24 surrounding cells, and cannot be known from one block state.
 *
 * <p>Sending a derived type would silently disagree with vanilla, so the boundary rejects it.
 */
public final class PathTypes {

  private static final byte[] PER_BLOCK_ORDINALS = {
    (byte) PathType.BLOCKED,
    (byte) PathType.OPEN,
    (byte) PathType.TRAPDOOR,
    (byte) PathType.POWDER_SNOW,
    (byte) PathType.FENCE,
    (byte) PathType.LAVA,
    (byte) PathType.WATER,
    (byte) PathType.RAIL,
    (byte) PathType.FIRE,
    (byte) PathType.DAMAGING,
    (byte) PathType.DOOR_OPEN,
    (byte) PathType.DOOR_WOOD_CLOSED,
    (byte) PathType.DOOR_IRON_CLOSED,
    (byte) PathType.LEAVES,
    (byte) PathType.STICKY_HONEY,
    (byte) PathType.COCOA,
    (byte) PathType.DAMAGE_CAUTIOUS
  };

  private static final boolean[] PER_BLOCK = new boolean[PathType.names.length];

  static {
    for (byte ordinal : PER_BLOCK_ORDINALS) {
      PER_BLOCK[ordinal] = true;
    }
  }

  private PathTypes() {}

  /** Number of ordinals in the shared enum, which is also the length of a malus table. */
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
