package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import maestro.fbs.PathType;

/**
 * Terrain owned by the simulated server: a square superflat world with mild hills, matching the
 * fuzz-test world shape. Stored in flat arrays because it is dense and small, so every read is O(1)
 * and no iteration order can affect a run.
 *
 * <p>Vertical extent, floor and hill relief come from the {@link Topology}, so a sweep covers
 * negative floors and columns that encode into more than a couple of runs.
 */
public final class SimWorld implements WorldSource {

  /** Marks a cell whose type still comes from the terrain rule, not a block change. */
  private static final byte NO_OVERRIDE = -1;

  /** A bottom slab, so a column's top block can differ from the ones below it only in floor. */
  private static final int SLAB_FLOOR = Cells.FULL_FLOOR / 2;

  private static final int FENCE_FLOOR = Cells.FULL_FLOOR * 3 / 2;

  private static final float MIN_WIDTH = 0.3f;
  private static final float MAX_EXTRA_WIDTH = 1.2f;
  private static final float MIN_MOB_HEIGHT = 0.5f;
  private static final float MAX_EXTRA_MOB_HEIGHT = 2.5f;
  private static final float MIN_UP_STEP = 0.5f;
  private static final float MAX_EXTRA_UP_STEP = 1.0f;
  private static final float MAX_MALUS = 16.0f;

  /** Fraction of ordinals a profile forbids, which vanilla represents as a negative malus. */
  private static final double IMPASSABLE_CHANCE = 0.15;

  private final Topology topology;
  private final int size;
  private final int chunksPerSide;
  private final int[] ground;
  private final boolean[] slabTopped;
  private final byte[] overrides;
  private final byte[] overrideFloors;

  /** Cells with an override, per chunk section, so a section without any can be summarized. */
  private final int[] overridesPerSection;

  private final boolean[] loadedChunks;
  private final List<MobClass> mobClasses;

  public SimWorld(Topology topology, int mobClassCount, Rng rng) {
    if (mobClassCount < 1) {
      throw new IllegalArgumentException("a world has at least one mob profile: " + mobClassCount);
    }
    this.topology = topology;
    this.size = topology.extent();
    this.chunksPerSide = topology.chunksPerSide();
    this.ground = new int[size * size];
    this.slabTopped = new boolean[size * size];
    this.overrides = new byte[size * size * topology.height()];
    this.overrideFloors = new byte[overrides.length];
    this.overridesPerSection =
        new int[chunksPerSide * chunksPerSide * (topology.height() / SECTION_HEIGHT)];
    this.loadedChunks = new boolean[chunksPerSide * chunksPerSide];
    Arrays.fill(overrides, NO_OVERRIDE);

    int lowestGround = topology.minY() + topology.height() / 2;
    for (int i = 0; i < ground.length; i++) {
      ground[i] = lowestGround + rng.nextInt(topology.relief() + 1);
      slabTopped[i] = rng.nextInt(4) == 0;
    }

    List<MobClass> profiles = new ArrayList<>(mobClassCount);
    for (int i = 0; i < mobClassCount; i++) {
      profiles.add(profile((short) (i + 1), rng));
    }
    this.mobClasses = List.copyOf(profiles);
  }

  @Override
  public MobClass mobClass(short classId) {
    int index = classId - 1;
    return index >= 0 && index < mobClasses.size() ? mobClasses.get(index) : null;
  }

  /** Profiles this world can provide. A workload draws a request's class from these. */
  public int mobClassCount() {
    return mobClasses.size();
  }

  /** Size along both axes, in blocks. */
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
  public void set(int x, int y, int z, byte pathType, int floor) {
    if (!inside(x, y, z)) {
      throw new IllegalArgumentException("block outside the world: " + x + "," + y + "," + z);
    }
    if (!PathTypes.isPerBlock(pathType)) {
      throw new IllegalArgumentException("not a per-block type: " + PathTypes.name(pathType));
    }
    if (floor < 0 || floor > Cells.MAX_FLOOR) {
      throw new IllegalArgumentException("floor out of range: " + floor);
    }
    int index = index(x, y, z);
    int section = (y - topology.minY()) / SECTION_HEIGHT;
    if (overrides[index] == NO_OVERRIDE && section < topology.height() / SECTION_HEIGHT) {
      overridesPerSection[sectionIndex(Chunks.of(x), Chunks.of(z), section)]++;
    }
    overrides[index] = pathType;
    overrideFloors[index] = (byte) floor;
  }

  /**
   * A collision height that matches the type, as a placed block would have: solid blocks are full
   * or slabs, fences are a block and a half, and everything else has no collision.
   */
  public static int floorFor(byte pathType, Rng rng) {
    if (pathType == (byte) PathType.BLOCKED) {
      return rng.nextBoolean() ? Cells.FULL_FLOOR : SLAB_FLOOR;
    }
    return pathType == (byte) PathType.FENCE ? FENCE_FLOOR : 0;
  }

  @Override
  public int minY() {
    return topology.minY();
  }

  @Override
  public int height() {
    return topology.height();
  }

  @Override
  public byte pathTypeAt(int x, int y, int z) {
    if (!inside(x, y, z) || !loadedChunks[chunkIndex(Chunks.of(x), Chunks.of(z))]) {
      return (byte) PathType.BLOCKED;
    }
    byte override = overrides[index(x, y, z)];
    if (override != NO_OVERRIDE) {
      return override;
    }
    return y <= ground[x * size + z] ? (byte) PathType.BLOCKED : (byte) PathType.OPEN;
  }

  @Override
  public int floorAt(int x, int y, int z) {
    if (!inside(x, y, z) || !loadedChunks[chunkIndex(Chunks.of(x), Chunks.of(z))]) {
      return Cells.FULL_FLOOR;
    }
    int index = index(x, y, z);
    if (overrides[index] != NO_OVERRIDE) {
      return overrideFloors[index] & 0xFF;
    }
    int column = x * size + z;
    if (y > ground[column]) {
      return 0;
    }
    return y == ground[column] && slabTopped[column] ? SLAB_FLOOR : Cells.FULL_FLOOR;
  }

  /**
   * A section is uniform when no block in it was changed and every column's ground lies wholly
   * above it, so it is solid, or wholly below it, so it is air. Anything else is read block by
   * block, which is always correct.
   */
  @Override
  public int uniformCell(int chunkX, int chunkZ, int section) {
    if (!chunkLoaded(chunkX, chunkZ)
        || overridesPerSection[sectionIndex(chunkX, chunkZ, section)] > 0) {
      return NOT_UNIFORM;
    }
    int low = topology.minY() + section * SECTION_HEIGHT;
    int high = low + SECTION_HEIGHT - 1;
    boolean air = true;
    boolean solid = true;
    for (int x = chunkX * Chunks.SIZE; x < (chunkX + 1) * Chunks.SIZE; x++) {
      for (int z = chunkZ * Chunks.SIZE; z < (chunkZ + 1) * Chunks.SIZE; z++) {
        int column = x * size + z;
        air &= ground[column] < low;
        // The top block may be a slab, which differs from the blocks below it.
        solid &= ground[column] > high || (ground[column] == high && !slabTopped[column]);
      }
    }
    if (air) {
      return PathType.OPEN;
    }
    return solid ? (Cells.FULL_FLOOR << 8) | PathType.BLOCKED : NOT_UNIFORM;
  }

  /**
   * One randomly drawn {@code WalkNodeEvaluator} profile. Every field the wire carries is drawn, so
   * the mob class table is encoded and decoded with values that vary between runs, not constants.
   */
  private static MobClass profile(short classId, Rng rng) {
    float[] malus = new float[PathTypes.count()];
    for (int ordinal = 0; ordinal < malus.length; ordinal++) {
      malus[ordinal] =
          rng.nextDouble() < IMPASSABLE_CHANCE ? -1.0f : (float) (rng.nextDouble() * MAX_MALUS);
    }
    MobClass.Shape shape =
        new MobClass.Shape(
            MIN_WIDTH + (float) rng.nextDouble() * MAX_EXTRA_WIDTH,
            MIN_MOB_HEIGHT + (float) rng.nextDouble() * MAX_EXTRA_MOB_HEIGHT,
            MIN_UP_STEP + (float) rng.nextDouble() * MAX_EXTRA_UP_STEP);
    MobClass.Abilities abilities =
        new MobClass.Abilities(
            rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean());
    return new MobClass(classId, shape, abilities, malus);
  }

  private boolean inside(int x, int y, int z) {
    return x >= 0
        && x < size
        && z >= 0
        && z < size
        && y >= topology.minY()
        && y < topology.minY() + topology.height();
  }

  private int chunkIndex(int chunkX, int chunkZ) {
    if (chunkX < 0 || chunkX >= chunksPerSide || chunkZ < 0 || chunkZ >= chunksPerSide) {
      throw new IllegalArgumentException("chunk outside the world: " + chunkX + "," + chunkZ);
    }
    return chunkX * chunksPerSide + chunkZ;
  }

  private int sectionIndex(int chunkX, int chunkZ, int section) {
    return chunkIndex(chunkX, chunkZ) * (topology.height() / SECTION_HEIGHT) + section;
  }

  private int index(int x, int y, int z) {
    return (x * size + z) * topology.height() + (y - topology.minY());
  }
}
