package io.github.davidmenggx.maestro.load;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * One scenario running on the overworld, moved on by the server's tick events. Server thread only.
 *
 * <p>It builds the scenario's terrain and places the players, waits until every entity's chunk
 * ticks entities, places the entities {@link #SPAWNS_PER_TICK} a tick, waits out the warmup, then
 * measures until its duration ends or it is stopped. Durations count ticks, 20 to a second. Walking
 * players move as each tick starts. Once every entity is placed, mobs that caught the player they
 * hunt are reset as each tick's work ends, before the fleet's hand-over, so the requests they leave
 * are canceled in that tick. A reset stands for no work of a real server, so its time is not
 * measured. Natural spawning is off while it runs, and so is the damage crowded mobs take, so a
 * horde keeps its count, and mob griefing, so no mob changes a block of the terrain. What it placed
 * is removed when it ends, and the terrain stays.
 */
final class LoadRun {

  /** Tags every entity a run places, so one left by a run that never ended can be found. */
  static final String TAG = "maestro_load";

  /** Entities placed in one tick at most, so placing many does not stall a tick. */
  static final int SPAWNS_PER_TICK = 64;

  /** Ticks the entities' chunks may take to tick entities. */
  static final int LOAD_TIMEOUT_TICKS = 60 * 20;

  private static final int TICKS_PER_SECOND = 20;

  private record Walker(LoadPlayer player, Walk walk) {}

  private enum Phase {
    LOADING,
    SPAWNING,
    WARMUP,
    MEASURING
  }

  private final String name;
  private final Scenario scenario;
  private final ServerLevel level;
  private final Maestro maestro;

  /** The released mod as the run began, or null without it. */
  private final Maestro.Release release;

  /** Each group's entity, its type id included. */
  private final List<CompoundTag> builds;

  private final List<Scenario.Spawn> spawns;

  /** Whether natural spawning was on before the run. */
  private final boolean spawnedMobs;

  /** The most mobs a mob may be crowded by before it takes damage, before the run. */
  private final int cramming;

  /** Whether mobs could change blocks before the run. */
  private final boolean griefing;

  private final List<LoadPlayer> players = new ArrayList<>();
  private final List<Walker> walkers = new ArrayList<>();
  private final List<Entity> entities = new ArrayList<>();

  /** Chunks holding a spawn that do not tick entities yet. */
  private final Set<ChunkPos> waiting = new LinkedHashSet<>();

  private final LongArrayList tickNanos = new LongArrayList();

  /** Blocks the terrain changed as the run started. */
  private int built;

  /** Where reset mobs go, drawn after the players' routes. */
  private SplittableRandom resetSpots;

  /** Mobs reset while measuring. */
  private int resets;

  /** Time this tick spent on resets, left out of its measured time. */
  private long resetNanos;

  private Phase phase = Phase.LOADING;
  private int phaseTicks;
  private long tickStart;
  private long measureStart;
  private String measureStarted;
  private Offloads before;

  /** The active fleet's run as measuring began. */
  private long fleetRun;

  private LoadRun(
      String name,
      Scenario scenario,
      ServerLevel level,
      Maestro maestro,
      List<CompoundTag> builds) {
    this.name = name;
    this.scenario = scenario;
    this.level = level;
    this.maestro = maestro;
    this.release = maestro.release();
    this.builds = builds;
    this.spawns = scenario.spawns();
    this.spawnedMobs = level.getGameRules().get(GameRules.SPAWN_MOBS);
    this.cramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
    this.griefing = level.getGameRules().get(GameRules.MOB_GRIEFING);
    for (Scenario.Spawn spawn : spawns) {
      waiting.add(new ChunkPos(spawn.x() >> 4, spawn.z() >> 4));
    }
  }

  /**
   * Starts {@code scenario} on {@code level}: turns natural spawning, cramming damage and mob
   * griefing off, builds the terrain and places the players. {@code maestro} is the released mod
   * the report describes, or {@link Maestro#ABSENT}.
   */
  static LoadRun start(String name, Scenario scenario, ServerLevel level, Maestro maestro) {
    // Load players are not in the player list, so a server pausing when empty would pause.
    if (level.getServer() instanceof DedicatedServer server && server.pauseWhenEmptySeconds() > 0) {
      throw new IllegalStateException("`pause-when-empty-seconds` must be 0 in server.properties");
    }
    List<CompoundTag> builds = new ArrayList<>();
    for (Scenario.Group group : scenario.groups()) {
      builds.add(build(group, level));
    }
    LoadRun run = new LoadRun(name, scenario, level, maestro, builds);
    level.getGameRules().set(GameRules.SPAWN_MOBS, false, level.getServer());
    level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 0, level.getServer());
    level.getGameRules().set(GameRules.MOB_GRIEFING, false, level.getServer());
    try {
      run.built = new Terrain(scenario.keeps()).build(level, scenario.terrain());
      run.placePlayers();
    } catch (RuntimeException e) {
      run.clear();
      throw e;
    }
    return run;
  }

  /** The compound a group's entities are built from, checked as {@code /summon} would. */
  private static CompoundTag build(Scenario.Group group, ServerLevel level) {
    Identifier id = Identifier.tryParse(group.entity());
    EntityType<?> type =
        id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    if (type == null || !type.canSummon()) {
      throw new IllegalArgumentException("`" + group.entity() + "` is not a summonable entity");
    }
    if (level.getDifficulty() == Difficulty.PEACEFUL && !type.isAllowedInPeaceful()) {
      throw new IllegalArgumentException("`" + group.entity() + "` cannot exist in peaceful");
    }
    CompoundTag tag;
    try {
      tag = TagParser.parseCompoundFully(group.nbt());
    } catch (CommandSyntaxException e) {
      throw new IllegalArgumentException(
          "the `nbt` of `" + group.entity() + "` is not a compound: " + e.getMessage());
    }
    tag.putString("id", id.toString());
    return tag;
  }

  private void placePlayers() {
    SplittableRandom routes = new SplittableRandom(scenario.seed());
    for (int i = 0; i < scenario.players().size(); i++) {
      Scenario.Player at = scenario.players().get(i);
      SplittableRandom route = routes.split();
      // Loads the chunk, so its surface is known.
      level.getChunk(at.x() >> 4, at.z() >> 4);
      int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.x(), at.z());
      LoadPlayer player = LoadPlayer.at(level, i, at.x(), y, at.z(), at.hunted());
      level.addNewPlayer(player);
      players.add(player);
      if (at.walk() > 0) {
        walkers.add(new Walker(player, new Walk(route, at.x() + 0.5, at.z() + 0.5, at.walk())));
      }
    }
    resetSpots = routes.split();
  }

  /** Moves each walking player on a tick, onto the surface where it steps. */
  private void walk() {
    for (Walker walker : walkers) {
      walker.walk().step();
      double x = walker.walk().x();
      double z = walker.walk().z();
      int y =
          level.getHeight(
              Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
      walker.player().walkTo(x, y, z);
    }
  }

  /**
   * Moves each mob that caught the player it hunts to where {@link Scenario#respawn} puts it, in a
   * chunk that ticks entities, with its path and target dropped. A target goal that still sees the
   * player may take it again. A mob with nowhere to go is tried again the next tick.
   */
  private void reset() {
    Scenario.Reset rule = scenario.reset();
    List<Vec3> around = players.stream().map(Entity::position).toList();
    for (int i = 0; i < entities.size(); i++) {
      if (!(entities.get(i) instanceof Mob mob)
          || !mob.isAlive()
          || !(mob.getTarget() instanceof LoadPlayer target)
          || !rule.caught(mob.distanceToSqr(target))) {
        continue;
      }
      Scenario.Spawn spot =
          scenario.respawn(
              spawns.get(i).group(),
              resetSpots,
              (x, z) ->
                  rule.clearOf(x, z, around)
                      && level.isPositionEntityTicking(new BlockPos(x, 0, z)));
      if (spot == null) {
        continue;
      }
      int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.x(), spot.z());
      mob.getNavigation().stop();
      mob.setTarget(null);
      mob.setDeltaMovement(Vec3.ZERO);
      mob.resetFallDistance();
      mob.snapTo(spot.x() + 0.5, y, spot.z() + 0.5, mob.getYRot(), mob.getXRot());
      if (phase == Phase.MEASURING) {
        resets++;
      }
    }
  }

  int players() {
    return players.size();
  }

  /** Blocks the terrain changed as the run started, none if it was built already. */
  int built() {
    return built;
  }

  /** Whether the run has begun measuring, so ending it gives a report. */
  boolean measuring() {
    return phase == Phase.MEASURING;
  }

  void tickStarted() {
    tickStart = System.nanoTime();
    resetNanos = 0;
    walk();
  }

  /** Resets the mobs that caught their player as the tick's work ends, before other mods. */
  void tickEnding() {
    if (scenario.reset() != null && (phase == Phase.WARMUP || phase == Phase.MEASURING)) {
      long started = System.nanoTime();
      reset();
      resetNanos += System.nanoTime() - started;
    }
  }

  /**
   * Moves the run on as a tick ends, after other mods. Returns whether its duration is over. Throws
   * if its entities' chunks never tick entities or an entity cannot be placed.
   */
  boolean tickEnded() {
    if (phase == Phase.MEASURING) {
      tickNanos.add(System.nanoTime() - tickStart - resetNanos);
    }
    phaseTicks++;
    switch (phase) {
      case LOADING -> {
        waiting.removeIf(chunk -> level.isPositionEntityTicking(chunk.getWorldPosition()));
        if (waiting.isEmpty()) {
          discardLeftovers();
          enter(Phase.SPAWNING);
        } else if (phaseTicks >= LOAD_TIMEOUT_TICKS) {
          ChunkPos chunk = waiting.iterator().next();
          throw new IllegalStateException(
              "chunk "
                  + chunk.x()
                  + ", "
                  + chunk.z()
                  + " does not tick entities: no player is within simulation distance");
        }
      }
      case SPAWNING -> {
        int end = Math.min(entities.size() + SPAWNS_PER_TICK, spawns.size());
        while (entities.size() < end) {
          spawn(spawns.get(entities.size()));
        }
        if (entities.size() == spawns.size()) {
          enter(Phase.WARMUP);
        }
      }
      case WARMUP -> {
        if (phaseTicks >= scenario.warmupSeconds() * TICKS_PER_SECOND) {
          before = maestro.stats();
          fleetRun = maestro.run();
          measureStarted = Instant.now().toString();
          measureStart = System.nanoTime();
          enter(Phase.MEASURING);
        }
      }
      case MEASURING -> {
        int duration = scenario.durationSeconds() * TICKS_PER_SECOND;
        return duration > 0 && phaseTicks >= duration;
      }
    }
    return false;
  }

  private void enter(Phase next) {
    phase = next;
    phaseTicks = 0;
  }

  /** Discards what an earlier run that never ended left in the loaded chunks. */
  private void discardLeftovers() {
    List<Entity> left = new ArrayList<>();
    for (Entity entity : level.getAllEntities()) {
      if (entity.entityTags().contains(TAG)) {
        left.add(entity);
      }
    }
    left.forEach(Entity::discard);
  }

  private void spawn(Scenario.Spawn spawn) {
    String type = scenario.groups().get(spawn.group()).entity();
    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.x(), spawn.z());
    Entity entity =
        EntityType.loadEntityRecursive(
            builds.get(spawn.group()).copy(),
            level,
            EntitySpawnReason.COMMAND,
            placed -> {
              placed.snapTo(
                  spawn.x() + 0.5, y, spawn.z() + 0.5, placed.getYRot(), placed.getXRot());
              return placed;
            });
    if (entity == null) {
      throw new IllegalStateException("could not build `" + type + "` from its `nbt`");
    }
    entity.addTag(TAG);
    if (entity instanceof Mob mob) {
      mob.setPersistenceRequired();
    }
    if (!level.tryAddFreshEntityWithPassengers(entity)) {
      throw new IllegalStateException("could not place `" + type + "`: its UUID is taken");
    }
    entities.add(entity);
  }

  /** Ends a run that is measuring: its report, then what {@link #abort} does. */
  Report finish() {
    if (!measuring()) {
      throw new IllegalStateException("the run has not begun measuring");
    }
    int alive = (int) entities.stream().filter(Entity::isAlive).count();
    // Another fleet's counts do not follow on from those taken as measuring began.
    Offloads offloaded = maestro.run() == fleetRun ? maestro.stats().since(before) : null;
    Report report =
        new Report(
            name,
            scenario,
            measureStarted,
            release,
            fleetRun,
            players.size(),
            entities.size(),
            alive,
            resets,
            tickNanos.toLongArray(),
            System.nanoTime() - measureStart,
            offloaded);
    clear();
    return report;
  }

  /**
   * Ends the run with no report: removes what it placed and restores natural spawning, cramming
   * damage and mob griefing.
   */
  void abort() {
    clear();
  }

  private void clear() {
    for (Entity entity : entities) {
      if (!entity.isRemoved()) {
        entity.discard();
      }
    }
    for (LoadPlayer player : players) {
      level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
    }
    level.getGameRules().set(GameRules.SPAWN_MOBS, spawnedMobs, level.getServer());
    level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, cramming, level.getServer());
    level.getGameRules().set(GameRules.MOB_GRIEFING, griefing, level.getServer());
  }
}
