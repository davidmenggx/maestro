package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Offload;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.Regions;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.wire.Reply;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The world stream of each region's area: one log of sequence-numbered frames and each holder's
 * place in it. The log belongs to the region, so it survives reconnects and holder changes, and its
 * sequence keeps rising across resnapshots. Every holder is sent the same sequence. A chunk read
 * only because some holders lack it reaches the others as an empty delta, so a holder starting over
 * costs the rest nothing. A chunk in the halo of several regions goes to each of their logs.
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

  /**
   * A produced frame. Holders in {@code skipping} already hold its chunk as it is, so they are sent
   * {@code skip}, an empty delta of the same sequence, instead of {@code full}.
   */
  private record Frame(byte[] full, byte[] skip, Set<NodeId> skipping) {

    static Frame plain(byte[] full) {
      return new Frame(full, null, Collections.emptySet());
    }

    byte[] bytesFor(NodeId node) {
      return skipping.contains(node) ? skip : full;
    }
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

    private final TreeMap<Long, Frame> frames = new TreeMap<>();

    /** Chunks to read: their residency changed, or a holder lacks them. */
    private final Set<Long> dirty = new LinkedHashSet<>();

    /** Chunks of {@link #dirty} whose residency changed, which every holder is sent in full. */
    private final Set<Long> stale = new LinkedHashSet<>();

    private final Map<NodeId, Holder> holders = new LinkedHashMap<>();

    private Log(Region region) {
      this.region = region;
    }
  }

  private final ControllerEnv env;
  private final WorldSource world;
  private final Regions regions;
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
      Regions regions,
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
      Log log = addLog(region);
      for (NodeId node : regions.holders(region.id())) {
        log.holders.put(node, new Holder());
      }
    }
  }

  /** A new region, whose log starts empty. Its loaded chunks are read for its first holder. */
  private Log addLog(Region region) {
    Log log = new Log(region);
    logs.put(region.id(), log);
    markArea(log);
    return log;
  }

  /**
   * {@code parent} was cut and its holders hold {@code lower}, whose area has the parent's corner
   * and lies inside its area. A greeted holder sent every frame the parent's log produced keeps
   * what it holds there, and the lower half's log goes on from the parent's sequence. Other holders
   * start the lower half over. The upper half starts empty.
   */
  void split(Region parent, Region lower, Region upper) {
    if (lower.minX() != parent.minX() || lower.minZ() != parent.minZ()) {
      throw new IllegalArgumentException(lower + " does not share the corner of " + parent);
    }
    Log old = logs.remove(parent.id());
    Log kept = new Log(lower);
    logs.put(lower.id(), kept);
    // Frames of the parent's log still encoding never reach the lower half's holders.
    kept.lastSequence = old.lastSequence;
    kept.ready = old.lastSequence;
    kept.logBase = old.lastSequence + 1;
    kept.evicted = old.lastSequence;
    for (long key : old.dirty) {
      if (inArea(lower, key)) {
        kept.dirty.add(key);
      }
    }
    for (long key : old.stale) {
      if (inArea(lower, key)) {
        kept.stale.add(key);
      }
    }
    for (Map.Entry<NodeId, Holder> entry : old.holders.entrySet()) {
      NodeId node = entry.getKey();
      Holder was = entry.getValue();
      Holder holder = new Holder();
      kept.holders.put(node, holder);
      if (!links.greeted(node)) {
        // It starts over when it greets, since it reports holding the parent.
        continue;
      }
      if (was.nextToSend == old.lastSequence + 1) {
        env.metrics().count("world.kept", 1, "node", node.toString());
        holder.nextToSend = was.nextToSend;
        for (Map.Entry<Long, Long> chunk : was.resident.entrySet()) {
          if (inArea(lower, chunk.getKey())) {
            holder.resident.put(chunk.getKey(), chunk.getValue());
          }
        }
        links.send(
            node,
            Channel.WORLD,
            Frames.keptAssignment(
                run,
                lower.id(),
                parent.id(),
                lower.areaSizeX(),
                lower.areaSizeZ(),
                kept.lastSequence,
                world));
      } else {
        env.metrics().count("world.resnapshot", 1, "node", node.toString());
        startOver(kept, holder);
        sendAssignment(kept, node, kept.lastSequence, false);
      }
    }
    // A frame of the parent's log that finishes encoding later goes to no one.
    old.holders.clear();
    addLog(upper);
  }

  private static boolean inArea(Region region, long chunk) {
    return region.inArea((int) (chunk >> 32) * Chunks.SIZE, (int) chunk * Chunks.SIZE);
  }

  /**
   * {@code node}, which is linked, holds the region from now on. It starts over from where the log
   * stands.
   */
  void holderAdded(int regionId, NodeId node) {
    Log log = logs.get(regionId);
    Holder holder = new Holder();
    log.holders.put(node, holder);
    env.metrics().count("world.resnapshot", 1, "node", node.toString());
    startOver(log, holder);
    sendAssignment(log, node, log.lastSequence, false);
    drain(log);
  }

  /** {@code node} no longer holds the region and is sent none of its frames. */
  void holderRemoved(int regionId, NodeId node) {
    Log log = logs.get(regionId);
    if (log != null) {
      log.holders.remove(node);
    }
  }

  /** The sequence a holder reports once it has applied everything produced for its region. */
  long heldSequence(NodeId node) {
    int region = regions.regionOf(node);
    return region == Regions.NONE ? 0 : logs.get(region).lastSequence;
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
    return region == Regions.NONE ? null : logs.get(region).holders.get(node).resident.get(chunk);
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
    for (Region region : regions.reaching(chunkX * Chunks.SIZE, chunkZ * Chunks.SIZE)) {
      Log log = logs.get(region.id());
      log.dirty.add(key);
      log.stale.add(key);
    }
  }

  /**
   * Replays the log to the holder, or starts it over, if the holder cannot resume from the frames
   * the log still has.
   */
  void hello(NodeId node, Reply.Greeting hello) {
    int regionId = regions.regionOf(node);
    if (regionId == Regions.NONE) {
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
   * Deltas per region for loaded chunks. A chunk whose residency changed is skipped, since its read
   * includes its changes and a later delta could re-add a block to a chunk that left. One read only
   * for holders that lack it still takes the delta, which the others need.
   */
  void flushCells() {
    Map<Integer, List<WorldEvent.BlockChanged>> byRegion = new TreeMap<>();
    for (WorldEvent.BlockChanged change : pendingCells) {
      long key = Chunks.key(Chunks.of(change.pos().x()), Chunks.of(change.pos().z()));
      if (!loadedChunks.contains(key)) {
        continue;
      }
      for (Region region : regions.reaching(change.pos().x(), change.pos().z())) {
        if (!logs.get(region.id()).stale.contains(key)) {
          byRegion.computeIfAbsent(region.id(), unseen -> new ArrayList<>()).add(change);
        }
      }
    }
    pendingCells.clear();

    for (Map.Entry<Integer, List<WorldEvent.BlockChanged>> entry : byRegion.entrySet()) {
      // Produced even with no holder connected, so a reconnect within retention is replayed.
      Log log = logs.get(entry.getKey());
      List<WorldEvent.BlockChanged> cells = entry.getValue();
      int minX = log.region.areaMinX();
      int minZ = log.region.areaMinZ();
      for (int from = 0; from < cells.size(); from += Frames.MAX_CELLS_PER_DELTA) {
        List<WorldEvent.BlockChanged> part =
            cells.subList(from, Math.min(cells.size(), from + Frames.MAX_CELLS_PER_DELTA));
        long sequence = ++log.lastSequence;
        produce(
            log, sequence, "delta", () -> Frame.plain(Frames.delta(sequence, part, minX, minZ)));
      }
    }
  }

  /**
   * One frame per marked chunk, for regions with a holder. A chunk whose residency changed goes to
   * every holder in full; one marked because holders lack it goes in full to those, and as an empty
   * delta to the rest, and is not read if none lacks it. Stops at {@link
   * #MAX_CHUNK_READS_PER_TICK}; the rest stay marked. Unreadable chunks stay marked at no cost.
   * Regions take turns going first.
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
      int originChunkX = Chunks.of(log.region.areaMinX());
      int originChunkZ = Chunks.of(log.region.areaMinZ());
      Iterator<Long> dirty = log.dirty.iterator();
      while (dirty.hasNext()) {
        long key = dirty.next();
        boolean loaded = loadedChunks.contains(key);
        boolean stale = log.stale.contains(key);
        Set<NodeId> skipping = new LinkedHashSet<>();
        if (!stale) {
          for (Map.Entry<NodeId, Holder> holder : log.holders.entrySet()) {
            if (holder.getValue().resident.containsKey(key)) {
              skipping.add(holder.getKey());
            }
          }
        }
        if (loaded && !stale && skipping.size() == log.holders.size()) {
          dirty.remove();
          continue;
        }
        if (loaded && reads == MAX_CHUNK_READS_PER_TICK) {
          break;
        }
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;
        // Wire positions, including chunk indices, are relative to the area.
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
        log.stale.remove(key);
        long sequence = ++log.lastSequence;
        if (runs != null) {
          for (Holder holder : log.holders.values()) {
            // A chunk resent while resident keeps its original sequence.
            holder.resident.putIfAbsent(key, sequence);
          }
          Frames.ChunkRuns read = runs;
          Set<NodeId> skip = Collections.unmodifiableSet(skipping);
          if (!skip.isEmpty()) {
            env.metrics().count("world.chunks.skipped", skip.size());
          }
          produce(
              log,
              sequence,
              "chunk",
              () ->
                  skip.isEmpty()
                      ? Frame.plain(Frames.chunkSnapshot(sequence, localX, localZ, read))
                      : new Frame(
                          Frames.chunkSnapshot(sequence, localX, localZ, read),
                          Frames.delta(sequence, List.of(), 0, 0),
                          skip));
        } else {
          for (Holder holder : log.holders.values()) {
            holder.resident.remove(key);
          }
          produce(
              log,
              sequence,
              "chunk",
              () -> Frame.plain(Frames.chunkCleared(sequence, localX, localZ)));
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
  private void produce(Log log, long sequence, String job, Supplier<Frame> work) {
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
    markArea(log);
  }

  /** Marks every loaded chunk of the log's area to be read. */
  private void markArea(Log log) {
    Region region = log.region;
    int minChunkX = Chunks.of(region.areaMinX());
    int minChunkZ = Chunks.of(region.areaMinZ());
    int chunksX = region.areaSizeX() / Chunks.SIZE;
    int chunksZ = region.areaSizeZ() / Chunks.SIZE;
    // The fewer of the area's chunks and the loaded ones are looked through.
    if ((long) chunksX * chunksZ > loadedChunks.size()) {
      for (long key : loadedChunks) {
        int x = (int) (key >> 32) - minChunkX;
        int z = (int) key - minChunkZ;
        if (x >= 0 && x < chunksX && z >= 0 && z < chunksZ) {
          log.dirty.add(key);
        }
      }
      return;
    }
    for (int x = minChunkX; x < minChunkX + chunksX; x++) {
      for (int z = minChunkZ; z < minChunkZ + chunksZ; z++) {
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
        Frames.assignment(
            run,
            log.region.id(),
            log.region.areaSizeX(),
            log.region.areaSizeZ(),
            sequence,
            resume,
            world));
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
        links.send(
            entry.getKey(),
            Channel.WORLD,
            log.frames.get(holder.nextToSend).bytesFor(entry.getKey()));
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
