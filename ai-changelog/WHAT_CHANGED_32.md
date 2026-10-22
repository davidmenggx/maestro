# Audit fixes: link identity, node conformance, a faithful node model, and controller cleanup

This change fixes what the audit of the node networking commit found, except for work that belongs
to later phases. Every datagram and every TCP connection now says which controller run and link it
belongs to, so a stray connection cannot replace the live link, and a request is never searched in
the wrong area. The C++ node's decisions moved into a deterministic core, and a shared set of
conformance scripts now runs against both that core and the Java modeled node, so the model cannot
drift from the node. The modeled node now serves requests on workers that take time, which exposed
an off-by-one in admission: the bound and the resend due tick left out the request's own service
time. The controller keys region streams by region id, takes its run id and node addresses from its
environment, can close a link, gives up on an encode that keeps failing, spreads chunk reads over
ticks, and drops several dead paths. The simulation gained node pauses, controller restarts,
mid-run state checkpoints, and an SLO check decided from the offered load instead of from what the
controller did.

## Changes

- Schema:
  - `Join { run, link }`, the first frame on both TCP connections, in `WorldMsg` and `ControlMsg`.
  - `RequestFrame.run` and `region_id`, `ResponseFrame.run`.
  - `schema/conformance.fbs`: the conformance script format. `common.fbs` comment on `BREACH`.
- Node:
  - `serve::NodeCore` holds every protocol decision, with injected time and outputs. `net::Server`
    is the asio shell. A connection is pending until its first frame; a valid `Join` of another run
    or a later attempt replaces the link, the current token joins once per port, and anything else
    is refused without touching the link. A silent pending connection closes after 5 s. `Hello` is
    sent on the control `Join`. A `Join` after the first frame is ignored.
  - A request frame of another run is dropped. A job whose run or region is not the held area's is
    answered with no path at sequence 0. Every response carries the run.
  - `Area::withCells` groups edits by chunk and column and rebuilds each column once.
  - Removed `Search::kind()`, a no-op `std::min`, and a second load in `WorldStore::admit`.
- Controller (`controller/src/main`):
  - `ControllerEnv.run`: the run id, never 0, no longer drawn from the seeded RNG.
  - `Transport.connect(node)` takes no address, and every attempt ends in exactly one callback.
    New `Transport.disconnect(node)`.
  - `region/RegionGrid` and `region/RegionMap` moved from the test harness.
  - `Frames.joinWorld`, `joinControl`; `requests`, `cancels` and `response` carry the run, requests
    the region too.
  - `WorldSource.readSection` reads a section at once. `Frames.readChunk` uses it.
  - `WorldEvent.Search.fits` holds the bounds once, and the path length bound is at most 2048.
  - `PathTypes.perBlockCount` and `perBlock` removed.
- `Controller` (renamed from `ProbeController`, still in the test source set):
  - Region streams keyed by region id. A `connecting` flag stops duplicate dials, and frames or
    callbacks from nodes outside the fleet are dropped.
  - Admission counts the request itself: `(ahead + 1) / capacity`. The resend due tick counts the
    whole batch.
  - Reported depth falls by one per answer seen until the next heartbeat.
  - An `UnknownProfile` for a resent request also cancels it.
  - An encode is tried 5 times. A world frame given up on starts its region's stream over with a
    fresh assignment; a request batch given up on times out.
  - At most 16 resident chunks are read per tick, regions taking turns.
  - Answers of another run are dropped. The response node list is no longer presized from the
    datagram.
  - Due ticks and per-node due counts are indexed instead of scanned each tick.
  - Removed a dead branch in `abandon` and stale comments.
- Mod:
  - `LevelWorldSource.readSection` classifies each block state once per section, except blocks with
    a dynamic shape, whose floor is read per block. Chunk reads are 7 to 12 times faster.
  - `SearchCall` keeps a search on the server when the evaluator footprint is over 512 cells.
  - `Searches.observe` returns the previous observer, and the capture restores it.
  - `EntityMixin` removed: it could never fire. The version is read from the mod metadata.
  - The capture checks section reads against block reads, and records run 1, region 3.
- Simulation:
  - `ModeledNode` acts through a `Host` and makes the real node's decisions: `Join` handling, the
    run check, the region check, 500 ms retention, profile validation, and waiting plus running in
    the heartbeat. Workers serve with a seeded cost. Its heartbeat runs on its own timer at a random
    phase.
  - `SimTransport`: overlapping faults are counted, a connect always calls back once, links open
    with real `Join` frames, datagrams outlive links, a paused node holds what reaches it, and each
    controller process has its own session.
  - New faults `Paused` and `ControllerRestarted`. A restart closes open requests in the ledger and
    replays the world to a new controller with a new run id.
  - Stream checks are per run, a fresh assignment may not start behind an applied frame, and seeded
    mid-run checkpoints compare a node's state with the world's when it reaches them.
  - `Scenario`: `WORKERS`, `WORKER_CAPACITY` (replaces `NODE_CAPACITY`), `COST_SPREAD_PERCENT`,
    `CAPACITY_ESTIMATE_PERCENT`, `REPEAT_REQUEST`. Mobs live only in loaded chunks and leave with
    them.
  - `SimRun.fitsCapacity` is decided from the offered load per node and tick, the workers, the
    highest cost and the latency. Clean plans are 40% of samples, and the edge latency follows the
    fleet's highest cost.
  - The ledger checks that a reached path ends at its target.
- Fixtures: `fixtures/parity` regenerated (1163 exact cases, as before); `fixtures/conformance/node.bin`
  added (26 cases), written by `make conformance`.
- CLAUDE.md: the footprint gate, two vanilla differences, the chunk read budget, link pairing and
  run/region on datagrams, conformance scripts, and the new simulation faults and checks.

## Results

- Node: 174 tests pass in plain, ASan+UBSan and TSan builds. All 26 conformance cases pass on the
  first run. `withCells` on a 65536-cell delta: 35 to 63 ms before, 13 to 16 ms after.
- Java: 27 mod tests and 151 controller tests pass, including the parity fixtures and the
  conformance replay on the modeled node.
- Fuzz: shallow (2000 seeds), deep (1500) and coverage (2000) pass. In the coverage sweep, 173 seeds
  were held to the SLO (82 at the latency edge) and 14253 mid-run checkpoints were compared.

## Design decisions made without instruction

**A link is named by run and attempt, a request by run and region.** The `Join` carries the run and
a per-node attempt counter, so the node pairs the two connections of one attempt and refuses older
ones. Request frames carry run and region, and the node checks the region when the search starts.
Alternatives: an epoch bumped per assignment, or a fresh link for every reassignment.

**Admission now counts the request itself.** With workers that take time, a node answers the
request at the back of the admitted queue one service time later than the old bound assumed, so it
missed the 10-tick budget. Alternative: keep the old bound and accept one late request at the edge.

**The model's capacity is per worker.** A request costs at most a tick and a half, so slow nodes
come from few workers rather than very slow searches. Tying cost to node capacity made a 4-worker
node at 1 request a tick take 4 ticks per search, and almost no run fit the SLO.

**The SLO fit is decided from inputs.** Each tick's requests to a node, served by its workers at
the highest cost with the slowest latency both ways, must fit one tick. Encodes now take at least
two steps, so a batch never leaves a tick early and doubles up with the next. Clean plans went from
25% to 40% to keep about a tenth of seeds held to the SLO. Alternative: keep judging fit by node
backlog, which a controller bug could cause and so exempt itself.

**A world frame that keeps failing to encode starts its stream over.** After 5 tries the region
gets a fresh assignment at its last sequence and every resident chunk again. Alternatives: retry
forever, which stalls the region, or skip the sequence, which leaves a gap the node refuses.

**Chunk reads have a budget of 16 per tick.** Even with section reads, a 256-chunk resnapshot is
too much for one tick. Regions take turns at being first.

**A controller restart in the simulation loses its open requests.** Nothing is left to report them,
so the ledger closes them as lost with the process, and the new controller learns the world as a
starting server would.

**Conformance effects are compared by meaning, not bytes.** The two FlatBuffers builders may lay out
the same table differently, and the model does not run A*, so answers compare id, run and sequence.

## Not done

- Phase work left out by choice: the request horizon rule and halo, last-write-wins, the stateless
  cell model, keeping overlap on a split, OpenTelemetry wiring, and failover to another node (the
  simulation still has one fixed region per node).
- `Frames.assignment` and `Frames.mobClass` are still encoded on the main thread. Both are small
  and rare, and offloading them would reorder them against the stream and the requests they lead.
- `maestro-node` has no `--join-timeout` flag.
