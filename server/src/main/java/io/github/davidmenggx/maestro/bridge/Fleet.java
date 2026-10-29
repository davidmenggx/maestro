package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.config.MaestroConfig;
import io.github.davidmenggx.maestro.controller.core.Controller;
import io.github.davidmenggx.maestro.controller.core.Dispatch;
import io.github.davidmenggx.maestro.controller.core.Placement;
import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.world.Cells;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import io.github.davidmenggx.maestro.obs.OtelMetrics;
import io.github.davidmenggx.maestro.obs.OtelTracing;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This server's link to its compute fleet: the controller and everything it runs on, fed by the
 * game's events. At most one is active, for the overworld. Used on the server thread only.
 *
 * <p>The fleet is static: the configured nodes, which the controller places on the square of {@link
 * RegionLayout} cells the config names, keeping some as spares.
 */
public final class Fleet implements AutoCloseable {

  /** Serialization is the only offloaded work, so one worker is enough. */
  private static final int OFFLOAD_THREADS = 1;

  /** Ticks between the controller's self-checks. */
  private static final int SELF_CHECK_TICKS = 100;

  /** Distinct self-check findings logged at most, so a recurring one cannot flood the log. */
  private static final int MAX_LOGGED_FINDINGS = 256;

  /** Ticks between reports of where the players stand. */
  private static final int PLAYER_TICKS = 20;

  private static final Logger LOG = LoggerFactory.getLogger("maestro");

  private static Fleet active;

  private final ServerLevel level;
  private final RegionLayout layout;
  private final List<MaestroConfig.NodeAddress> addresses;
  private final ServerClock clock;
  private final Thread serverThread;
  private final NettyTransport transport;
  private final PoolOffload offload;
  private final PathOffload paths;
  private final Controller controller;
  private final Set<String> logged = new LinkedHashSet<>();

  /** Each node's state as last logged. */
  private final Map<NodeId, String> states = new LinkedHashMap<>();

  /** The players last reported to the controller, by entity id. */
  private Set<Integer> players = new LinkedHashSet<>();

  /** {@link Controller#regionsVersion} when the regions were last logged. */
  private int loggedRegions = -1;

  /** Also sees every event the game sends the controller, or null. */
  private WorldInput watcher;

  private Fleet(
      ServerLevel level,
      RegionLayout layout,
      List<MaestroConfig.NodeAddress> addresses,
      List<NodeId> fleet,
      ServerClock clock,
      NettyTransport transport,
      PoolOffload offload,
      PathOffload paths,
      Controller controller) {
    this.level = level;
    this.layout = layout;
    this.addresses = List.copyOf(addresses);
    this.clock = clock;
    this.serverThread = Thread.currentThread();
    this.transport = transport;
    this.offload = offload;
    this.paths = paths;
    this.controller = controller;
    for (NodeId node : fleet) {
      states.put(node, state(node));
    }
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
    List<NodeId> fleet = List.copyOf(nodes.keySet());
    RegionLayout layout = new RegionLayout(config.areaCells());

    SecureRandom entropy = new SecureRandom();
    long run = 0;
    while (run == 0) {
      run = entropy.nextLong();
    }
    OtelMetrics metrics = new OtelMetrics();
    NettyTransport transport =
        new NettyTransport(nodes, NettyTransport.MAX_PENDING_BYTES, clock::nowNanos);
    PoolOffload offload = new PoolOffload(OFFLOAD_THREADS);
    ControllerEnv env =
        new ControllerEnv(
            clock,
            SeededRng.of(entropy.nextLong()),
            offload,
            transport,
            metrics,
            new OtelTracing(config.subMode().name().toLowerCase(Locale.ROOT)),
            run);
    ProfileTable profiles = new ProfileTable();
    PathOffload paths =
        new PathOffload(overworld, layout, clock, metrics, profiles, config.offload());
    Controller controller =
        new Controller(
            env,
            new LevelWorldSource(overworld, profiles),
            paths,
            fleet,
            new Controller.Settings(
                layout,
                config.spares(),
                config.nodeCapacity(),
                config.subMode() == MaestroConfig.SubMode.STREAM ? Dispatch.STREAM : Dispatch.BATCH,
                Placement.Policy.DEFAULT,
                config.traceSampleRate()));
    active =
        new Fleet(
            overworld, layout, config.nodes(), fleet, clock, transport, offload, paths, controller);
    paths.bind(active::accept);
    HeldPaths.clock(clock);
    Searches.intercept(paths);
    active.logRegions();
    LOG.info(
        "Maestro: offloading to {} nodes, offload={}, subMode={}, run {}",
        nodes.size(),
        config.offload(),
        config.subMode(),
        Long.toHexString(run));
    return active;
  }

  /**
   * A server tick starts: what arrived meanwhile is handed over, then the tick opens. Where the
   * players stand is reported every {@link #PLAYER_TICKS}.
   */
  public void tickStarted() {
    handOver();
    accept(new WorldEvent.TickBegan(clock.tick()));
    if (controller.regionsVersion() != loggedRegions) {
      logRegions();
    }
    if (clock.tick() % PLAYER_TICKS == 0) {
      reportPlayers();
    }
  }

  /** Each player's position, and each player gone since the last report. */
  private void reportPlayers() {
    Set<Integer> seen = new LinkedHashSet<>();
    for (ServerPlayer player : level.players()) {
      if (!player.isSpectator()) {
        seen.add(player.getId());
        accept(
            new WorldEvent.PlayerMoved(clock.tick(), player.getId(), pos(player.blockPosition())));
      }
    }
    for (int gone : players) {
      if (!seen.contains(gone)) {
        accept(new WorldEvent.PlayerLeft(clock.tick(), gone));
      }
    }
    players = seen;
  }

  /** Logs every region with its holders, and the spares with what became of the requests so far. */
  private void logRegions() {
    loggedRegions = controller.regionsVersion();
    Regions regions = controller.regions();
    List<NodeId> spares = new ArrayList<>(states.keySet());
    for (Region region : regions.regions()) {
      List<String> holders = new ArrayList<>();
      for (NodeId node : regions.holders(region.id())) {
        holders.add(node + " at " + addresses.get(node.id()).host());
        spares.remove(node);
      }
      LOG.info(
          "Maestro: region {} at x {}..{}, z {}..{} is held by {}",
          region.id(),
          region.minX(),
          region.minX() + region.sizeX() - 1,
          region.minZ(),
          region.minZ() + region.sizeZ() - 1,
          holders);
    }
    LOG.info("Maestro: spare nodes {}; so far {}", spares, paths.summary());
  }

  /**
   * A server tick's work ends: arrivals are handed over, mobs that stopped waiting cancel, then the
   * tick closes.
   */
  public void tickEnding() {
    handOver();
    paths.tickEnding();
    accept(new WorldEvent.TickEnded(clock.tick()));
    logChangedStates();
    if (clock.tick() % SELF_CHECK_TICKS == 0) {
      selfCheck();
    }
  }

  /** Logs each node whose link or service changed, with what became of the requests so far. */
  private void logChangedStates() {
    for (Map.Entry<NodeId, String> entry : states.entrySet()) {
      String now = state(entry.getKey());
      if (!now.equals(entry.getValue())) {
        entry.setValue(now);
        LOG.info("Maestro: {} is {}; so far {}", entry.getKey(), now, paths.summary());
      }
    }
  }

  private String state(NodeId node) {
    if (!controller.linked(node)) {
      return "not linked";
    }
    return controller.inService(node) ? "linked and in service" : "linked but out of service";
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
      accept(new WorldEvent.ChunkLoaded(clock.tick(), chunk.getPos().x(), chunk.getPos().z()));
    }
  }

  public void chunkUnloaded(ServerLevel from, LevelChunk chunk) {
    if (from == level) {
      accept(new WorldEvent.ChunkUnloaded(clock.tick(), chunk.getPos().x(), chunk.getPos().z()));
    }
  }

  public void entityLoaded(Entity entity, ServerLevel from) {
    if (from == level && entity instanceof Mob) {
      accept(new WorldEvent.MobSpawned(clock.tick(), entity.getId(), pos(entity.blockPosition())));
    }
  }

  /** The mob's requests end with it. It is forgotten first, so their drops are not counted. */
  public void entityUnloaded(Entity entity, ServerLevel from) {
    if (from == level && entity instanceof Mob) {
      paths.mobRemoved(entity.getId());
      accept(new WorldEvent.MobRemoved(clock.tick(), entity.getId()));
    }
  }

  /**
   * Whether a block change in the chunk, made now, is reported: the chunk is in a region's area,
   * which holds whole chunks, and the change is made on the server thread.
   */
  public boolean watches(LevelChunk chunk) {
    return chunk.getLevel() == level
        && Thread.currentThread() == serverThread
        && layout.reaches(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
  }

  /** The block's cell as the node holds it. */
  public static int cell(LevelChunk chunk, BlockPos pos) {
    return Cells.pack(BlockCells.floor(chunk, pos), BlockCells.pathType(chunk, pos));
  }

  /** A block in a watched chunk changed state, and was the cell {@code before}. */
  public void blockChanged(LevelChunk chunk, BlockPos pos, int before) {
    int after = cell(chunk, pos);
    if (after != before) {
      accept(
          new WorldEvent.BlockChanged(
              clock.tick(), pos(pos), Cells.typeOf(after), Cells.floorOf(after)));
    }
  }

  /** Hands {@code next} every event the game sends the controller from now on. Null stops it. */
  void watch(WorldInput next) {
    watcher = next;
  }

  private void accept(WorldEvent event) {
    if (watcher != null) {
      watcher.accept(event);
    }
    controller.accept(event);
  }

  /** What became of the offloaded requests so far. */
  public OffloadStats stats() {
    return paths.stats();
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
