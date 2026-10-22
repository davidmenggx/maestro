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
 * The world stream of each region: one log of sequence-numbered frames, and each holder's place in
 * it. The log belongs to the region, not to a node holding it, so it survives a reconnect and its
 * sequence keeps rising across resnapshots. Every holder is sent the same frames.
 *
 * <p>Encoding is offloaded and can finish out of order, but frames must leave in sequence order, so
 * finished frames wait in the log until the gap before them is filled. A world frame whose encode
 * keeps failing starts its region's log over.
 */
public final class WorldStreams {

  /**
   * Sent frames kept per region, so a reconnecting holder can be replayed forward instead of sent a
   * new snapshot. A holder that fell further behind gets a new snapshot.
   */
  public static final int RETENTION_FRAMES = 48;

  /**
   * Chunks read per tick, across all regions. A read runs on the main thread, so a region sent
   * again in full is spread over several ticks, and its chunks become usable as they arrive.
   */
  public static final int MAX_CHUNK_READS_PER_TICK = 16;

  /** {@link Holder#nextToSend} before the holder's first {@code Hello}: nothing is sent. */
  private static final long NOT_STARTED = Long.MAX_VALUE;

  /** One holder's place in its region's log. */
  private static final class Holder {

    /** Lowest sequence not yet handed to the transport for this holder. */
    private long nextToSend = NOT_STARTED;

    /**
     * Chunks the holder has resident, each with the sequence of the frame from which it holds it. A
     * chunk sent as cleared is absent, because the holder has it as air.
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
     * The first sequence of the log as it stands. A frame before it belongs to a log given up on,
     * and is dropped when its encode finishes.
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

  /**
   * The world sequence a holder reports once it has applied everything produced for its region: the
   * last one produced. An assignment stands at the log's position, so this holds for a holder that
   * holds its assignment alone too.
   */
  long heldSequence(NodeId node) {
    int region = regions.regionOf(node);
    return region == RegionMap.NONE ? 0 : logs.get(region).lastSequence;
  }

  /**
   * Whether every change to the region so far is in a frame produced for it, so a holder at {@link
   * #heldSequence} holds what the world holds now. Block changes are flushed with the tick.
   */
  boolean regionSettled(int regionId) {
    return logs.get(regionId).dirty.isEmpty();
  }

  /**
   * The sequence from which the node has the chunk resident, or null if it has it as air. A search
   * starting in the chunk is valid from that sequence on.
   */
  Long residentSince(NodeId node, long chunk) {
    int region = regions.regionOf(node);
    return region == RegionMap.NONE ? null : logs.get(region).holders.get(node).resident.get(chunk);
  }

  void blockChanged(WorldEvent.BlockChanged change) {
    pendingCells.add(change);
  }

  /**
   * A chunk that leaves is treated as air on its holders, as vanilla does for a chunk it could not
   * fetch, so the region keeps serving and only that chunk is sent again.
   */
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
   * Decides whether to replay the log to the holder or start it over for it. Replay needs the
   * holder to still hold this log's region, at a sequence the log still has the frames after.
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
   * Deltas per region, holding the changes to chunks its holders have resident. A chunk about to be
   * read is read after every change in it, so its changes are left out. Sending them after it would
   * also put a block back into a chunk that left.
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
      // Produced even while no holder is connected. A holder that reconnects within the
      // retention window is replayed from here.
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
   * One frame per chunk whose residency or content changed, for regions with a holder to send it
   * to. Reads stop at {@link #MAX_CHUNK_READS_PER_TICK}, and the rest stay marked for the next
   * tick. A chunk the world cannot read now stays marked and costs no read. Regions take turns at
   * being first.
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
            // A chunk sent again while resident never was air on the holder, so it keeps
            // the sequence it first became resident at.
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
   * The chunk's runs, read on the main thread, {@link #UNREADABLE}, or null if the read threw. A
   * read that throws is a bug in the world source. Retrying it would throw every tick, so the chunk
   * counts as unloaded until the world loads it again, and is sent as cleared.
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
   * Stores a finished frame under its sequence, then sends what the gap before it held back. A
   * frame of a log since started over is dropped, and one whose encode is given up on starts the
   * log over again.
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
   * Starts the log over at its last sequence, past a gap no frame can fill: every holder holds the
   * region as air from there, and every chunk the world holds is read and sent again.
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
   * Starts the log over for one holder at its last sequence: the holder will hold the region as
   * air, and every chunk the world holds is read again. The frames still being encoded before that
   * point are not sent to it, since the chunks read again supersede them.
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
   * Sent on the world channel, not the control channel. The assignment must arrive before the
   * frames it applies to, and only one channel orders them. It says where the log stands, so the
   * node refuses a first frame that does not follow it.
   */
  private void sendAssignment(Log log, NodeId node, long sequence, boolean resume) {
    links.send(
        node,
        Channel.WORLD,
        Frames.assignment(run, log.region.id(), log.region.size(), sequence, resume, world));
  }

  /**
   * Moves the log's ready point over every frame now present, and sends each greeted holder every
   * ready frame it has not been sent. Frames behind the ready point stay as the replay window until
   * they age out.
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
