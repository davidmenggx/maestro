package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.config.MaestroConfig.Offload;
import io.github.davidmenggx.maestro.controller.core.RequestTracker;
import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.env.Metrics;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.ProfileTable;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldInput;
import io.github.davidmenggx.maestro.controller.world.WorldOutput;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

/**
 * Answers the searches Maestro offloads, and applies the controller's outcomes to the mobs that
 * asked. Used on the server thread only.
 *
 * <p>An offloaded call sends a request. While it waits, the call returns the mob's live path if it
 * is following one, so a mob chasing a moving target keeps moving, or else a held path at its own
 * block ({@link HeldPaths}). The answer replaces either when it arrives, if the mob still follows
 * it. A request the controller refuses at once is searched by vanilla instead. One dropped later
 * ends a held path, and the mob's goal asks again. A mob that stops following the placeholder
 * cancels its request.
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

  /** How long an answer the mob did not take may serve a later call for the same search. */
  static final int FRESH_TICKS = RequestTracker.MAX_TICKS_LATE;

  /** Nodes at the front of an answer the mob may have walked past while it waited. */
  static final int MAX_SKIPPED_NODES = 4;

  private static final int NO_MOB = Integer.MIN_VALUE;

  private static final PathType[] TYPES = PathType.values();

  /**
   * A request waiting for its answer. {@code placeholder} is the path handed out meanwhile, and
   * {@code vanilla} is vanilla's answer in shadow mode. Each is null otherwise.
   */
  private static final class Pending {

    final BlockPos start;
    final BlockPos target;
    final Path vanilla;
    Path placeholder;

    Pending(BlockPos start, BlockPos target, Path vanilla) {
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

  private final ServerLevel level;
  private final Clock clock;
  private final Metrics metrics;
  private final ProfileTable profiles;
  private final Offload mode;
  private final Int2ObjectMap<MobState> mobs = new Int2ObjectOpenHashMap<>();
  private final Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);

  /** Answers by ticks from request to answer, the last bucket holding any later. */
  private final long[] ticksLate = new long[RequestTracker.MAX_TICKS_LATE + 1];

  private WorldInput controller;

  /** The mob whose request the controller is admitting now, or {@link #NO_MOB}. */
  private int admitting = NO_MOB;

  private boolean refused;

  public PathOffload(
      ServerLevel level, Clock clock, Metrics metrics, ProfileTable profiles, Offload mode) {
    this.level = level;
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

  @Override
  public boolean wants(Mob mob) {
    return controller != null && Searches.offloadable() && mob.level() == level;
  }

  @Override
  public Path search(Mob mob, SearchCall call, Supplier<Path> vanilla) {
    MobState state = mobs.computeIfAbsent(mob.getId(), id -> new MobState());
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
      return mode == Offload.SHADOW ? vanilla.get() : placeholder(mob, pending);
    }

    if (mode == Offload.SHADOW) {
      Path path = vanilla.get();
      if (request(mob, state, call)) {
        state.pending = new Pending(start, target, path);
      }
      return path;
    }
    if (!request(mob, state, call)) {
      return vanilla.get();
    }
    Pending sent = new Pending(start, target, null);
    state.pending = sent;
    return placeholder(mob, sent);
  }

  /**
   * Sends the call's request, which replaces any the mob has waiting. Returns whether the
   * controller took it. One it refuses at once is reported dropped during the call.
   */
  private boolean request(Mob mob, MobState state, SearchCall call) {
    record(Outcome.REQUESTED);
    Pending replaced = state.pending;
    state.pending = null;
    admitting = mob.getId();
    refused = false;
    try {
      controller.accept(
          new WorldEvent.PathRequested(
              clock.tick(),
              mob.getId(),
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
        endHold(mob);
      }
      return false;
    }
    return true;
  }

  /**
   * What the mob follows while it waits: its live path if it has one, or else a held path at the
   * start node. A held path it already follows there keeps being held.
   */
  private static Path placeholder(Mob mob, Pending pending) {
    Path current = mob.getNavigation().getPath();
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

  @Override
  public void displaced(Mob mob, Path current, Path next) {
    MobState state = mobs.get(mob.getId());
    Pending pending = state == null ? null : state.pending;
    if (pending == null || mode == Offload.SHADOW || next == pending.placeholder) {
      return;
    }
    if (current != pending.placeholder && !HeldPaths.isHeld(current)) {
      return;
    }
    // The navigation keeps a path it is given again.
    if (next != null && next.sameAs(current)) {
      return;
    }
    state.pending = null;
    record(Outcome.CANCELED);
    controller.accept(new WorldEvent.PathCanceled(clock.tick(), mob.getId()));
  }

  @Override
  public void pathComputed(PathResult result) {
    MobState state = mobs.get(result.mobId());
    Pending pending = state == null ? null : state.pending;
    if (pending == null) {
      return;
    }
    state.pending = null;
    record(Outcome.ANSWERED);
    long late = clock.tick() - result.issuedTick();
    ticksLate[(int) Math.clamp(late, 0, ticksLate.length - 1)]++;
    if (mode == Offload.SHADOW) {
      record(sameNodes(pending.vanilla, result) ? Outcome.SHADOW_MATCHED : Outcome.SHADOW_DIFFERED);
      return;
    }

    Entity entity = level.getEntity(result.mobId());
    if (!(entity instanceof Mob mob)) {
      return;
    }
    PathNavigation navigation = mob.getNavigation();
    Path current = navigation.getPath();
    if (current == null || (current != pending.placeholder && !HeldPaths.isHeld(current))) {
      // The mob moved on. A later call for the same search may still use the answer, once.
      state.latest = new Answer(pending.start, pending.target, result);
      record(Outcome.MOVED_ON);
      return;
    }
    record(Outcome.APPLIED);
    Path path = toPath(result);
    if (path == null) {
      // Vanilla's search found nothing either, and the navigation would stop.
      navigation.stop();
      return;
    }
    skipPassed(path, mob);
    NavigationAccess access = (NavigationAccess) navigation;
    access.maestro$setTargetPos(pending.target);
    navigation.moveTo(path, access.maestro$speedModifier());
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
    record(Outcome.DROPPED);
    metrics.count("path.offload.dropped", 1, "reason", reason.name());
    if (level.getEntity(mobId) instanceof Mob mob) {
      endHold(mob);
    }
  }

  /** The mob left the world. */
  public void mobRemoved(int mobId) {
    mobs.remove(mobId);
  }

  /** A held path the mob follows ends, so its goal finishes and asks again later. */
  private static void endHold(Mob mob) {
    Path current = mob.getNavigation().getPath();
    if (current != null && HeldPaths.isHeld(current)) {
      HeldPaths.release(current);
    }
  }

  /**
   * The answer started where the mob stood when it asked, and a mob that followed its live path
   * meanwhile is a node or two along. It follows the answer from the node of the first few it is
   * nearest.
   */
  private static void skipPassed(Path path, Mob mob) {
    Vec3 at = mob.position();
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

  private static boolean sameNodes(Path vanilla, PathResult result) {
    if (vanilla == null) {
      return result.nodes().isEmpty();
    }
    if (vanilla.canReach() != result.reached() || vanilla.getNodeCount() != result.nodes().size()) {
      return false;
    }
    for (int i = 0; i < vanilla.getNodeCount(); i++) {
      if (!pos(vanilla.getNodePos(i)).equals(result.nodes().get(i).pos())) {
        return false;
      }
    }
    return true;
  }

  private void record(Outcome outcome) {
    outcomes.merge(outcome, 1L, Long::sum);
    metrics.count("path.offload", 1, "outcome", outcome.name());
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
