# Phase 5: halos, spares and placement, priority shedding, and stream mode

This change builds the distributed logic of Phase 5. Regions are now rectangles of 256-block cells
that tile a configured square around the origin. Each holder gets its region plus a 64-block halo,
so a search near a region's edge is offloaded instead of searched on the server. Nodes past the
region count are spares. A spare takes the region of a holder that stays down past a grace period,
halves a region that keeps shedding, or becomes a replica of a hot single-cell region and goes back
when the load drops. Requests that do not fit the holder's queue are shed by priority: a mob far
from players or with no target is shed first, and a shed mob keeps its path or idles instead of
being searched on the server. The stream sub-mode sends each request from the encode worker as
soon as it is encoded. The answer cache is gone.

Results:

- Unit suites: 322 Java tests (226 controller, 49 mod, 47 `mixinTest`) and 197 node tests pass.
  The node also passes under ASan with UBSan (g++) and under TSan (clang++).
- Parity fixtures: every exact case still matches. The recaptured fixtures differ from the old
  ones only in the assignment frame.
- Fuzz at CI sizes, every shard passes:
  - shallow, 4 x 8000 seeds;
  - deep, 8 x 2500 seeds. The first sweep failed 4 seeds (3205, 5522, 9911, 16521). All four
    were the harness ending a run before a late region change had settled, fixed below;
  - coverage, 2000 seeds: 73 held to the SLO, 24 of them at the latency edge. The sweep now
    reaches region splits, replicas added and returned, reassignment to a spare, streamed answers,
    and shedding with a player online.
- Boot test passes. E2E passes in every run:
  - shadow: 307 of 307 answers matched vanilla;
  - `on`, batch: 277 of 277 answers moved a mob, no drops; 1 tick late for 183, 2 ticks for 94;
  - `on`, stream: 249 of 249 answers moved a mob, no drops; 0 ticks late for 4, 1 tick for 245;
  - failover: the failover served 91 calls while the node was paused and 37 while it was down;
    answers moved 94 mobs between the faults and 117 after the restart.

## Changes

- **Wire.** `RegionAssignment.size` is now `size_x` and `size_z`, since an area is a rectangle.
  The node's `Geometry` and `Area` index chunks by `chunksX` and `chunksZ`. The parity fixtures
  were recaptured with `make capture`: only the assignment frame of each changed, which was checked
  frame by frame. The conformance fixture was regenerated and has two new cases for rectangles.
- **Regions (`controller.region`).**
  - `Region` is a rectangle with an area: the region and `Region.HALO` (64) blocks around it.
  - `RegionLayout(cells)` is the covered square, `cells` by `cells` cells centered on the origin's
    cell, at most 127 so every area position fits 16 bits. `initial(fleet, spares)` halves the
    square, largest region first and across its longer side, into one region per node past the
    spares, at least one and at most one per cell.
  - `RegionMap` takes its layout, checks that regions are whole cells inside the square, and can
    `split` a region into its halves, `unassign` a node, and list the regions `reaching` a block
    (whose areas hold it). Its `check` finds a square not fully covered.
- **Controller.**
  - `Controller(env, world, out, fleet, Settings)`. `Settings` holds the layout, spare count,
    assumed capacity, `Dispatch` (`BATCH` or `STREAM`) and the `Placement.Policy`. The controller
    builds and owns the region map; `regions()` and `regionsVersion()` expose it.
  - `WorldStreams`: a log covers its region's area. A chunk or block change goes to every log
    whose area holds it, so halo chunks reach both neighbors. Logs and holders can be added and
    removed at runtime; a new holder that is linked gets a fresh assignment at once.
  - `RequestTracker`: positions are relative to the area. The reach check uses the area, so a
    search may read into the halo. Admission sheds a request (`DropReason.SHED`, was
    `QUEUE_DEPTH`) if its holder's queue would answer it past its `Priority` budget. Requests of a
    holder that leaves its region are dropped (`NODE_LOST`) and still count against it until due.
    A batch names the region each request was admitted for. In stream mode each admitted request
    is its own encode job, which sends it; the completion records the send.
  - `Priority`: 10 ticks for a hunting mob near a player, a tick less per 32 blocks to the nearest
    player, two less for a mob with no target, never under 4. Players come from `PlayerMoved` and
    `PlayerLeft`.
  - `Placement` (new): judged at tick start.
    - A holder down (unlinked or out of service) for `graceTicks` (100) hands its region to the
      first spare that is up, which is sent it afresh. The old holder is a spare once back.
    - A region that shed in every 20-tick window for `hotTicks` (200) takes a spare: halved if it
      spans more than one cell (the old holders keep the lower half, the spare takes the upper; both
      sent afresh), else the spare becomes a replica.
    - A replicated region whose sent requests stayed within half the capacity of one holder fewer,
      with no shedding, for `coldTicks` (600) returns its newest holder to the spares.
  - `WorldEvent.PathRequested` carries `hunting`.
- **Mod.**
  - Config keys `areaCells` (default 5) and `spares` (default 1). `subMode: stream` is allowed.
  - `Fleet` reports player positions every 20 ticks and logs the regions and spares whenever they
    change. It watches block changes in every chunk an area can hold, halo included.
  - `PathOffload`: a shed call returns the mob's live path, or null so its goal ends and the mob
    idles. It is not searched on the server. The answer cache (`FRESH_TICKS`, `REUSED`) is
    removed: an answer for a mob that moved on is dropped. A request says whether its mob has a
    target.
- **DST.**
  - `Topology(cells, nodes, spares, ...)` uses the production layout. The world reaches one halo
    past the square, so outer areas hold real chunks.
  - `Scenario` draws the placement timings, the dispatch sub-mode, and the share of hunting mobs.
  - The simulator runs offload work at its finish step, between hand-overs, so a streamed request
    leaves then. Its completion still waits for the next hand-over.
  - The harness reads the controller's live region map. A spare is not held to "caught up".
  - New invariant: no region is left only to dead holders while a live spare could take it.
  - A request counts as servable only once a full send of every area could have reached it:
    each loaded chunk once per area holding it.
  - A run with a quiet tail now also lasts until the controller's last region change has settled,
    since a new holder makes its whole area be read again for every holder of the region. A
    change during that extension extends the run again, at most 4 times.
  - New tests: `PlacementRulesTest` (each rule's exact timing and order), `PlacementTest`
    (reassignment, split, replica and its return, no spare), budget and halo cases in
    `WorldBoundaryTest`, rectangles and halos in the region and node tests. The coverage tier now
    requires splits, replicas added and returned, reassignments, streamed answers, multi-cell
    starts, and shedding with a player online.
- **E2E.** `make test-e2e` adds a run in stream mode (`SUBMODE=stream`). Runs are named
  `offload-submode[-faults]`.
- **CLAUDE.md.** The layout, halo routing, spares, split and replica rules, shedding budget,
  stream sub-mode, no hedging, the removed answer cache, and the DST and e2e scope.

## Design decisions made without instruction

**A down holder gives up its region only if a spare is up to take it.** With no spare, releasing
it gains nothing and turns its later return into a full resend instead of a resume. So the
single-node setup keeps resuming after a pause or a restart, as before.
- Alternative: release it after the grace period anyway and give the region back to whichever
  node comes up first. Correct, but always a full resend.

**A split resends both halves.** The old holder's area shrinks and moves, so it takes the lower
half with a fresh assignment, as the spare takes the upper one. Mobs in the region use the
failover until their chunks arrive.
- Alternative: let the old holder keep serving from its old area until the spare is ready. The
  node holds one area, so this needs either overlapping regions or a node that holds two areas.

**Splits are never merged back; replicas are returned.** A replica costs a full stream, so it
goes back when the load drops. A merge would resend both halves for little gain.

**Hot and cold are judged in 20-tick windows.** Hot is at least one shed request in each window.
Cold is a window with no shed request and at most half of one holder fewer's capacity sent. This
reads "10 s of refusals" and "30 s of low volume" with simple counters.
- Alternative: rates over a sliding window. More state for the same effect.

**A shed request's budget is a step function.** Ten ticks for a hunting mob near a player, minus
a tick per 32 blocks to the nearest player, minus two if not hunting, at least four. It is judged
per call against the holder's queue, so it works the same in batch and stream mode, and needs no
tick-end sort.
- Alternative: admit by priority at tick end. Batch only, and the refusal would come a tick late.

**Players are reported every 20 ticks, by entity id.** Spectators are left out, since they draw no
mobs. A player missing from a report is reported as left.

**The DST samples shorter placement timings than production.** Runs are 60 to 320 ticks, so the
production 10 s and 30 s would rarely fire. The sampler draws grace 10-100 ticks, hot 1-10 windows,
cold 1-30 windows. The default scenario keeps the production values.

**The world in the DST reaches one halo past the covered square.** Mobs and players there are
outside every region, as on a server, and the outer regions' areas hold real chunks.

## Not done

- Sending the area's origin to the node, which the deferred float-rounding and node-hash gaps
  need. The halo did not require it.
- Phases 6 to 8 (stateless mode, metrics and dashboards, live trials).
