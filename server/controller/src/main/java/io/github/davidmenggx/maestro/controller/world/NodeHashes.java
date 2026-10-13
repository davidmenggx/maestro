package io.github.davidmenggx.maestro.controller.world;

/**
 * Vanilla keys a search's nodes by {@code Node.createHash}, which keeps only part of a position.
 * Two positions with one hash share a node, so the search treats one as the other. The compute node
 * keys its nodes by the whole position and cannot do the same, so a search where this could happen
 * stays vanilla.
 *
 * <p>The hash keeps {@code y} modulo 256, {@code z} modulo 256 and {@code x} modulo 32768, except
 * that {@code z}'s bit 7 shares a hash bit with the sign of {@code x}, and {@code x}'s bit 7 with
 * the sign of {@code z}. Two positions within a search therefore share a hash only if they are 256
 * apart in {@code y} or {@code z}, 128 apart in {@code z} where {@code x} is negative, or 128 apart
 * in {@code x} where {@code z} is negative.
 */
public final class NodeHashes {

  private NodeHashes() {}

  /** {@code Node.createHash}. */
  static int hash(int x, int y, int z) {
    return y & 0xFF
        | (x & 32767) << 8
        | (z & 32767) << 24
        | (x < 0 ? Integer.MIN_VALUE : 0)
        | (z < 0 ? 32768 : 0);
  }

  /**
   * Whether two nodes of one search could share a hash: two positions the search may create, or one
   * of those and the target, which vanilla creates before searching.
   *
   * <p>A node is expanded only while nearer the start than {@code maxPathLength}, and creates nodes
   * one block further, up to {@code floor(max(1, maxUpStep))} blocks above it, and down to {@code
   * maxFallDistance + 1} blocks below it. A mob that cannot float also sinks through water, as far
   * as {@code minY}.
   */
  public static boolean mayCollide(
      Pos start,
      Pos target,
      float maxPathLength,
      int maxFallDistance,
      float maxUpStep,
      boolean canFloat,
      int minY) {
    // Past these, the box alone holds two positions with one hash, and the arithmetic below could
    // overflow.
    if (!(maxPathLength < 32768) || !(maxUpStep < 256)) {
      return true;
    }
    long reach = (long) Math.ceil(maxPathLength);
    long jump = (long) Math.floor(Math.max(1.0f, maxUpStep));
    long minX = start.x() - reach;
    long maxX = start.x() + reach;
    long minZ = start.z() - reach;
    long maxZ = start.z() + reach;
    long top = start.y() + reach - 1 + jump;
    long bottom = canFloat ? Math.max(minY, start.y() - reach - maxFallDistance) : minY;

    // Two positions inside the box.
    if (top - bottom >= 256 || maxZ - minZ >= 256 || maxX - minX >= 32768) {
      return true;
    }
    if ((maxZ - minZ >= 128 && minX < 0) || (maxX - minX >= 128 && minZ < 0)) {
      return true;
    }

    // The target and a position inside the box. Only positions a multiple of 128 away from the
    // target along each axis can share its hash.
    int targetHash = hash(target.x(), target.y(), target.z());
    for (long x = first(minX, target.x(), 128); x <= maxX; x += 128) {
      for (long z = first(minZ, target.z(), 128); z <= maxZ; z += 128) {
        for (long y = first(bottom, target.y(), 256); y <= top; y += 256) {
          boolean same = x == target.x() && y == target.y() && z == target.z();
          if (!same && hash((int) x, (int) y, (int) z) == targetHash) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Smallest value at or above {@code low} that is {@code value} plus a multiple of {@code step}.
   */
  private static long first(long low, long value, long step) {
    return low + Math.floorMod(value - low, step);
  }
}
