package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.Arrays;
import java.util.List;
import maestro.fbs.PathType;

/**
 * Terrain the simulated server owns: a square superflat world with mild hills, matching the
 * fuzz-test world shape. Held as flat arrays because it is dense and small, so every read is O(1)
 * and no iteration order can leak into a run.
 *
 * <p>Deliberately shorter than a real overworld column. Height changes what a snapshot costs, not
 * what the controller does with one.
 */
public final class SimWorld implements WorldSource {

  public static final int MIN_Y = 0;
  public static final int HEIGHT = 64;

  public static final int CHUNK_SIZE = 16;

  /** Two profiles, so a node has something to reject a request against. */
  private static final List<MobClass> MOB_CLASSES =
      List.of(profile((short) 1, 0.6f, 1.95f, 16), profile((short) 2, 0.9f, 1.4f, 32));

  /** Marks a cell whose type still comes from the terrain rule rather than a block change. */
  private static final byte NO_OVERRIDE = -1;

  private final int size;
  private final int chunksPerSide;
  private final int[] ground;
  private final byte[] overrides;
  private final boolean[] loadedChunks;

  public SimWorld(int size, Rng rng) {
    if (size <= 0 || size % CHUNK_SIZE != 0) {
      throw new IllegalArgumentException("world size must be a multiple of " + CHUNK_SIZE);
    }
    this.size = size;
    this.chunksPerSide = size / CHUNK_SIZE;
    this.ground = new int[size * size];
    this.overrides = new byte[size * size * HEIGHT];
    this.loadedChunks = new boolean[chunksPerSide * chunksPerSide];
    Arrays.fill(overrides, NO_OVERRIDE);
    for (int i = 0; i < ground.length; i++) {
      ground[i] = HEIGHT / 2 + rng.nextInt(3);
    }
  }

  @Override
  public List<MobClass> mobClasses() {
    return MOB_CLASSES;
  }

  /** Block extent along both axes. */
  public int size() {
    return size;
  }

  public int chunksPerSide() {
    return chunksPerSide;
  }

  /** Highest solid block in the column. Mobs stand at {@code groundAt + 1}. */
  public int groundAt(int x, int z) {
    return ground[x * size + z];
  }

  public boolean chunkLoaded(int chunkX, int chunkZ) {
    return loadedChunks[chunkIndex(chunkX, chunkZ)];
  }

  public void loadChunk(int chunkX, int chunkZ) {
    loadedChunks[chunkIndex(chunkX, chunkZ)] = true;
  }

  public void unloadChunk(int chunkX, int chunkZ) {
    loadedChunks[chunkIndex(chunkX, chunkZ)] = false;
  }

  /** Applies a block change. The workload emits the matching event. */
  public void set(int x, int y, int z, byte pathType) {
    if (x < 0 || x >= size || z < 0 || z >= size || y < MIN_Y || y >= MIN_Y + HEIGHT) {
      throw new IllegalArgumentException("block outside the world: " + x + "," + y + "," + z);
    }
    if (!PathTypes.isPerBlock(pathType)) {
      throw new IllegalArgumentException("not a per-block type: " + PathTypes.name(pathType));
    }
    overrides[index(x, y, z)] = pathType;
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
  public byte pathTypeAt(int x, int y, int z) {
    if (x < 0 || x >= size || z < 0 || z >= size || y < MIN_Y || y >= MIN_Y + HEIGHT) {
      return (byte) PathType.BLOCKED;
    }
    if (!loadedChunks[chunkIndex(x / CHUNK_SIZE, z / CHUNK_SIZE)]) {
      return (byte) PathType.BLOCKED;
    }
    byte override = overrides[index(x, y, z)];
    if (override != NO_OVERRIDE) {
      return override;
    }
    return y <= ground[x * size + z] ? (byte) PathType.BLOCKED : (byte) PathType.OPEN;
  }

  private static MobClass profile(short classId, float width, float height, int followRange) {
    float[] malus = new float[PathTypes.count()];
    malus[PathType.WATER] = 8.0f;
    malus[PathType.LAVA] = -1.0f;
    malus[PathType.DAMAGE_CAUTIOUS] = 8.0f;
    return new MobClass(
        classId, width, height, malus, 0.5f, true, false, false, false, followRange);
  }

  private int chunkIndex(int chunkX, int chunkZ) {
    if (chunkX < 0 || chunkX >= chunksPerSide || chunkZ < 0 || chunkZ >= chunksPerSide) {
      throw new IllegalArgumentException("chunk outside the world: " + chunkX + "," + chunkZ);
    }
    return chunkX * chunksPerSide + chunkZ;
  }

  private int index(int x, int y, int z) {
    return (x * size + z) * HEIGHT + (y - MIN_Y);
  }
}
