package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import maestro.fbs.PathType;

/**
 * Terrain the simulated server owns: superflat with mild hills, matching the fuzz-test world shape.
 * Coordinates start at the origin and stay small, so a position packs into a long trivially.
 *
 * <p>Deliberately shorter than a real overworld column. Height changes what a snapshot costs, not
 * what the controller does with one.
 */
public final class SimWorld implements WorldSource {

  public static final int MIN_Y = 0;
  public static final int HEIGHT = 64;

  private final int sizeX;
  private final int sizeZ;
  private final int[] ground;
  private final Map<Long, Byte> overrides = new TreeMap<>();
  private final Set<Long> loadedChunks = new LinkedHashSet<>();

  public SimWorld(int sizeX, int sizeZ, Rng rng) {
    this.sizeX = sizeX;
    this.sizeZ = sizeZ;
    this.ground = new int[sizeX * sizeZ];
    for (int i = 0; i < ground.length; i++) {
      ground[i] = HEIGHT / 2 + rng.nextInt(3);
    }
  }

  public int sizeX() {
    return sizeX;
  }

  public int sizeZ() {
    return sizeZ;
  }

  /** Highest solid block in the column. Mobs stand at {@code groundAt + 1}. */
  public int groundAt(int x, int z) {
    return ground[x * sizeZ + z];
  }

  public void loadChunk(int chunkX, int chunkZ) {
    loadedChunks.add(chunkKey(chunkX, chunkZ));
  }

  /** Applies a block change. The workload emits the matching event. */
  public void set(int x, int y, int z, byte pathType) {
    overrides.put(key(x, y, z), pathType);
  }

  @Override
  public int minY() {
    return MIN_Y;
  }

  @Override
  public int height() {
    return HEIGHT;
  }

  @Override
  public boolean chunkLoaded(int chunkX, int chunkZ) {
    return loadedChunks.contains(chunkKey(chunkX, chunkZ));
  }

  @Override
  public byte pathTypeAt(int x, int y, int z) {
    if (x < 0 || x >= sizeX || z < 0 || z >= sizeZ || y < MIN_Y || y >= MIN_Y + HEIGHT) {
      return (byte) PathType.BLOCKED;
    }
    if (!chunkLoaded(x >> 4, z >> 4)) {
      return (byte) PathType.BLOCKED;
    }
    Byte override = overrides.get(key(x, y, z));
    if (override != null) {
      return override;
    }
    return y <= ground[x * sizeZ + z] ? (byte) PathType.BLOCKED : (byte) PathType.OPEN;
  }

  private static long key(int x, int y, int z) {
    return ((long) x << 32) | ((long) z << 16) | y;
  }

  private static long chunkKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
  }
}
