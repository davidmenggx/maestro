package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Offload;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionMap;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.wire.Reply;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The world stream of each region: one log of sequence-numbered frames and each holder's place in
 * it. The log belongs to the region, so it survives reconnects and its sequence keeps rising across
 * resnapshots. Every holder is sent the same frames.
 *
 * <p>Encodes can finish out of order but frames leave in sequence order, so finished frames wait
 * for the gap before them. A frame whose encode keeps failing starts the region's log over.
 */
public final class WorldStreams {

  /** Sent frames kept per region for replay. A holder further behind gets a new snapshot. */
  public static final int RETENTION_FRAMES = 48;

  /**
   * Chunks read per tick across all regions, on the main thread. A full resend of a region spreads
   * over several ticks.
   */
  public static final int MAX_CHUNK_READS_PER_TICK = 16;

  /** {@link Holder#nextToSend} before the holder's first {@code Hello}: nothing is sent. */
  private static final long NOT_STARTED = Long.MAX_VALUE;

  /** One holder's place in its region's log. */
  private static final class Holder {

    /** Lowest sequence not yet handed to the transport for this holder. */
    private long nextToSend = NOT_STARTED;

    /**
     * Chunks the holder has resident, with the sequence of the frame each came from. A cleared
     * chunk is absent (air).
     */
    private final Map<Long, Long> resident = new LinkedHashMap<>();
  }

  private static final class Log {

    private final Region region;

    /** Highest sequence produced, encoded or not. */
    private long lastSequence;

    /** Every frame up to this one is in {@link #frames} or was evicted from it. */
    private long ready;

    /**
     * First sequence of the current log. Earlier frames belong to a log given up on and are dropped
     * when their encode finishes.
     */
    private long logBase = 1;

    /** Highest sequence dropped from {@link #frames}. Resuming before it is impossible. */
    private long evicted;

    private final TreeMap<Long, byte[]> frames = new TreeMap<>();

    /** Chunks whose state changed and has not been read. */
    private final Set<Long> dirty = new LinkedHashSet<>();

    private final Map<NodeId, Holder> holders = new LinkedHashMap<>();

    private Log(Region region) {
      this.region = region;
    }
  }

  private final ControllerEnv env;
  private final WorldSource world;
  private final RegionMap regions;
  private final NodeLinks links;
  private final Encoder encoder;
  private final Violations violations;
  private final long run;

  /** By region id, in id order. */
  private final Map<Integer, Log> logs = new LinkedHashMap<>();

  private final Set<Long> loadedChunks = new LinkedHashSet<>();
  private final List<WorldEvent.BlockChanged> pendingCells = new ArrayList<>();

  /** Index of the log {@link #flushChunks} starts from, so each gets a turn at the read budget. */
  private int readCursor;

  WorldStreams(
      ControllerEnv env,
      WorldSource world,
      RegionMap regions,
      NodeLinks links,
      Encoder encoder,
      Violations violations,
      long run) {
    this.env = env;
    this.world = world;
    this.regions = regions;
    this.links = links;
    this.encoder = encoder;
    this.violations = violations;
    this.run = run;
    for (Region region : regions.regions()) {
      Log log = new Log(region);
      for (NodeId node : regions.holders(region.id())) {
        log.holders.put(node, new Holder());
      }
      logs.put(region.id(), log);
    }
  }

  /** The sequence a holder reports once it has applied everything produced for its region. */
  long heldSequence(NodeId node) {
    int region = regions.regionOf(node);
    return region == RegionMap.NONE ? 0 : logs.get(region).lastSequence;
  }

  /**
   * Whether every change so far is in a produced frame, so a holder at {@link #heldSequence}
   * matches the world now. Block changes are flushed with the tick.
   */
  boolean regionSettled(int regionId) {
    return logs.get(regionId).dirty.isEmpty();
  }

  /** The sequence from which the node has the chunk resident, or null if it holds air there. */
  Long residentSince(NodeId node, long chunk) {
    int region = regions.regionOf(node);
    return region == RegionMap.NONE ? null : logs.get(region).holders.get(node).resident.get(chunk);
  }

  void blockChanged(WorldEvent.BlockChanged change) {
    pendingCells.add(change);
  }

  /** A leaving chunk is air on its holders, as in vanilla, so only that chunk is sent again. */
  void chunkResidency(int chunkX, int chunkZ, boolean loaded) {
    long key = Chunks.key(chunkX, chunkZ);
    boolean changed = loaded ? loadedChunks.add(key) : loadedChunks.remove(key);
    if (!changed) {
      return;
    }
    Region region = regions.regionAt(chunkX * Chunks.SIZE, chunkZ * Chunks.SIZE);
    if (region != null) {
      logs.get(region.id()).dirty.add(key);
    }
  }

  /**
   * Replays the log to the holder, or starts it over, if the holder cannot resume from the frames
   * the log still has.
   */
  void hello(NodeId node, Reply.Greeting hello) {
    int regionId = regions.regionOf(node);
    if (regionId == RegionMap.NONE) {
      return;
    }
    Log log = logs.get(regionId);
    Holder holder = log.holders.get(node);
    long held = hello.lastSequence();
    boolean resume =
        held > 0
            && hello.run() == run
            && hello.regionId() == regionId
            && held <= log.lastSequence
            && held >= log.logBase - 1
            && held >= log.evicted;
    if (resume) {
      env.metrics().count("world.resume", 1, "node", node.toString());
      holder.nextToSend = held + 1;
    } else {
      env.metrics().count("world.resnapshot", 1, "node", node.toString());
      startOver(log, holder);
    }
    sendAssignment(log, node, resume ? held : log.lastSequence, resume);
    drain(log);
  }

  /**
   * Deltas per region for chunks its holders have resident. A chunk about to be read is skipped,
   * since the read includes its changes and a later delta could re-add a block to a chunk that
   * left.
   */
  void flushCells() {
    Map<Integer, List<WorldEvent.BlockChanged>> byRegion = new TreeMap<>();
    for (WorldEvent.BlockChanged change : pendingCells) {
      Region region = regions.regionAt(change.pos().x(), change.pos().z());
      if (region == null) {
        continue;
      }
      long key = Chunks.key(Chunks.of(change.pos().x()), Chunks.of(change.pos().z()));
      if (loadedChunks.contains(key) && !logs.get(region.id()).dirty.contains(key)) {
        byRegion.computeIfAbsent(region.id(), unseen -> new ArrayList<>()).add(change);
      }
    }
    pendingCells.clear();

    for (Map.Entry<Integer, List<WorldEvent.BlockChanged>> entry : byRegion.entrySet()) {
      // Produced even with no holder connected, so a reconnect within retention is replayed.
      Log log = logs.get(entry.getKey());
      List<WorldEvent.BlockChanged> cells = entry.getValue();
      int minX = log.region.minX();
      int minZ = log.region.minZ();
      for (int from = 0; from < cells.size(); from += Frames.MAX_CELLS_PER_DELTA) {
        List<WorldEvent.BlockChanged> part =
            cells.subList(from, Math.min(cells.size(), from + Frames.MAX_CELLS_PER_DELTA));
        long sequence = ++log.lastSequence;
        produce(log, sequence, "delta", () -> Frames.delta(sequence, part, minX, minZ));
      }
    }
  }

  /**
   * One frame per chunk whose residency or content changed, for regions with a holder. Stops at
   * {@link #MAX_CHUNK_READS_PER_TICK}; the rest stay marked. Unreadable chunks stay marked at no
   * cost. Regions take turns going first.
   */
  void flushChunks() {
    List<Log> order = List.copyOf(logs.values());
    if (order.isEmpty()) {
      return;
    }
    int reads = 0;
    for (int turn = 0; turn < order.size(); turn++) {
      Log log = order.get((readCursor + turn) % order.size());
      if (log.dirty.isEmpty() || !anyGreeted(log)) {
        continue;
      }
      int originChunkX = Chunks.of(log.region.minX());
      int originChunkZ = Chunks.of(log.region.minZ());
      Iterator<Long> dirty = log.dirty.iterator();
      while (dirty.hasNext()) {
        long key = dirty.next();
        boolean loaded = loadedChunks.contains(key);
        if (loaded && reads == MAX_CHUNK_READS_PER_TICK) {
          break;
        }
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;
        // Wire positions, including chunk indices, are relative to the region.
        int localX = chunkX - originChunkX;
        int localZ = chunkZ - originChunkZ;
        Frames.ChunkRuns runs = null;
        if (loaded) {
          runs = read(key, chunkX, chunkZ);
          if (runs == UNREADABLE) {
            continue;
          }
          reads++;
        }
        dirty.remove();
        long sequence = ++log.lastSequence;
        if (runs != null) {
          for (Holder holder : log.holders.values()) {
            // A chunk resent while resident keeps its original sequence.
            holder.resident.putIfAbsent(key, sequence);
          }
          Frames.ChunkRuns read = runs;
          produce(
              log, sequence, "chunk", () -> Frames.chunkSnapshot(sequence, localX, localZ, read));
        } else {
          for (Holder holder : log.holders.values()) {
            holder.resident.remove(key);
          }
          produce(log, sequence, "chunk", () -> Frames.chunkCleared(sequence, localX, localZ));
        }
      }
    }
    readCursor = (readCursor + 1) % order.size();
    if (reads == MAX_CHUNK_READS_PER_TICK) {
      env.metrics().count("world.chunks.deferred", 1);
    }
  }

  /** {@link #read} of a chunk the world cannot read now. Compared by identity. */
  private static final Frames.ChunkRuns UNREADABLE = new Frames.ChunkRuns();

  /**
   * The chunk's runs, {@link #UNREADABLE}, or null if the read threw. A throwing read is a world
   * source bug and would repeat every tick, so the chunk counts as unloaded and is sent as cleared.
   */
  private Frames.ChunkRuns read(long key, int chunkX, int chunkZ) {
    try {
      Frames.ChunkRuns runs = Frames.readChunk(world, chunkX, chunkZ);
      if (runs == null) {
        env.metrics().count("world.chunks.unreadable", 1);
        return UNREADABLE;
      }
      return runs;
    } catch (RuntimeException e) {
      env.metrics().count("world.chunks.failed", 1);
      violations.add("chunk " + chunkX + "," + chunkZ + " could not be read: " + e);
      loadedChunks.remove(key);
      return null;
    }
  }

  /** Frames kept for replay past {@link #RETENTION_FRAMES}. Frames behind a gap do not count. */
  void check(List<String> found) {
    for (Log log : logs.values()) {
      int retained = log.frames.headMap(log.ready, true).size();
      if (retained > RETENTION_FRAMES) {
        found.add(
            "region "
                + log.region.id()
                + " retains "
                + retained
                + " frames, past "
                + RETENTION_FRAMES);
      }
    }
  }

  /**
   * Stores a finished frame under its sequence and sends what the gap held back. Frames of a
   * restarted log are dropped; one whose encode is given up on restarts the log.
   */
  private void produce(Log log, long sequence, String job, Supplier<byte[]> work) {
    encoder.encode(
        job,
        Offload.Lane.WORLD,
        work,
        frame -> {
          if (sequence < log.logBase) {
            env.metrics().count("world.frame.superseded", 1, "job", job);
            return;
          }
          log.frames.put(sequence, frame);
          drain(log);
        },
        () -> {
          if (sequence < log.logBase) {
            return;
          }
          env.metrics().count("world.restarted", 1, "region", Integer.toString(log.region.id()));
          restart(log);
        });
  }

  /**
   * Starts the log over past a gap no frame can fill: every holder holds air from there, and every
   * chunk the world holds is read and sent again.
   */
  private void restart(Log log) {
    log.frames.clear();
    log.logBase = log.lastSequence + 1;
    log.ready = log.lastSequence;
    for (Map.Entry<NodeId, Holder> entry : log.holders.entrySet()) {
      startOver(log, entry.getValue());
      if (links.greeted(entry.getKey())) {
        sendAssignment(log, entry.getKey(), log.lastSequence, false);
      }
    }
  }

  /**
   * Starts the log over for one holder: it holds air from there and every chunk is read again.
   * Frames still encoding before that point are not sent to it.
   */
  private void startOver(Log log, Holder holder) {
    holder.nextToSend = log.lastSequence + 1;
    holder.resident.clear();
    int minChunkX = Chunks.of(log.region.minX());
    int minChunkZ = Chunks.of(log.region.minZ());
    int perSide = log.region.size() / Chunks.SIZE;
    for (int x = minChunkX; x < minChunkX + perSide; x++) {
      for (int z = minChunkZ; z < minChunkZ + perSide; z++) {
        long key = Chunks.key(x, z);
        if (loadedChunks.contains(key)) {
          log.dirty.add(key);
        }
      }
    }
  }

  /**
   * Sent on the world channel, which alone orders it before the frames it applies to. It says where
   * the log stands, so the node refuses a first frame that does not follow.
   */
  private void sendAssignment(Log log, NodeId node, long sequence, boolean resume) {
    links.send(
        node,
        Channel.WORLD,
        Frames.assignment(run, log.region.id(), log.region.size(), sequence, resume, world));
  }

  /**
   * Advances the ready point over present frames and sends each greeted holder the ready frames it
   * lacks. Frames behind the ready point stay as the replay window.
   */
  private void drain(Log log) {
    while (log.frames.containsKey(log.ready + 1)) {
      log.ready++;
    }
    for (Map.Entry<NodeId, Holder> entry : log.holders.entrySet()) {
      Holder holder = entry.getValue();
      if (!links.greeted(entry.getKey())) {
        continue;
      }
      for (; holder.nextToSend <= log.ready; holder.nextToSend++) {
        links.send(entry.getKey(), Channel.WORLD, log.frames.get(holder.nextToSend));
      }
    }
    long lowest = log.ready - RETENTION_FRAMES;
    while (!log.frames.isEmpty() && log.frames.firstKey() <= lowest) {
      log.evicted = Math.max(log.evicted, log.frames.pollFirstEntry().getKey());
    }
  }

  private boolean anyGreeted(Log log) {
    for (NodeId node : log.holders.keySet()) {
      if (links.greeted(node)) {
        return true;
      }
    }
    return false;
  }
}
