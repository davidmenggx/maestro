package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Rng;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import maestro.fbs.PathType;

/**
 * Terrain owned by the simulated server: a square superflat world with mild hills, matching the
 * fuzz-test world shape, covering the {@link Topology}'s grid on both sides of the origin. A
 * column's terrain is a hash of the run's terrain seed and its position, made for a whole chunk the
 * first time the chunk is read, so only the part of the world a run touches is made, and every read
 * is O(1). Block changes are few, so they are kept in a map that is only looked up, never iterated,
 * and no iteration order can affect a run.
 *
 * <p>Vertical extent, floor and hill relief come from the {@link Topology}, so a sweep covers
 * negative floors and columns that encode into more than a couple of runs.
 */
public final class SimWorld implements WorldSource {

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

  private static final long NOT_LOADED = -1;

  private final Topology topology;
  private final int min;
  private final int size;
  private final int chunksPerSide;
  private final int sections;
  private final long terrainSeed;
  private final int lowestGround;

  /**
   * Each chunk's columns once made, by chunk index then column {@code dx * Chunks.SIZE + dz}, as
   * {@code ground << 1 | slabTopped}.
   */
  private final int[][] terrainByChunk;

  /** Changed blocks as {@link Cells#pack packed} cells, by {@link #blockKey}. */
  private final Map<Long, Integer> overrides = new HashMap<>();

  /** Changed blocks per chunk section, so a section without any can be summarized. */
  private final int[] overridesPerSection;

  /** Changed blocks per column, so a block in a column without any is not looked up. */
  private final int[] overridesPerColumn;

  /** The tick each chunk was last loaded at, or {@link #NOT_LOADED}. */
  private final long[] loadedSince;

  /**
   * Indices of the loaded chunks, the first {@link #loadedCount} of them, in no meaningful but a
   * deterministic order, so a workload can draw one in O(1).
   */
  private final int[] loaded;

  /** Where each chunk index sits in {@link #loaded}, or -1. */
  private final int[] loadedAt;

  /** Bumped whenever a chunk's cells change, so a digest of them can be kept until then. */
  private final int[] chunkVersions;

  private final long[] chunkHashes;
  private final int[] hashedVersions;
  private final byte[] hashCells;
  private final byte[] hashFloors;

  private int loadedCount;
  private int mostLoaded;
  private final List<MobClass> mobClasses;

  public SimWorld(Topology topology, int mobClassCount, Rng rng) {
    if (mobClassCount < 1) {
      throw new IllegalArgumentException("a world has at least one mob profile: " + mobClassCount);
    }
    this.topology = topology;
    this.min = topology.min();
    this.size = topology.extent();
    this.chunksPerSide = topology.chunksPerSide();
    this.sections = topology.height() / SECTION_HEIGHT;
    this.overridesPerColumn = new int[size * size];
    this.terrainByChunk = new int[chunksPerSide * chunksPerSide][];
    int chunks = chunksPerSide * chunksPerSide;
    this.overridesPerSection = new int[chunks * sections];
    this.loadedSince = new long[chunks];
    this.loaded = new int[chunks];
    this.loadedAt = new int[chunks];
    this.chunkVersions = new int[chunks];
    this.chunkHashes = new long[chunks];
    this.hashedVersions = new int[chunks];
    this.hashCells = new byte[Chunks.SIZE * Chunks.SIZE * topology.height()];
    this.hashFloors = new byte[hashCells.length];
    Arrays.fill(loadedSince, NOT_LOADED);
    Arrays.fill(loadedAt, -1);
    Arrays.fill(hashedVersions, -1);

    this.lowestGround = topology.minY() + topology.height() / 2;
    this.terrainSeed = rng.nextLong();

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

  /** Lowest x and z in the world. */
  public int min() {
    return min;
  }

  /** Size along both axes, in blocks. */
  public int size() {
    return size;
  }

  /** Lowest chunk x and z in the world. */
  public int minChunk() {
    return Chunks.of(min);
  }

  public int chunksPerSide() {
    return chunksPerSide;
  }

  public boolean contains(int x, int z) {
    return x >= min && x - min < size && z >= min && z - min < size;
  }

  /** Highest solid block in the column. Mobs stand at {@code groundAt + 1}. */
  public int groundAt(int x, int z) {
    return terrainAt(x, z) >> 1;
  }

  /**
   * The column's terrain as {@code ground << 1 | slabTopped}, where the top block is a bottom slab
   * in one column of four.
   */
  private int terrainAt(int x, int z) {
    int chunkX = Chunks.of(x);
    int chunkZ = Chunks.of(z);
    int[] columns = terrainByChunk[chunkIndex(chunkX, chunkZ)];
    if (columns == null) {
      columns = new int[Chunks.SIZE * Chunks.SIZE];
      for (int dx = 0; dx < Chunks.SIZE; dx++) {
        for (int dz = 0; dz < Chunks.SIZE; dz++) {
          long bits = terrain(chunkX * Chunks.SIZE + dx, chunkZ * Chunks.SIZE + dz);
          int ground = lowestGround + (int) Long.remainderUnsigned(bits, topology.relief() + 1);
          columns[dx * Chunks.SIZE + dz] = ground << 1 | ((bits >>> 62) == 0 ? 1 : 0);
        }
      }
      terrainByChunk[chunkIndex(chunkX, chunkZ)] = columns;
    }
    return columns[Math.floorMod(x, Chunks.SIZE) * Chunks.SIZE + Math.floorMod(z, Chunks.SIZE)];
  }

  /** The column's terrain bits: the splitmix64 finalizer over the seed and the position. */
  private long terrain(int x, int z) {
    long bits = terrainSeed ^ ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    bits = (bits ^ (bits >>> 30)) * 0xBF58476D1CE4E5B9L;
    bits = (bits ^ (bits >>> 27)) * 0x94D049BB133111EBL;
    return bits ^ (bits >>> 31);
  }

  public boolean chunkLoaded(int chunkX, int chunkZ) {
    return loadedSince[chunkIndex(chunkX, chunkZ)] != NOT_LOADED;
  }

  /** The tick the chunk was last loaded at, or -1 if it is not loaded. */
  public long loadedSince(int chunkX, int chunkZ) {
    return loadedSince[chunkIndex(chunkX, chunkZ)];
  }

  public void loadChunk(int chunkX, int chunkZ, long tick) {
    int index = chunkIndex(chunkX, chunkZ);
    if (loadedSince[index] != NOT_LOADED) {
      return;
    }
    loadedSince[index] = tick;
    chunkVersions[index]++;
    loaded[loadedCount] = index;
    loadedAt[index] = loadedCount++;
    mostLoaded = Math.max(mostLoaded, loadedCount);
  }

  public void unloadChunk(int chunkX, int chunkZ) {
    int index = chunkIndex(chunkX, chunkZ);
    if (loadedSince[index] == NOT_LOADED) {
      return;
    }
    loadedSince[index] = NOT_LOADED;
    chunkVersions[index]++;
    int at = loadedAt[index];
    int last = loaded[--loadedCount];
    loaded[at] = last;
    loadedAt[last] = at;
    loadedAt[index] = -1;
  }

  public int loadedCount() {
    return loadedCount;
  }

  /** The most chunks loaded at once so far. */
  public int mostLoaded() {
    return mostLoaded;
  }

  /** The {@code n}th loaded chunk, as a {@link Chunks#key}. */
  public long loadedChunk(int n) {
    if (n < 0 || n >= loadedCount) {
      throw new IllegalArgumentException("no loaded chunk " + n + " of " + loadedCount);
    }
    int index = loaded[n];
    return Chunks.key(minChunk() + index / chunksPerSide, minChunk() + index % chunksPerSide);
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
    int section = (y - topology.minY()) / SECTION_HEIGHT;
    if (overrides.put(blockKey(x, y, z), Cells.pack(floor, pathType)) == null) {
      overridesPerColumn[column(x, z)]++;
      if (section < sections) {
        overridesPerSection[sectionIndex(Chunks.of(x), Chunks.of(z), section)]++;
      }
    }
    chunkVersions[chunkIndex(Chunks.of(x), Chunks.of(z))]++;
  }

  /**
   * The {@link ModeledNode.AreaDigest#chunkHash} of the chunk as a node holds it once it has every
   * frame: its blocks if it is loaded, and air if not. Kept until the chunk changes, since every
   * checkpoint reads every chunk of a region.
   */
  public long chunkHash(int chunkX, int chunkZ) {
    int index = chunkIndex(chunkX, chunkZ);
    if (hashedVersions[index] == chunkVersions[index]) {
      return chunkHashes[index];
    }
    long hash = ModeledNode.AreaDigest.AIR;
    if (chunkLoaded(chunkX, chunkZ)) {
      fillChunk(chunkX, chunkZ, hashCells, hashFloors);
      hash = ModeledNode.AreaDigest.chunkHash(hashCells, hashFloors);
    }
    chunkHashes[index] = hash;
    hashedVersions[index] = chunkVersions[index];
    return hash;
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
    return Cells.typeOf(cellAt(x, y, z));
  }

  @Override
  public int floorAt(int x, int y, int z) {
    return Cells.floorOf(cellAt(x, y, z));
  }

  /** The block as a packed cell. Outside the world or a loaded chunk, a full solid block. */
  public int cellAt(int x, int y, int z) {
    if (!inside(x, y, z) || !chunkLoaded(Chunks.of(x), Chunks.of(z))) {
      return Cells.pack(Cells.FULL_FLOOR, (byte) PathType.BLOCKED);
    }
    if (overridesPerColumn[column(x, z)] > 0) {
      Integer override = overrides.get(blockKey(x, y, z));
      if (override != null) {
        return override;
      }
    }
    int terrain = terrainAt(x, z);
    int ground = terrain >> 1;
    if (y > ground) {
      return Cells.pack(0, (byte) PathType.OPEN);
    }
    int floor = y == ground && (terrain & 1) != 0 ? SLAB_FLOOR : Cells.FULL_FLOOR;
    return Cells.pack(floor, (byte) PathType.BLOCKED);
  }

  /**
   * Every block of a loaded chunk, as {@link #cellAt} gives it, indexed {@code (dx * Chunks.SIZE +
   * dz) * height + dy}. Each column is filled as terrain in three runs, then a changed block in it
   * is written over its run.
   */
  private void fillChunk(int chunkX, int chunkZ, byte[] cells, byte[] floors) {
    int height = topology.height();
    for (int dx = 0; dx < Chunks.SIZE; dx++) {
      for (int dz = 0; dz < Chunks.SIZE; dz++) {
        int x = chunkX * Chunks.SIZE + dx;
        int z = chunkZ * Chunks.SIZE + dz;
        int base = (dx * Chunks.SIZE + dz) * height;
        int terrain = terrainAt(x, z);
        int top = (terrain >> 1) - minY();
        Arrays.fill(cells, base, base + top + 1, (byte) PathType.BLOCKED);
        Arrays.fill(floors, base, base + top, (byte) Cells.FULL_FLOOR);
        floors[base + top] = (byte) ((terrain & 1) != 0 ? SLAB_FLOOR : Cells.FULL_FLOOR);
        Arrays.fill(cells, base + top + 1, base + height, (byte) PathType.OPEN);
        Arrays.fill(floors, base + top + 1, base + height, (byte) 0);
        if (overridesPerColumn[column(x, z)] == 0) {
          continue;
        }
        for (int dy = 0; dy < height; dy++) {
          Integer override = overrides.get(blockKey(x, minY() + dy, z));
          if (override != null) {
            cells[base + dy] = Cells.typeOf(override);
            floors[base + dy] = (byte) Cells.floorOf(override);
          }
        }
      }
    }
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
        int terrain = terrainAt(x, z);
        int ground = terrain >> 1;
        air &= ground < low;
        // The top block may be a slab, which differs from the blocks below it.
        solid &= ground > high || (ground == high && (terrain & 1) == 0);
      }
    }
    if (air) {
      return Cells.pack(0, (byte) PathType.OPEN);
    }
    return solid ? Cells.pack(Cells.FULL_FLOOR, (byte) PathType.BLOCKED) : NOT_UNIFORM;
  }

  /**
   * Reads even sections whole and leaves odd ones to block reads, so every run encodes chunks both
   * ways and the state comparisons check both.
   */
  @Override
  public boolean readSection(int chunkX, int chunkZ, int section, int[] cells) {
    if (section % 2 == 1 || !chunkLoaded(chunkX, chunkZ)) {
      return false;
    }
    int low = topology.minY() + section * SECTION_HEIGHT;
    for (int dx = 0; dx < Chunks.SIZE; dx++) {
      for (int dz = 0; dz < Chunks.SIZE; dz++) {
        int x = chunkX * Chunks.SIZE + dx;
        int z = chunkZ * Chunks.SIZE + dz;
        for (int dy = 0; dy < SECTION_HEIGHT; dy++) {
          cells[(dx * Chunks.SIZE + dz) * SECTION_HEIGHT + dy] = cellAt(x, low + dy, z);
        }
      }
    }
    return true;
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
    return contains(x, z) && y >= topology.minY() && y < topology.minY() + topology.height();
  }

  private int column(int x, int z) {
    return (x - min) * size + (z - min);
  }

  private int chunkIndex(int chunkX, int chunkZ) {
    int dx = chunkX - minChunk();
    int dz = chunkZ - minChunk();
    if (dx < 0 || dx >= chunksPerSide || dz < 0 || dz >= chunksPerSide) {
      throw new IllegalArgumentException("chunk outside the world: " + chunkX + "," + chunkZ);
    }
    return dx * chunksPerSide + dz;
  }

  private int sectionIndex(int chunkX, int chunkZ, int section) {
    return chunkIndex(chunkX, chunkZ) * sections + section;
  }

  /**
   * A block position as one key: x in the high half, y and z in the low, so the key's hash code,
   * which folds the halves together, spreads. Y fits 12 bits and z 20 in any world a topology
   * makes.
   */
  private static long blockKey(int x, int y, int z) {
    return ((long) x << 32) | ((long) (y & 0xFFF) << 20) | (z & 0xFFFFF);
  }
}
