# Audit fixes: region rework, a split controller, spec-size simulation, and node hardening

This change fixes what the audit of 2a171d3 found. The controller no longer accepts a path its
node searched over air after a world stream starts over. A block a piston is moving can no
longer crash the chunk read or leave a gap in a stream. Regions now have ids from a per-run
counter, cover both sides of the origin, and may have several holders, each with its own place
in the region's one log. The controller is split into four classes and decodes replies through
`Frames`. The simulation runs every seed on spec-size 256-block regions, with chunks loaded
around spawn and players. It checks two new invariants: no answer searched over air is accepted,
and no servable request is dropped for want of a node. The node now limits what an unjoined
connection may send, drops requests that waited too long, refuses bad assignments and oversized
profiles, and applies deltas in time that follows the edit count. The mod clamps negative
floors, keeps the overworld's world border setup, never loads a chunk while reading it, and
puts request encodes ahead of world encodes.

## Changes

- Controller main (`server/controller/src/main`):
  - `region/Region` and a mutable `RegionMap`:
    - Ids come from a counter that starts at 1, so 0 means no region.
    - Regions never overlap.
    - A node holds one region at most, and a region may have several holders.
    - `RegionGrid` is removed.
  - `wire/Reply` and `Frames.readResponse` / `readControlReply`: node replies are decoded in
    `Frames`.
  - `Frames`:
    - `cancels` carries the region.
    - Request tables are written with every field, so 24 requests always fit 1200 bytes.
    - `readChunk` reuses its buffers per thread, and returns null for a chunk the source cannot
      read now.
    - New `MAX_DATAGRAM_BYTES`.
  - `WorldSource.readable`.
  - `Cells.pack` / `floorOf` / `typeOf` replace the hand-written packing everywhere.
  - `MobClass.Shape`:
    - The 512-cell footprint limit (`footprint`, `MAX_FOOTPRINT`) replaces the width and height
      bounds.
    - The node and the model enforce it too.
  - `Offload.Lane` (`REQUESTS`, `WORLD`).
  - `Transport.disconnect` removed. The controller, not the transport, sends each link's `Join`
    frames, so the run id has one source.
  - `DropReason.SHED` removed.
- Controller (test source set, still `sim`):
  - Split into `Controller` (dispatch), `NodeLinks`, `WorldStreams`, `RequestTracker`, `Encoder`
    and `Violations`. The self-violation list is bounded.
  - `WorldStreams`:
    - Each region has one log and a cursor per holder.
    - Each holder keeps its own resident chunks.
    - A holder starts over alone. A gap no frame can fill restarts the whole log.
    - The retention self-check counts only frames behind the ready point.
    - A holder at the log's restart point resumes instead of being sent the region again.
  - Major 1: an answer is accepted only if its holder has the start chunk resident, from a
    sequence at or before the answer's. Before, the check used the sequence taken at admission.
  - Major 2:
    - A chunk is read before its sequence is taken.
    - A read that throws counts the chunk as unloaded and sends it as cleared.
    - A chunk the world cannot read now stays marked and is retried next tick.
  - A region's mobs are split between its holders by id. A mob whose holder is not ready goes to
    the next holder that is.
  - A request batch given up on goes back to its node's next batch.
  - An `UnknownProfile` no longer lowers the node's reported depth.
- Simulation:
  - `Topology`:
    - 256-block regions, centered on the origin.
    - `replicated` regions with a second holder (`DEFAULT` has one).
  - `SimWorld`:
    - Signed coordinates and sparse block changes.
    - Terrain is hashed per chunk on first use.
    - A cached per-chunk hash for checkpoints.
    - Loaded-chunk bookkeeping (`loadedSince`, `mostLoaded`).
  - `SyntheticWorkload`: loads spawn chunks and each player's view. Mobs, block changes and
    churn use loaded chunks.
  - `ModeledNode`:
    - The area is kept per chunk.
    - 1 s retention and a 1 s maximum wait.
    - Refuses an assignment of another run, of run 0, or a fresh one behind the stream it holds.
    - Marks an answer searched over air.
  - `SimOffload` runs work when it finishes, and work that throws reports failure.
  - The self-check of a restarted controller is kept.
  - The state replayed after a restart goes through the run digest.
  - Stream monotonicity is checked per holder, and checkpoints are compared on every holder.
  - `RecordingOutput` counts a servable request dropped for want of a node as an SLO miss.
  - `Invariants` fails a run that accepted an answer searched over air.
  - `SimMetrics` keeps gauges.
  - Conformance scripts gained 15 cases (41 in all):
    - world frames the node refuses;
    - the three assignment checks;
    - a profile past 512 cells;
    - a request that waited past 1 s;
    - 1000 ms retention.
- Node (`node/`):
  - `NodeOptions`: `Retention` is now 1000 ms, and a new `MaxWait` is 1000 ms. A job that waited
    longer is settled unanswered, as a cancel would.
  - Assignment checks:
    - `NodeCore` refuses an assignment whose run is not the link's.
    - `WorldStore` refuses run 0 and a fresh assignment behind the held stream (`Behind`).
  - `Profile::decode` refuses a footprint past 512 cells, computed as vanilla does.
  - Frame size limits:
    - A pending connection's first frame is at most 256 bytes.
    - Control frames are at most 64 KiB, world frames 8 MiB.
    - A failed `accept` waits 100 ms before retrying.
  - An answer past 65507 bytes is dropped and logged, and `sendTo` errors are logged.
  - `Area::withCells` sorts edits, so it costs O(n log n) in the edit count. A no-op edit keeps
    its column and chunk shared.
  - Removed `serveWaiting`, the `Inline` scratch, the redundant `Pool.stop()`, the double frame
    verification and the unreachable `Applied` case. `ServerOptions` embeds `NodeOptions`.
  - The conformance replay matches the Java replay: a refused connect does not become the port's
    connection, `LinkDropped` checks the closed set, and `Serve` skips stale jobs.
- Mod (`server/src`):
  - `BlockCells.floor` clamps to [0, 32].
  - `MinecraftServerMixin` keeps the overworld's entry, so its world border is set up.
  - `LevelWorldSource` reads only through loaded `FULL` chunks (`getChunkNow`), so a read never
    loads a chunk.
  - `SearchCall` uses `Shape.fits` for the footprint and drops its unused `mob` field.
  - `PoolOffload` runs request jobs before world jobs.
  - `MaestroConfig` fills a missing key with its default.
  - `Telemetry` exporter logs are off.
  - `boot-smoke-test.sh` summons mobs so `PathFinderMixin` applies, and matches Mixin's real
    failure messages.
  - Removed `ParityCapture.checkSections` and a test of `System.nanoTime`.
- Schema comments: the footprint limit, region ids from 1, assignment rules, and conformance
  semantics.
- Fixtures: `fixtures/parity` recaptured (1163 exact cases, as before) and
  `fixtures/conformance/node.bin` regenerated.
- CI:
  - The C++ dependency cache uses the right path.
  - Fuzz shards are resized for the heavier runs: shallow 4 x 8000 seeds, deep 8 x 2500.
  - Local defaults are now shallow 5000 and deep 1500.
- CLAUDE.md:
  - the hold-in-place path;
  - the region model;
  - the node's assignment rules, retention and maximum wait;
  - answers that count only if the start chunk was held;
  - the shulker box and piston approximation;
  - spec-size simulation;
  - the two new invariants.

## Design decisions made without instruction

**Answer validity is decided from residency per holder.** The controller stores the start chunk
of each request. It accepts an answer only if the holder has that chunk resident from a sequence
at or before the answer's. An answer computed before the chunk later left is dropped too, which
is conservative. Alternative: keep closed residency intervals per chunk, which is exact but needs
more state.

**An unreadable chunk is retried; a chunk whose read throws is sent as air.** A chunk that is
briefly unreadable is about to leave, and retrying costs no read. A read that throws would throw
every tick, so the chunk counts as unloaded until the world loads it again.

**Replicas split mobs by id.** This split is deterministic and lets the DST reason about load. The
SLO fit check charges every holder of a replicated region with the region's full load, because
which holder is ready decides the split at startup. Alternative: route by load, which the fit
check could not predict.

**The controller sends the `Join` frames.** This makes the run id single-sourced, and keeps the
transport a plain byte carrier for the Netty work. Alternative: `connect(node, run)`.

**The oversized-answer risk was smaller than the audit said.** A path walks less than
`maxPathLength` (at most 2048), at one block or more per node, so an answer is at most about
16 KB. The visit bound was left alone, and the node's size check stays as a guard only.

**The simulation's servable-request rule.** A request is servable when its start chunk has been
loaded for longer than it takes to read every chunk loaded so far, plus 4 ticks. The chunk must
also still be loaded when the request is dropped. Without the second condition, a mob that walked
into the next chunk before its start chunk unloaded produced a false miss.

**Simulation speed.** Spec-size regions made runs about 20 times slower. Hashing chunks once,
caching the hashes, filling chunks by column runs and making terrain lazily brought this down to
about 7 times slower. CI shards were resized to stay within 30 minutes, which covers fewer seeds
than before.

**`reportedDepth` still drifts upward.** Requests the node finished after the controller stopped
waiting, or dropped on a cancel, are not taken off until the next heartbeat. That errs toward
rejecting, and fixing it exactly needs the node to report what it dropped.

**Kept on purpose:** `PlayerMoved` / `PlayerLeft` are kept although nothing reads them, since
shedding and the failover budget need them. The test seams `NodeCore::store()`, `Area::column()`,
`BinaryHeap::size()` and `Column::with(int, Cell)` are kept too.

## Not done

- The hold-in-place path is only recorded in CLAUDE.md. It needs the Phase 4 interception.
- No runtime reassignment, replication or split policy uses the mutable region map yet (Phase 5).
- The CI "Fuzz shallow 0" failure did not reproduce locally. The exact CI command and a scan of
  seeds 1-25000 both passed on the old code, so the CI log is needed.
