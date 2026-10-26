# Placement fixes, kept halves on a split, and audit cleanup

This change fixes the problems the audit of the Phase 5 change found in how regions move between
nodes. A cold region now returns a holder that is down before its newest one, so it no longer
leaves itself to a dead node. Spares are kept for failover: a hot region takes a spare only while
more spares are up than holders are down, and a region whose holders are all down takes a replica
from another region when no spare is up. A split no longer takes the whole region offline: the
lower half shares the region's area corner and lies inside its area, so its holders keep what they
hold, told by a new wire field, and only the spare's upper half is sent afresh. The audit's test
gaps, dead code and stale docs are cleaned up too.

Results:

- Unit suites: 327 Java tests (231 controller, 49 mod, 47 `mixinTest`) and 201 node tests pass.
  The node also passes under ASan with UBSan (g++) and under TSan (clang++).
- Conformance: the regenerated fixture has 8 new cases; `ModeledNode` and the C++ node pass all
  of them. Parity fixtures are unchanged and every exact case still matches.
- Fuzz at CI sizes, every shard passes: shallow 4 x 8000, deep 8 x 2500, coverage 2000 (73 seeds
  held to the SLO, 39 halves kept). The first deep sweep failed seeds 146 and 2613, fixed below.
- Boot test passes. E2E passes in every run: shadow (299 of 299 matched), `on` batch and stream,
  failover in batch and stream, and the new split run (the region was halved 17 s in, node-0 kept
  its half, and answers moved 282 mobs after).
- Fix found by the deep sweep: after a split, the parent's log still listed its holders, so a frame
  of the parent finishing its encode later went to a holder that had started the lower half over
  (a sequence gap, or a delta outside the smaller area). The parent's log now drops its holders.

## Changes

- **Wire.** `RegionAssignment.from_region_id`. When set, the node must hold that region of the run
  at `sequence`, in an area with the same corner, `min_y` and `height` that contains the new one.
  It keeps what lies inside the new size under the new `region_id`, and the stream goes on. It must
  not also set `resume`. The field is omitted when 0, so the parity fixtures did not change.
- **Node.** `Geometry::holds`, `Area::cropped` (shares every chunk inside), and a new
  `ApplyStatus::CannotKeep` for a kept area of a stream the node does not hold, at another
  sequence, or larger than the one held. Like any refused frame it drops the world and the link.
- **Controller.**
  - `Placement`:
    - A cold region returns a holder that is down first, else its newest (B1).
    - The hot rule takes a spare only while more spares are up than holders are down (M2a).
    - A holder down past its grace with no spare up: if its region has no holder up, the newest up
      holder of the first region, in id order, that keeps another holder up moves to it, sent
      afresh. Metric `region.replica.reclaimed` (M2b).
    - A split keeps the old holders on the lower half. `Placement.Changes.regionSplit` replaces
      `regionAdded` and `regionRemoved`.
  - `WorldStreams.split`: the lower half's log goes on from the parent's sequence. A greeted holder
    that was sent every frame the parent's log produced keeps its resident chunks inside the lower
    area and is sent a kept assignment (metric `world.kept`). Any other holder starts over, as
    before. Chunks the parent still had to read carry over. The upper half starts empty.
  - `Frames.keptAssignment`.
  - `Regions`: a read-only view of the region map. `Controller.regions()`, `RequestTracker` and
    `WorldStreams` use it; only `Placement` and `RegionLayout` change the map. `RegionMap.NONE`
    became `Regions.NONE`; `RegionMap.exists` (used only by tests) is gone.
- **Mod.** `Fleet` logs what became of the requests so far with the spare nodes line.
- **Tests.**
  - `PlacementRulesTest`: replica return with a down holder, the spare kept for a down holder,
    reclaiming a replica, a down replica of a served region left in place, and the new split order.
  - DST: a split checks the old holder kept its half; a new `PlacementTest` case reclaims a replica.
    `ModeledNode` crops like the node.
  - New DST invariant, judged at every tick: no region left only to dead holders past its grace
    plus 2 ticks while a spare is up. A run with reassignment disabled fails it, as checked by hand.
  - Conformance: a fresh assignment of another region replaces the area even behind it; a kept area
    goes on under a new id; a cell past a kept area's new edge is refused; and kept areas of another
    region, run or sequence, a larger one, and one that also resumes are refused.
  - Node unit tests for `Area::cropped`, kept assignments, and a fresh assignment of another region.
  - E2E: two new default runs. `on-stream-failover` runs the failover faults in stream mode.
    `on-batch-split` adds a spare node, one request a tick per node, and a square of 2 by 2 cells,
    so the region sheds and is halved. No node may refuse a world frame, and answers must move mobs
    after the split.
- **Docs.** `DropReason.NO_NODE` says area, not region. The `PathOffload.wants` comment no longer
  claims it refuses nothing the controller would take. `Transport.send` says an offload worker may
  call it. `RegionLayout.halves` says the lower half keeps the corner. A misplaced comment in
  `test_world_store.cpp` is fixed. `Scenario` uses `Placement.WINDOW_TICKS` instead of a copy.
  CLAUDE.md describes the new placement rules, the kept assignment, the invariant and the e2e runs.

## Design decisions made without instruction

**A replica is reclaimed only for a region left with no holder up.** A dead replica of a region
that a live holder still serves keeps its place until a spare is up or the region cools.
- Alternative: replace any dead holder with another region's replica. That moves capacity between
  regions for no gain in availability.

**The reclaimed replica is the newest up holder of the first region, in id order, that keeps
another holder up.** Simple and deterministic.
- Alternative: take it from the region with the least load. Needs the load windows here, and the
  region it leaves may shed either way.

**The lower half's log goes on from the parent's sequence.** The kept holder's resident chunks then
keep their sequences, so the answer check stays valid, and a holder that reconnects right after the
split can resume.
- Alternative: start the lower half at sequence 0 and rewrite every carried chunk's sequence. More
  bookkeeping, and a reconnect would need a full resend.

**A new node status, `CannotKeep`.** It names the refusal in the node's log.
- Alternative: reuse `CannotResume`, which would read as a failed resume.

**Requests in flight at a split are still dropped.** They name the parent region, which the node
answers as if it held no area once it applies the kept assignment.
- Alternative: keep the ones starting in the lower half. They would still be answered as if no area
  were held, unless the controller resent them under the new id.

**The e2e split run uses a crowd.** At one request a tick, a request is shed only when four arrive
in one tick, so 8 husks whose villagers soon die never shed in every second for 10 s. The run gives
every mob Resistance V, adds the iron golem, 30 more husks and 8 more villagers, and waits up to
120 s for the split.
- Alternative: a node flag that slows searches. A test seam in the node for one e2e run.

**The e2e split run cannot tell a kept half from a fallback resend.** It checks that no node
refused a world frame and that answers move mobs after the split. The e2e world does not change
after setup, so the parent's log is always drained and the kept path is taken. The DST checks the
kept state exactly.

## Not done

- The fuzz sweep never reclaims a replica (0 in 2000 coverage seeds): it needs a replicated region
  and another region's holder dying with no spare up. `PlacementRulesTest` and a scripted
  `PlacementTest` case cover it.
- The failover e2e run failed once with mobs that stopped asking for paths after the node restarted
  (one request in 11 s), then passed on every rerun. Mob activity there depends on the persistent
  dev world; the run does not touch the changed placement code.

- The other audit items the user did not pick: the shedding budgets against the 3-tick SLO, the
  "Hot nodes" sentence in CLAUDE.md, spares in a HALF_OPEN circuit, replica load sharing, and
  duplicate chunk reads.
