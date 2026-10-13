package io.github.davidmenggx.maestro.capture;

import io.github.davidmenggx.maestro.bridge.LevelWorldSource;
import io.github.davidmenggx.maestro.bridge.SearchCall;
import io.github.davidmenggx.maestro.bridge.Searches;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.SearchReach;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Runs vanilla searches on seeded terrain and records each as a parity case. Everything happens in
 * one call on the server thread, so no tick changes the world between building it and searching it.
 */
final class ParityCapture implements Searches.Observer {

  /** Side of the area in chunks. Large enough for a follow range of 64 blocks. */
  static final int AREA_CHUNKS = 10;

  static final int SIZE = AREA_CHUNKS * Chunks.SIZE;

  /** Corner of the area on both axes: whole chunks, far from spawn. */
  static final int ORIGIN = 4096;

  private static final long RUN = 1;
  private static final int REGION = 0;
  private static final int ATTEMPTS_PER_CASE = 50;

  /** Block change frames in a capture, each between two batches of searches. */
  private static final int DELTA_BATCHES = 3;

  private static final Difficulty[] DIFFICULTIES = {
    Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD
  };
  private static final float[] BUDGET_MULTIPLIERS = {0.02f, 0.05f, 0.1f, 0.25f};
  private static final float[] MALUS_VALUES = {-1.0f, 0.0f, 2.0f, 4.0f, 8.0f, 16.0f};

  /** Two and above give a jump of more than one block. */
  private static final double[] STEP_HEIGHTS = {1.0, 1.5, 2.0, 2.5};

  private static final int[] REACH_RANGES = {0, 1, 1, 1, 2, 3};
  private static final PathType[] TYPES = PathType.values();

  /** The mobs captured, chosen for their footprints, door handling and malus tables. */
  private enum Kind {
    ZOMBIE(EntityType.ZOMBIE),
    BABY_ZOMBIE(EntityType.ZOMBIE),
    VILLAGER(EntityType.VILLAGER),
    SPIDER(EntityType.SPIDER),
    IRON_GOLEM(EntityType.IRON_GOLEM),
    SNIFFER(EntityType.SNIFFER),
    COW(EntityType.COW);

    final EntityType<? extends Mob> type;

    Kind(EntityType<? extends Mob> type) {
      this.type = type;
    }
  }

  /**
   * {@code offStart} cases are approximated because the node's stand-in for the mob's position
   * differs from it where that matters, {@code modelDiffered} because a collision or floor query
   * got a different answer from the model. {@code deltas} is the number of block change frames.
   */
  record Summary(int cases, int exact, int reached, int offStart, int modelDiffered, int deltas) {}

  private record Recorded(
      SearchCall call, List<WorldOutput.Node> nodes, boolean reached, float cost) {}

  private record Captured(
      FixtureFile.Case recorded, boolean reached, boolean offStart, boolean modelDiffered) {}

  private final ServerLevel level;
  private final SplittableRandom rng;
  private final ProfileTable profiles = new ProfileTable();
  private final LevelWorldSource world;
  private final int minY;
  private final int maxY;
  private final List<Terrain.Enclosure> enclosures;
  private final List<Recorded> recorded = new ArrayList<>();
  private final ArmorStand quarry;
  private int lastDeltaAt = -1;

  private ParityCapture(ServerLevel level, long seed, List<Terrain.Enclosure> enclosures) {
    this.level = level;
    this.enclosures = enclosures;
    this.rng = new SplittableRandom(seed ^ 0x5DEECE66DL);
    this.world = new LevelWorldSource(level, profiles);
    this.minY = level.getMinY();
    this.maxY = level.getMaxY();
    this.quarry = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
  }

  /**
   * Captures {@code count} cases on the terrain for {@code seed} and writes them to {@code file}.
   */
  static Summary run(ServerLevel level, long seed, int count, java.nio.file.Path file)
      throws IOException {
    Difficulty difficulty = level.getDifficulty();
    int firstChunk = Chunks.of(ORIGIN);
    try {
      for (int cx = firstChunk; cx < firstChunk + AREA_CHUNKS; cx++) {
        for (int cz = firstChunk; cz < firstChunk + AREA_CHUNKS; cz++) {
          level.setChunkForced(cx, cz, true);
          if (level.getChunkSource().getChunkNow(cx, cz) == null) {
            throw new IllegalStateException("chunk " + cx + ", " + cz + " did not load");
          }
        }
      }
      List<Terrain.Enclosure> enclosures = Terrain.build(level, ORIGIN, ORIGIN, SIZE, seed);
      ParityCapture capture = new ParityCapture(level, seed, enclosures);
      return capture.capture(seed, count, file);
    } finally {
      level.getServer().setDifficulty(difficulty, true);
      for (int cx = firstChunk; cx < firstChunk + AREA_CHUNKS; cx++) {
        for (int cz = firstChunk; cz < firstChunk + AREA_CHUNKS; cz++) {
          level.setChunkForced(cx, cz, false);
        }
      }
    }
  }

  private Summary capture(long seed, int count, java.nio.file.Path file) throws IOException {
    forgetCachedTypes();

    List<byte[]> worldFrames = new ArrayList<>();
    worldFrames.add(Frames.assignment(RUN, REGION, SIZE, 0, false, world));
    long sequence = 0;
    int firstChunk = Chunks.of(ORIGIN);
    for (int dx = 0; dx < AREA_CHUNKS; dx++) {
      for (int dz = 0; dz < AREA_CHUNKS; dz++) {
        Frames.ChunkRuns runs = Frames.readChunk(world, firstChunk + dx, firstChunk + dz);
        byte[] frame = Frames.chunkSnapshot(++sequence, dx, dz, runs);
        // The section shortcut must give what reading every block gives.
        byte[] reference =
            Frames.chunkSnapshot(
                sequence,
                dx,
                dz,
                Frames.readChunk(new BlockByBlock(world), firstChunk + dx, firstChunk + dz));
        if (!Arrays.equals(frame, reference)) {
          throw new IllegalStateException(
              "sections and blocks disagree in chunk " + dx + ", " + dz);
        }
        worldFrames.add(frame);
      }
    }

    List<FixtureFile.Case> cases = new ArrayList<>();
    int exact = 0;
    int reached = 0;
    int offStart = 0;
    int modelDiffered = 0;
    int deltas = 0;
    // Block changes between batches of searches, so the node is also checked against a world it
    // reached by deltas.
    int batch = Math.max(1, count / (DELTA_BATCHES + 1));
    ApproximationProbe probe = ApproximationProbe.start(world);
    try {
      for (int attempt = 0;
          cases.size() < count && attempt < count * ATTEMPTS_PER_CASE;
          attempt++) {
        if (!cases.isEmpty()
            && cases.size() % batch == 0
            && deltas < DELTA_BATCHES
            && lastDeltaAt != cases.size()) {
          lastDeltaAt = cases.size();
          worldFrames.add(Frames.delta(++sequence, mutate(), ORIGIN, ORIGIN));
          deltas++;
        }
        Captured captured = attempt(cases.size() + 1, sequence, worldFrames.size(), probe);
        if (captured != null) {
          cases.add(captured.recorded());
          exact += captured.recorded().exact() ? 1 : 0;
          reached += captured.reached() ? 1 : 0;
          offStart += captured.offStart() ? 1 : 0;
          modelDiffered += captured.modelDiffered() ? 1 : 0;
        }
      }
    } finally {
      ApproximationProbe.stop();
    }
    if (cases.size() < count) {
      throw new IllegalStateException("only " + cases.size() + " of " + count + " cases found");
    }
    FixtureFile.write(file, seed, FixtureFile.frame(worldFrames), cases);
    return new Summary(cases.size(), exact, reached, offStart, modelDiffered, deltas);
  }

  /**
   * Changes a few blocks near the surface and returns the changes as the controller would report
   * them. Cached path types are cleared for the changed blocks, as for the terrain.
   */
  private List<WorldEvent.BlockChanged> mutate() {
    BlockState[] placed = {
      Blocks.STONE.defaultBlockState(),
      Blocks.OAK_FENCE.defaultBlockState(),
      Blocks.WHITE_CARPET.defaultBlockState(),
      Blocks.WATER.defaultBlockState(),
      Blocks.LAVA.defaultBlockState(),
      Blocks.MAGMA_BLOCK.defaultBlockState(),
      Blocks.AIR.defaultBlockState()
    };
    Map<Long, BlockPos> changed = new LinkedHashMap<>();
    for (int i = 4 + rng.nextInt(40); i > 0; i--) {
      int x = ORIGIN + rng.nextInt(SIZE);
      int z = ORIGIN + rng.nextInt(SIZE);
      int top = topBlock(x, z);
      BlockState state = placed[rng.nextInt(placed.length)];
      // Air digs the surface. Everything else is placed on it or in place of it.
      int y = state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA) ? top : top + 1;
      if (y <= minY || y >= minY + Terrain.BAND) {
        continue;
      }
      BlockPos pos = new BlockPos(x, y, z);
      level.setBlock(pos, state, Terrain.FLAGS);
      changed.put(pos.asLong(), pos);
    }
    List<WorldEvent.BlockChanged> events = new ArrayList<>();
    for (BlockPos pos : changed.values()) {
      level.getPathTypeCache().invalidate(pos);
      events.add(
          new WorldEvent.BlockChanged(
              0,
              pos(pos),
              world.pathTypeAt(pos.getX(), pos.getY(), pos.getZ()),
              world.floorAt(pos.getX(), pos.getY(), pos.getZ())));
    }
    return events;
  }

  /** Height of the highest block in the column that is not air. */
  private int topBlock(int x, int z) {
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int y = maxY; y > minY; y--) {
      if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
        return y;
      }
    }
    return minY;
  }

  /** One drawn mob and target. Null if vanilla did not search or the case cannot be replayed. */
  private Captured attempt(
      long requestId, long worldSequence, int worldFrames, ApproximationProbe probe) {
    Kind kind = Kind.values()[rng.nextInt(Kind.values().length)];
    Mob mob = kind.type.create(level, EntitySpawnReason.COMMAND);
    if (mob == null) {
      return null;
    }
    configure(mob, kind);

    float defaultLength = Math.max((float) mob.getAttributeValue(Attributes.FOLLOW_RANGE), 16.0f);
    int reach = SearchReach.blocks(defaultLength, mob.getBbWidth());
    if (2 * reach + 1 > SIZE) {
      return null;
    }
    int[] column = startColumn(reach);
    int x = column[0];
    int z = column[1];
    double standing = standingY(x, z);
    mob.snapTo(ORIGIN + x + 0.5, standing, ORIGIN + z + 0.5, rng.nextInt(360), 0.0f);
    // Blocks only: vanilla's search does not see entities, so they must not decide a case.
    if (!level.noBlockCollision(mob, mob.getBoundingBox())) {
      return null;
    }
    mob.setOnGround(true);

    BlockPos target = target(x, z);
    int reachRange = REACH_RANGES[rng.nextInt(REACH_RANGES.length)];
    PathNavigation navigation = mob.getNavigation();
    recorded.clear();
    probe.reset();
    Searches.observe(this);
    try {
      if (rng.nextInt(4) == 0) {
        int maxPathLength = 8 + rng.nextInt((int) defaultLength - 7);
        navigation.createPath(target, reachRange, maxPathLength);
      } else {
        navigation.createPath(target, reachRange);
      }
    } finally {
      Searches.observe(null);
    }
    if (recorded.size() != 1) {
      return null;
    }

    Recorded search = recorded.get(0);
    SearchCall call = search.call();
    MobClass profile = call.profile();
    Pos start = pos(call.start());
    if (!SearchReach.fits(
        ORIGIN, ORIGIN, SIZE, start, call.search().maxPathLength(), profile.shape().width())) {
      return null;
    }
    byte[] request =
        Frames.requests(
            List.of(
                new Frames.Request(
                    requestId, profile.classId(), start, pos(call.target()), call.search())),
            ORIGIN,
            ORIGIN);
    byte[] response =
        Frames.response(
            requestId,
            search.reached(),
            search.nodes(),
            search.cost(),
            worldSequence,
            ORIGIN,
            ORIGIN);
    // The node puts the mob at its start node and models every cell as a box of its floor
    // height. Either may change the answer, and the case is exact only if neither did.
    boolean offStart = !positionIsStandIn(mob, call.start(), probe);
    boolean exact = !offStart && !probe.disagreed();
    return new Captured(
        new FixtureFile.Case(
            EntityType.getKey(mob.getType()).toString(),
            exact,
            Frames.mobClass(profile),
            request,
            response,
            worldFrames),
        search.reached(),
        offStart,
        probe.disagreed());
  }

  @Override
  public ProfileTable profiles() {
    return profiles;
  }

  @Override
  public void searched(SearchCall call, Path vanilla) {
    List<WorldOutput.Node> nodes = new ArrayList<>(vanilla.getNodeCount());
    for (int i = 0; i < vanilla.getNodeCount(); i++) {
      Node node = vanilla.getNode(i);
      nodes.add(new WorldOutput.Node(new Pos(node.x, node.y, node.z), (byte) node.type.ordinal()));
    }
    recorded.add(new Recorded(call, nodes, vanilla.canReach(), vanilla.getEndNode().g));
  }

  /**
   * Draws the inputs that vary at runtime: the difficulty, the mob's target and health (which set
   * its fall distance), its follow range and step height, its door and float flags, and a few malus
   * values, as goals change them.
   */
  private void configure(Mob mob, Kind kind) {
    level.getServer().setDifficulty(DIFFICULTIES[rng.nextInt(DIFFICULTIES.length)], true);
    if (kind == Kind.BABY_ZOMBIE) {
      mob.setBaby(true);
    }
    if (kind == Kind.SNIFFER && rng.nextBoolean()) {
      mob.setRemainingFireTicks(100);
    }
    if (rng.nextBoolean()) {
      mob.setTarget(quarry);
      mob.setHealth(1 + rng.nextInt((int) mob.getMaxHealth()));
    }
    if (rng.nextBoolean()) {
      setBase(mob, Attributes.FOLLOW_RANGE, 16 + rng.nextInt(49));
      mob.getNavigation().updatePathfinderMaxVisitedNodes();
    }
    if (rng.nextInt(5) == 0) {
      // A budget the search can run out of.
      mob.getNavigation()
          .setMaxVisitedNodesMultiplier(BUDGET_MULTIPLIERS[rng.nextInt(BUDGET_MULTIPLIERS.length)]);
    }
    if (rng.nextInt(10) < 2) {
      setBase(mob, Attributes.STEP_HEIGHT, STEP_HEIGHTS[rng.nextInt(STEP_HEIGHTS.length)]);
    }

    PathNavigation navigation = mob.getNavigation();
    if (rng.nextInt(3) == 0) {
      navigation.setCanOpenDoors(rng.nextBoolean());
    }
    if (rng.nextInt(3) == 0) {
      navigation.getNodeEvaluator().setCanPassDoors(rng.nextBoolean());
    }
    if (rng.nextInt(4) == 0) {
      navigation.setCanFloat(rng.nextBoolean());
    }
    if (rng.nextInt(4) == 0 && navigation instanceof GroundPathNavigation ground) {
      ground.setCanWalkOverFences(rng.nextBoolean());
    }
    if (rng.nextInt(10) < 3) {
      for (int i = 1 + rng.nextInt(3); i > 0; i--) {
        mob.setPathfindingMalus(
            TYPES[rng.nextInt(TYPES.length)], MALUS_VALUES[rng.nextInt(MALUS_VALUES.length)]);
      }
    }
  }

  private static void setBase(Mob mob, Holder<Attribute> attribute, double value) {
    AttributeInstance instance = mob.getAttribute(attribute);
    if (instance != null) {
      instance.setBaseValue(value);
    }
  }

  /**
   * A column at least {@code reach} from every edge. Some starts are inside a room or pen, so the
   * mob has to find the door or gate.
   */
  private int[] startColumn(int reach) {
    if (rng.nextInt(5) < 2) {
      List<Terrain.Enclosure> reachable = new ArrayList<>();
      for (Terrain.Enclosure pen : enclosures) {
        if (pen.minX() + 1 >= reach
            && pen.minX() + pen.width() - 1 <= SIZE - reach
            && pen.minZ() + 1 >= reach
            && pen.minZ() + pen.depth() - 1 <= SIZE - reach) {
          reachable.add(pen);
        }
      }
      if (!reachable.isEmpty()) {
        Terrain.Enclosure pen = reachable.get(rng.nextInt(reachable.size()));
        return new int[] {
          pen.minX() + 1 + rng.nextInt(pen.width() - 2),
          pen.minZ() + 1 + rng.nextInt(pen.depth() - 2)
        };
      }
    }
    return new int[] {reach + rng.nextInt(SIZE - 2 * reach), reach + rng.nextInt(SIZE - 2 * reach)};
  }

  /** Half the targets are near the mob, the rest anywhere in the area. */
  private BlockPos target(int x, int z) {
    int tx;
    int tz;
    if (rng.nextBoolean()) {
      tx = Math.clamp(x - 12 + rng.nextInt(25), 0, SIZE - 1);
      tz = Math.clamp(z - 12 + rng.nextInt(25), 0, SIZE - 1);
    } else {
      tx = rng.nextInt(SIZE);
      tz = rng.nextInt(SIZE);
    }
    int ty = (int) Math.floor(standingY(tx, tz)) - 1 + rng.nextInt(4);
    return new BlockPos(ORIGIN + tx, ty, ORIGIN + tz);
  }

  /** Height a mob stands at in the column: the top of its highest collision shape. */
  private double standingY(int x, int z) {
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int y = maxY; y >= minY; y--) {
      pos.set(ORIGIN + x, y, ORIGIN + z);
      VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
      if (!shape.isEmpty()) {
        return y + shape.max(Direction.Axis.Y);
      }
    }
    return minY;
  }

  /**
   * Whether the node's stand-in for the mob's position, the bottom centre of the start node, gives
   * the search what the mob's position did. Vanilla reads the position for a sweep, which the
   * stand-in matches only at the bottom centre, and for the block the mob is in, which it matches
   * whenever the mob is in the start block.
   */
  private static boolean positionIsStandIn(Mob mob, BlockPos start, ApproximationProbe probe) {
    boolean atBottomCentre =
        mob.getX() == start.getX() + 0.5
            && mob.getY() == start.getY()
            && mob.getZ() == start.getZ() + 0.5;
    return atBottomCentre || (mob.blockPosition().equals(start) && !probe.swept());
  }

  /**
   * Vanilla reads per-block types through a cache that a block change clears only in a ticking
   * chunk. A freshly loaded chunk may not be ticking yet, so every cell the terrain wrote is
   * cleared here, and vanilla reads the blocks as they are.
   */
  private void forgetCachedTypes() {
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int x = 0; x < SIZE; x++) {
      for (int z = 0; z < SIZE; z++) {
        for (int y = minY; y < minY + Terrain.BAND; y++) {
          level.getPathTypeCache().invalidate(pos.set(ORIGIN + x, y, ORIGIN + z));
        }
      }
    }
  }

  /** A source that does not know about sections, so every block is read. */
  private record BlockByBlock(WorldSource inner) implements WorldSource {

    @Override
    public MobClass mobClass(short classId) {
      return inner.mobClass(classId);
    }

    @Override
    public int minY() {
      return inner.minY();
    }

    @Override
    public int height() {
      return inner.height();
    }

    @Override
    public byte pathTypeAt(int x, int y, int z) {
      return inner.pathTypeAt(x, y, z);
    }

    @Override
    public int floorAt(int x, int y, int z) {
      return inner.floorAt(x, y, z);
    }
  }

  private static Pos pos(BlockPos pos) {
    return new Pos(pos.getX(), pos.getY(), pos.getZ());
  }
}
