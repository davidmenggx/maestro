package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.config.MaestroConfig;
import io.github.davidmenggx.maestro.controller.core.Controller;
import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.obs.OtelMetrics;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This server's link to its compute fleet: the controller and everything it runs on, fed by the
 * game's events. At most one is active, for the overworld. Used on the server thread only.
 *
 * <p>The fleet is static: each configured node holds one region, laid out by {@link RegionLayout}.
 */
public final class Fleet implements AutoCloseable {

  /** Serialization is the only offloaded work, so one worker is enough. */
  private static final int OFFLOAD_THREADS = 1;

  /** Ticks between the controller's self-checks. */
  private static final int SELF_CHECK_TICKS = 100;

  /** Distinct self-check findings logged at most, so a recurring one cannot flood the log. */
  private static final int MAX_LOGGED_FINDINGS = 256;

  private static final Logger LOG = LoggerFactory.getLogger("maestro");

  private static Fleet active;

  private final ServerLevel level;
  private final RegionMap regions;
  private final ServerClock clock;
  private final Thread serverThread;
  private final NettyTransport transport;
  private final PoolOffload offload;
  private final PathOffload paths;
  private final Controller controller;
  private final Set<String> logged = new LinkedHashSet<>();

  private Fleet(
      ServerLevel level,
      RegionMap regions,
      ServerClock clock,
      NettyTransport transport,
      PoolOffload offload,
      PathOffload paths,
      Controller controller) {
    this.level = level;
    this.regions = regions;
    this.clock = clock;
    this.serverThread = Thread.currentThread();
    this.transport = transport;
    this.offload = offload;
    this.paths = paths;
    this.controller = controller;
  }

  /** The active fleet, or null if none. */
  public static Fleet active() {
    return active;
  }

  /**
   * Starts offloading the overworld's searches to the configured nodes, on the server thread,
   * before any of its chunks or entities load.
   */
  public static Fleet start(ServerLevel overworld, MaestroConfig config, ServerClock clock) {
    if (active != null) {
      throw new IllegalStateException("a fleet is already active");
    }
    Map<NodeId, MaestroConfig.NodeAddress> nodes = new LinkedHashMap<>();
    for (int i = 0; i < config.nodes().size(); i++) {
      nodes.put(NodeId.of(i), config.nodes().get(i));
    }
    RegionMap regions = RegionLayout.spiral(nodes.keySet().stream().toList());

    SecureRandom entropy = new SecureRandom();
    long run = 0;
    while (run == 0) {
      run = entropy.nextLong();
    }
    OtelMetrics metrics = new OtelMetrics();
    NettyTransport transport = new NettyTransport(nodes, NettyTransport.MAX_PENDING_BYTES);
    PoolOffload offload = new PoolOffload(OFFLOAD_THREADS);
    ControllerEnv env =
        new ControllerEnv(
            clock, SeededRng.of(entropy.nextLong()), offload, transport, metrics, run);
    ProfileTable profiles = new ProfileTable();
    PathOffload paths =
        new PathOffload(overworld, regions, clock, metrics, profiles, config.offload());
    Controller controller =
        new Controller(
            env, new LevelWorldSource(overworld, profiles), paths, regions, config.nodeCapacity());
    paths.bind(controller);
    HeldPaths.clock(clock);
    Searches.intercept(paths);

    active = new Fleet(overworld, regions, clock, transport, offload, paths, controller);
    for (Region region : regions.regions()) {
      LOG.info(
          "Maestro: region {} at x {}..{}, z {}..{} is held by {} at {}",
          region.id(),
          region.minX(),
          region.minX() + region.size() - 1,
          region.minZ(),
          region.minZ() + region.size() - 1,
          regions.holders(region.id()),
          config.nodes().get(regions.holders(region.id()).get(0).id()).host());
    }
    LOG.info(
        "Maestro: offloading to {} nodes, offload={}, run {}",
        nodes.size(),
        config.offload(),
        Long.toHexString(run));
    return active;
  }

  /** A server tick starts: what arrived meanwhile is handed over, then the tick opens. */
  public void tickStarted() {
    handOver();
    controller.accept(new WorldEvent.TickBegan(clock.tick()));
  }

  /**
   * A server tick's work ends: what arrived during it is handed over, mobs that stopped waiting for
   * their answers cancel them, then the tick closes.
   */
  public void tickEnding() {
    handOver();
    paths.tickEnding();
    controller.accept(new WorldEvent.TickEnded(clock.tick()));
    if (clock.tick() % SELF_CHECK_TICKS == 0) {
      selfCheck();
    }
  }

  private void handOver() {
    transport.drain();
    offload.drain();
  }

  private void selfCheck() {
    for (String finding : controller.selfCheck()) {
      if (logged.size() < MAX_LOGGED_FINDINGS && logged.add(finding)) {
        LOG.warn("Maestro controller self-check: {}", finding);
      }
    }
  }

  public void chunkLoaded(ServerLevel from, LevelChunk chunk) {
    if (from == level) {
      controller.accept(
          new WorldEvent.ChunkLoaded(clock.tick(), chunk.getPos().x(), chunk.getPos().z()));
    }
  }

  public void chunkUnloaded(ServerLevel from, LevelChunk chunk) {
    if (from == level) {
      controller.accept(
          new WorldEvent.ChunkUnloaded(clock.tick(), chunk.getPos().x(), chunk.getPos().z()));
    }
  }

  public void entityLoaded(Entity entity, ServerLevel from) {
    if (from == level && entity instanceof Mob) {
      controller.accept(
          new WorldEvent.MobSpawned(clock.tick(), entity.getId(), pos(entity.blockPosition())));
    }
  }

  /** The mob's requests end with it. It is forgotten first, so their drops are not counted. */
  public void entityUnloaded(Entity entity, ServerLevel from) {
    if (from == level && entity instanceof Mob) {
      paths.mobRemoved(entity.getId());
      controller.accept(new WorldEvent.MobRemoved(clock.tick(), entity.getId()));
    }
  }

  /**
   * Whether a block change in the chunk, made now, is reported: the chunk is in a region, which
   * holds whole chunks, and the change is made on the server thread.
   */
  public boolean watches(LevelChunk chunk) {
    return chunk.getLevel() == level
        && Thread.currentThread() == serverThread
        && regions.regionAt(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ()) != null;
  }

  /** The block's cell as the node holds it. */
  public static int cell(LevelChunk chunk, BlockPos pos) {
    return Cells.pack(BlockCells.floor(chunk, pos), BlockCells.pathType(chunk, pos));
  }

  /** A block in a watched chunk changed state, and was the cell {@code before}. */
  public void blockChanged(LevelChunk chunk, BlockPos pos, int before) {
    int after = cell(chunk, pos);
    if (after != before) {
      controller.accept(
          new WorldEvent.BlockChanged(
              clock.tick(), pos(pos), Cells.typeOf(after), Cells.floorOf(after)));
    }
  }

  /** Stops offloading. Searches are vanilla's from here on. */
  @Override
  public void close() {
    if (active == this) {
      active = null;
    }
    Searches.intercept(null);
    selfCheck();
    transport.close();
    offload.close();
    LOG.info("Maestro fleet stopped: {}", paths.summary());
  }

  private static Pos pos(BlockPos pos) {
    return new Pos(pos.getX(), pos.getY(), pos.getZ());
  }
}
