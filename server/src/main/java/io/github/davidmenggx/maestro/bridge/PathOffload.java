package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.config.MaestroConfig.Offload;
import io.github.davidmenggx.maestro.controller.core.RequestTracker;
import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.Metrics;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers the searches Maestro offloads, and applies the controller's outcomes to the mobs that
 * asked. Used on the server thread only.
 *
 * <p>An offloaded call sends a request. While it waits, the call returns the mob's live path if it
 * is following one, so a mob chasing a moving target keeps moving, or else a held path at its own
 * block ({@link HeldPaths}). The answer replaces either when it arrives, if the mob still follows
 * it. A request the controller refuses at once is searched by vanilla instead. One dropped later
 * ends a held path, and the mob's goal asks again. A mob that no longer follows its placeholder
 * when the tick's work ends cancels its request. Judging this at the end of the tick, not when the
 * navigation drops the placeholder, lets a goal that replaces another stop the navigation and hand
 * it the same path back, as the goal selector does when the new goal's {@code canUse} searched.
 *
 * <p>In shadow mode vanilla answers every call, and each answer from the fleet is compared with
 * vanilla's.
 */
public final class PathOffload implements Searches.Interceptor, WorldOutput {

  /** Mixed into {@code PathNavigation}. */
  public interface NavigationAccess {

    double maestro$speedModifier();

    /** Sets the target a later call must name to reuse the path the navigation follows. */
    void maestro$setTargetPos(BlockPos target);
  }

  /** What the offload reads and changes on one mob's navigation. */
  interface Navigator {

    /** The path the navigation follows, or null if none. */
    Path path();

    /** Drops the path the navigation follows. */
    void stop();

    /** Follows {@code path} toward {@code target}, at the speed the navigation moves at now. */
    void follow(Path path, BlockPos target);

    /** Where the mob stands. */
    Vec3 position();
  }

  /** A mob's navigation. */
  private record MobNavigator(Mob mob) implements Navigator {

    @Override
    public Path path() {
      return mob.getNavigation().getPath();
    }

    @Override
    public void stop() {
      mob.getNavigation().stop();
    }

    @Override
    public void follow(Path path, BlockPos target) {
      PathNavigation navigation = mob.getNavigation();
      NavigationAccess access = (NavigationAccess) navigation;
      access.maestro$setTargetPos(target);
      navigation.moveTo(path, access.maestro$speedModifier());
    }

    @Override
    public Vec3 position() {
      return mob.position();
    }
  }

  /** How long an answer the mob did not take may serve a later call for the same search. */
  static final int FRESH_TICKS = RequestTracker.MAX_TICKS_LATE;

  /** Nodes at the front of an answer the mob may have walked past while it waited. */
  static final int MAX_SKIPPED_NODES = 4;

  /** Shadow mismatches logged at most, each with both paths, so a failing run can be read. */
  static final int MAX_LOGGED_MISMATCHES = 20;

  private static final int NO_MOB = Integer.MIN_VALUE;

  private static final Logger LOG = LoggerFactory.getLogger("maestro");

  private static final PathType[] TYPES = PathType.values();

  /** Vanilla's path as its search returned it, before the navigation trimmed or advanced it. */
  private record VanillaPath(List<BlockPos> nodes, boolean reached) {

    static VanillaPath of(Path path) {
      if (path == null) {
        return null;
      }
      List<BlockPos> nodes = new ArrayList<>(path.getNodeCount());
      for (int i = 0; i < path.getNodeCount(); i++) {
        nodes.add(path.getNodePos(i));
      }
      return new VanillaPath(nodes, path.canReach());
    }
  }

  /**
   * A request waiting for its answer. {@code placeholder} is the path handed out meanwhile, and
   * {@code vanilla} is vanilla's answer in shadow mode. Each is null otherwise.
   */
  private static final class Pending {

    final BlockPos start;
    final BlockPos target;
    final VanillaPath vanilla;
    Path placeholder;

    Pending(BlockPos start, BlockPos target, VanillaPath vanilla) {
      this.start = start;
      this.target = target;
      this.vanilla = vanilla;
    }

    boolean matches(BlockPos otherStart, BlockPos otherTarget) {
      return start.equals(otherStart) && target.equals(otherTarget);
    }
  }

  /** An answer that arrived after its mob moved on, and what it was asked for. */
  private record Answer(BlockPos start, BlockPos target, PathResult result) {}

  private static final class MobState {
    Pending pending;
    Answer latest;
  }

  /** What became of the requests, for the summary. */
  private enum Outcome {
    REQUESTED,
    REFUSED,
    ANSWERED,
    APPLIED,
    MOVED_ON,
    REUSED,
    DROPPED,
    CANCELED,
    SHADOW_MATCHED,
    SHADOW_DIFFERED
  }

  private final Level level;
  private final RegionMap regions;
  private final IntFunction<Navigator> navigators;
  private final Clock clock;
  private final Metrics metrics;
  private final ProfileTable profiles;
  private final Offload mode;
  private final Int2ObjectMap<MobState> mobs = new Int2ObjectOpenHashMap<>();

  /** Mobs with a request waiting in {@link Offload#ON} mode, in the order they asked. */
  private final IntLinkedOpenHashSet waiting = new IntLinkedOpenHashSet();

  private final Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);

  /** Answers by ticks from request to answer, the last bucket holding any later. */
  private final long[] ticksLate = new long[RequestTracker.MAX_TICKS_LATE + 1];

  private WorldInput controller;

  /** The mob whose request the controller is admitting now, or {@link #NO_MOB}. */
  private int admitting = NO_MOB;

  private boolean refused;

  /** Offloads the searches of mobs in {@code level} that start in one of {@code regions}. */
  public PathOffload(
      ServerLevel level,
      RegionMap regions,
      Clock clock,
      Metrics metrics,
      ProfileTable profiles,
      Offload mode) {
    this(
        level,
        regions,
        id -> level.getEntity(id) instanceof Mob mob ? new MobNavigator(mob) : null,
        clock,
        metrics,
        profiles,
        mode);
  }

  /** {@code navigators} finds a mob's navigation by its id, or null if the mob is gone. */
  PathOffload(
      Level level,
      RegionMap regions,
      IntFunction<Navigator> navigators,
      Clock clock,
      Metrics metrics,
      ProfileTable profiles,
      Offload mode) {
    this.level = level;
    this.regions = regions;
    this.navigators = navigators;
    this.clock = clock;
    this.metrics = metrics;
    this.profiles = profiles;
    this.mode = mode;
  }

  /** Sends requests to {@code controller} from now on. Until then no call is taken. */
  public void bind(WorldInput controller) {
    this.controller = controller;
  }

  @Override
  public ProfileTable profiles() {
    return profiles;
  }

  /**
   * A mob standing outside every region has its search refused anyway, so it is not read and sent.
   * A search starts at most a block from the mob, and a start that close to a region's edge cannot
   * fit its reach, so this refuses nothing the controller would take.
   */
  @Override
  public boolean wants(Mob mob) {
    return controller != null
        && Searches.offloadable()
        && mob.level() == level
        && regions.regionAt(mob.getBlockX(), mob.getBlockZ()) != null;
  }

  @Override
  public Path search(Mob mob, SearchCall call, Supplier<Path> vanilla) {
    return search(mob.getId(), new MobNavigator(mob), call, vanilla);
  }

  Path search(int mobId, Navigator navigator, SearchCall call, Supplier<Path> vanilla) {
    MobState state = mobs.computeIfAbsent(mobId, id -> new MobState());
    BlockPos start = call.start();
    BlockPos target = call.target();
    if (mode == Offload.ON) {
      Answer latest = state.latest;
      state.latest = null;
      if (latest != null
          && latest.start().equals(start)
          && latest.target().equals(target)
          && clock.tick() - latest.result().issuedTick() <= FRESH_TICKS) {
        record(Outcome.REUSED);
        return toPath(latest.result());
      }
    }
    Pending pending = state.pending;
    if (pending != null && pending.matches(start, target)) {
      // Asked again for the answer it already waits for.
      return mode == Offload.SHADOW ? vanilla.get() : placeholder(navigator, pending);
    }

    if (mode == Offload.SHADOW) {
      Path path = vanilla.get();
      if (request(mobId, navigator, state, call)) {
        state.pending = new Pending(start, target, VanillaPath.of(path));
      }
      return path;
    }
    if (!request(mobId, navigator, state, call)) {
      return vanilla.get();
    }
    Pending sent = new Pending(start, target, null);
    state.pending = sent;
    waiting.add(mobId);
    return placeholder(navigator, sent);
  }

  /**
   * Sends the call's request, which replaces any the mob has waiting. Returns whether the
   * controller took it. One it refuses at once is reported dropped during the call.
   */
  private boolean request(int mobId, Navigator navigator, MobState state, SearchCall call) {
    record(Outcome.REQUESTED);
    Pending replaced = state.pending;
    state.pending = null;
    waiting.remove(mobId);
    admitting = mobId;
    refused = false;
    try {
      controller.accept(
          new WorldEvent.PathRequested(
              clock.tick(),
              mobId,
              call.profile().classId(),
              pos(call.start()),
              pos(call.target()),
              call.search()));
    } finally {
      admitting = NO_MOB;
    }
    if (refused) {
      record(Outcome.REFUSED);
      if (replaced != null) {
        endHold(navigator);
      }
      return false;
    }
    return true;
  }

  /**
   * What the mob follows while it waits: its live path if it has one, or else a held path at the
   * start node. A held path it already follows there keeps being held.
   */
  private static Path placeholder(Navigator navigator, Pending pending) {
    Path current = navigator.path();
    if (current != null && !current.isDone()) {
      if (!HeldPaths.isHeld(current)) {
        pending.placeholder = current;
        return current;
      }
      if (current.getNodeCount() == 1 && current.getNode(0).asBlockPos().equals(pending.start)) {
        HeldPaths.renew(current);
        pending.placeholder = current;
        return current;
      }
    }
    pending.placeholder = HeldPaths.hold(pending.start, pending.target);
    return pending.placeholder;
  }

  /**
   * Whether a mob following {@code current} still waits for {@code pending}: it follows the
   * placeholder, or is held, which it only ever is while a request is out.
   */
  private static boolean waitsFor(Pending pending, Path current) {
    return current != null && (current == pending.placeholder || HeldPaths.isHeld(current));
  }

  /**
   * The tick's work is done. Each mob that no longer follows its placeholder cancels its request,
   * since its answer would not be used, and a mob that left cancels it too.
   */
  public void tickEnding() {
    if (waiting.isEmpty()) {
      return;
    }
    List<Integer> displaced = new ArrayList<>();
    for (int mobId : waiting) {
      MobState state = mobs.get(mobId);
      Navigator navigator = navigators.apply(mobId);
      if (navigator == null || !waitsFor(state.pending, navigator.path())) {
        displaced.add(mobId);
      }
    }
    for (int mobId : displaced) {
      waiting.remove(mobId);
      mobs.get(mobId).pending = null;
      record(Outcome.CANCELED);
      controller.accept(new WorldEvent.PathCanceled(clock.tick(), mobId));
    }
  }

  @Override
  public void pathComputed(PathResult result) {
    MobState state = mobs.get(result.mobId());
    Pending pending = state == null ? null : state.pending;
    if (pending == null) {
      return;
    }
    state.pending = null;
    waiting.remove(result.mobId());
    record(Outcome.ANSWERED);
    long late = clock.tick() - result.issuedTick();
    ticksLate[(int) Math.clamp(late, 0, ticksLate.length - 1)]++;
    if (mode == Offload.SHADOW) {
      if (sameNodes(pending.vanilla, result)) {
        record(Outcome.SHADOW_MATCHED);
      } else {
        record(Outcome.SHADOW_DIFFERED);
        if (outcomes.get(Outcome.SHADOW_DIFFERED) <= MAX_LOGGED_MISMATCHES) {
          LOG.warn(
              "Maestro shadow mismatch: mob {} from {} to {}: vanilla {}, node {} (reached {})",
              result.mobId(),
              pending.start.toShortString(),
              pending.target.toShortString(),
              pending.vanilla,
              result.nodes().stream().map(node -> node.pos().toString()).toList(),
              result.reached());
        }
      }
      return;
    }

    Navigator navigator = navigators.apply(result.mobId());
    if (navigator == null) {
      return;
    }
    Path current = navigator.path();
    if (!waitsFor(pending, current)) {
      // The mob moved on. A later call for the same search may still use the answer, once.
      state.latest = new Answer(pending.start, pending.target, result);
      record(Outcome.MOVED_ON);
      return;
    }
    record(Outcome.APPLIED);
    Path path = toPath(result);
    if (path == null) {
      // Vanilla's search found nothing either, and the navigation would stop.
      navigator.stop();
      return;
    }
    skipPassed(path, navigator.position());
    if (HeldPaths.isHeld(current)) {
      // The navigation keeps a path it is given whose nodes it already follows, so a one-node
      // answer at the held block would otherwise stay held.
      HeldPaths.release(current);
      navigator.stop();
    }
    navigator.follow(path, pending.target);
  }

  @Override
  public void pathDropped(int mobId, DropReason reason) {
    if (mobId == admitting) {
      // The request being admitted replaces the one superseded, and its own refusal means
      // vanilla searches instead.
      if (reason != DropReason.SUPERSEDED) {
        refused = true;
      }
      return;
    }
    MobState state = mobs.get(mobId);
    if (state == null || state.pending == null) {
      return;
    }
    state.pending = null;
    waiting.remove(mobId);
    record(Outcome.DROPPED);
    metrics.count("path.offload.dropped", 1, "reason", reason.name());
    Navigator navigator = navigators.apply(mobId);
    if (navigator != null) {
      endHold(navigator);
    }
  }

  /** The mob left the world. */
  public void mobRemoved(int mobId) {
    mobs.remove(mobId);
    waiting.remove(mobId);
  }

  /** A held path the mob follows ends, so its goal finishes and asks again later. */
  private static void endHold(Navigator navigator) {
    Path current = navigator.path();
    if (current != null && HeldPaths.isHeld(current)) {
      HeldPaths.release(current);
    }
  }

  /**
   * The answer started where the mob stood when it asked, and a mob that followed its live path
   * meanwhile is a node or two along. It follows the answer from the node of the first few it is
   * nearest.
   */
  private static void skipPassed(Path path, Vec3 at) {
    int last = Math.min(path.getNodeCount() - 1, MAX_SKIPPED_NODES);
    int nearest = 0;
    double best = Double.MAX_VALUE;
    for (int i = 0; i <= last; i++) {
      double distance = at.distanceToSqr(Vec3.atBottomCenterOf(path.getNode(i).asBlockPos()));
      if (distance < best) {
        best = distance;
        nearest = i;
      }
    }
    for (int i = 0; i < nearest; i++) {
      path.advance();
    }
  }

  /** The answer as vanilla's search would have returned it: null when it found no path. */
  static Path toPath(PathResult result) {
    if (result.nodes().isEmpty()) {
      return null;
    }
    // `Node` alone names the controller's node here.
    List<net.minecraft.world.level.pathfinder.Node> nodes = new ArrayList<>(result.nodes().size());
    for (WorldOutput.Node answered : result.nodes()) {
      Pos pos = answered.pos();
      net.minecraft.world.level.pathfinder.Node node =
          new net.minecraft.world.level.pathfinder.Node(pos.x(), pos.y(), pos.z());
      node.type = TYPES[answered.pathType()];
      nodes.add(node);
    }
    Pos target = result.target();
    return new Path(nodes, new BlockPos(target.x(), target.y(), target.z()), result.reached());
  }

  private static boolean sameNodes(VanillaPath vanilla, PathResult result) {
    if (vanilla == null) {
      return result.nodes().isEmpty();
    }
    if (vanilla.reached() != result.reached() || vanilla.nodes().size() != result.nodes().size()) {
      return false;
    }
    for (int i = 0; i < vanilla.nodes().size(); i++) {
      if (!pos(vanilla.nodes().get(i)).equals(result.nodes().get(i).pos())) {
        return false;
      }
    }
    return true;
  }

  private void record(Outcome outcome) {
    outcomes.merge(outcome, 1L, Long::sum);
    metrics.count("path.offload", 1, "outcome", outcome.name());
  }

  /** How many requests ended in {@code outcome} so far. */
  long outcomes(String outcome) {
    return outcomes.getOrDefault(Outcome.valueOf(outcome), 0L);
  }

  /** Counts of what became of the requests so far, and how late their answers were. */
  public String summary() {
    StringBuilder text = new StringBuilder();
    for (Outcome outcome : Outcome.values()) {
      text.append(outcome.name().toLowerCase())
          .append('=')
          .append(outcomes.getOrDefault(outcome, 0L))
          .append(' ');
    }
    text.append("ticks_late=");
    String separator = "";
    for (int ticks = 0; ticks < ticksLate.length; ticks++) {
      if (ticksLate[ticks] > 0) {
        text.append(separator).append(ticks).append(':').append(ticksLate[ticks]);
        separator = ",";
      }
    }
    return text.toString();
  }

  private static Pos pos(BlockPos pos) {
    return new Pos(pos.getX(), pos.getY(), pos.getZ());
  }
}
