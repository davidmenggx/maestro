# Harness hardening: seam trim, hot-path accounting, scripted traces

The seams and the simulator are unchanged in shape but noticeably tighter. Three main-module
interfaces lost members that nothing needed: `WorldSource.chunkLoaded` is gone, because chunk
residency already arrives as `ChunkLoaded` and `ChunkUnloaded` events and two answers to one question
can disagree; `NodeId` no longer implements `Comparable`, since deterministic order comes from
`LinkedHashMap` rather than sorting; and `Pos` lost its unused chunk accessors. `SeededRng.seed`,
`Simulation.seed`/`transport`/`tick`, `ModeledNode.id`/`queueDepth`, `Digest.mix(ByteBuffer)`,
`FaultPlan.withLatency` and its hand-written `toString`, and `SyntheticWorkload.requestsIssued` all
went the same way. `ModeledNode` and `ProbeController` now mark where their assertion surface starts,
so a getter that exists only for a test reads as such.

Two hot paths were quadratic or allocation-heavy. `ProbeController` was counting in-flight requests
per node with a stream filter over every outstanding request on every incoming request, which is
O(n^2) per tick; it now keeps an `inFlight` counter, and all the per-request bookkeeping funnels
through one `drop`/`forget` pair instead of being repeated at five call sites. Expiry walks
`outstanding` in issue order and stops at the first live entry rather than scanning the whole map.
Snapshot building no longer allocates an `int[2]` per column run: runs pack into a single `int` as
`(length << 8) | pathType` inside a growable `Runs` buffer, which matters at the real region size of
64x64x384 where a region holds thousands of runs. `SimWorld` dropped its `TreeMap` of block overrides
and its `Set` of loaded chunks for flat arrays, making every terrain read O(1) with no boxing.

Constants that have to agree now say so structurally. `Simulation` derives `NANOS_PER_STEP` from
`NANOS_PER_TICK / STEPS_PER_TICK` instead of stating both, and names the mid-tick service point.
`RegionMap` and `SimWorld` reject a size that is not chunk aligned, `SimWorld` is square and sized
from `RegionMap.extent()` so the world extent has one source of truth, and the snapshot's `baseY`
comes from `WorldSource.minY()` rather than a harness constant, which leaves `ProbeController` with no
knowledge of the simulated world at all. The scenario knobs that were six positional constructor
arguments are now a `SimRun.Scenario` record with `DEFAULT` and `SATURATED`.

Test coverage roughly doubled, from 23 to 32. `ScriptedWorkload` is new: it replays a fixed
`List<WorldEvent>` and applies the world mutations as it emits them, which is the shape a recorded
server trace will take. `WorldBoundaryTest` uses it for the five cases a random workload only reaches
by luck: a mob removed while its request waits, a request for a mob that never spawned, a mob asking
twice, a request with no node holding the region, and a block change that lands before the snapshot
and is absorbed by it. `NondeterminismGuardTest` grew from one deliberately bad sample class to ten,
one per rule category, plus a negative control that uses only the seams and must not be flagged, plus
a check that every rule is well formed. `SimulationTest` added per-region snapshot-once accounting,
explicit request-stream drop and duplicate assertions, and post-failover resnapshot and
partition-backlog assertions.

Both mutations tried against the new suite were caught: suppressing the `SUPERSEDED` callback fails
`aSecondRequestForOneMobSupersedesTheFirst` and the request-accounting invariant in six other tests,
and re-snapshotting a region every tick fails `eachRegionIsSnapshottedOnceThenStreamedAsDeltas` and
`aBlockChangeBeforeTheSnapshotIsAbsorbedByIt`.

## Design decisions made without explicit instruction

- **The seed-distinctness test now asserts that N seeds give N distinct digests**, rather than that
  two specific seeds differ. The old form tested a hash inequality and said nothing about the system;
  the new form catches the failure that actually matters, a seed being ignored somewhere in the
  wiring. A false failure needs an FNV-1a collision across 64 bits, so at this sweep size it is far
  below one in a billion, which is well under the flake rate of anything else in the suite.
- **`Metrics.NOOP` was kept even though nothing calls it yet.** `ControllerEnv` rejects a null
  `Metrics`, so a no-op is how a caller says it does not want metrics; production will need exactly
  that until the OpenTelemetry bridge lands.
- **`WorldSource.chunkLoaded` was removed rather than made the single source of truth.** The events
  are not optional: the controller has to react to a chunk arriving or leaving, so it holds that state
  anyway. Keeping the pull method as well invites the two to drift.
- **`ProbeController.players` was deleted rather than left populated.** It was written and never read.
  The `PlayerMoved` and `PlayerLeft` branches stay as documented no-ops, so the boundary still shows
  where the shedding and failover priorities will consume them.
