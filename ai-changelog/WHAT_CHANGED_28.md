# Audit fixes: true SLO check, safer seams, stronger parity coverage

This round fixes what the audit of the parity work found. The spec and code no longer claim that
vanilla's search sees entities: it does not, because it searches a `PathNavigationRegion`, which
holds none. The DST now checks the real SLO (p99 below 3 ticks, a timeout counting as a miss) in
every run that fits the fleet's capacity, instead of a bound that could never fail. The capture
reads each search's profile through the observer's own table, so no record keeps the shared malus
buffer. `MobClass` compares by content. `PoolOffload` keeps its exactly-one-outcome contract after
`close`. The boot smoke test boots the released mod alone. The config refuses unknown keys. The node
rejects frames that would overflow or that its collision check cannot see. The capture now draws
step heights up to 2.5, and the parity test proves that block changes, the visit budget and
two-block climbs each decided some exact case. The fixtures were recaptured.

## Changes

- Docs: CLAUDE.md, `walk_node_evaluator.hpp`, `ApproximationProbe` and `fixtures/README.md` say
  that only the world border is ignored on purpose, and that a cell's collision height is read
  without the mob (powder snow and scaffolding can differ). `ParityCapture.removeEntities` is gone.
  The capture's start check reads blocks only (`noBlockCollision`).
- SLO (DST):
  - `SimRun.fitsCapacity()`: the fault plan is clean (no loss, no failed encode, no fleet fault)
    and no node ever ended a service with requests still queued (`ModeledNode.backlogged`).
  - `Invariants.checkSlo`: p99 over answers plus timeouts must be below `TICK_BUDGET` (strict).
    Applied only when the run fits capacity. `violations(double)` is gone.
  - `SimMetrics.percentileWithMisses`. `FaultPlan.clean()`. One sampled plan in four is clean.
  - The coverage sweep asserts that some run with answers was held to the SLO. The shallow
    reliable sweep asserts that most runs were.
- Capture: `SearchCall` carries the interned `MobClass` instead of the shape, abilities and malus.
  `Searches.Observer` gives the `ProfileTable` to intern into. The mixin's malus buffer is scratch
  that nothing keeps. Step heights 1.0, 1.5, 2.0 and 2.5.
- `MobClass`: `equals`, `hashCode` and `toString` use the malus content.
- `LevelWorldSource.uniformCell` returns `NOT_UNIFORM` for a chunk that is not loaded.
- `PoolOffload`: a job submitted after `close`, or dropped by it, queues its failure. `drain` runs
  only what was queued before it started.
- Build: `runReleaseServer` runs the main source set alone. `make test-boot` uses it and fails if
  `maestro-dev` loads. One `ProcessResources` block for both mods. The dev mod declares
  `fabric-api`.
- Config: a key that is not a setting aborts startup. The stale `devMode` key was removed from
  `server/run/config/maestro.json`.
- Controller (simulated):
  - A request whose start or target the wire cannot carry is dropped as `NO_NODE` before it is
    batched, so its encode cannot fail on every retry. `Frames.fits` added.
  - A malformed answer is counted and ignored; the request stays outstanding and times out.
  - A request naming a profile the world does not hold is dropped as `CLASS_UNREGISTERED`.
  - Removed the unreachable admission self-check and the unreachable `!greeted` branch. Fixed the
    profile-ordering comment.
  - `checkAgainstFreshSnapshot` no longer fails when every chunk of a region is unloaded.
- `NondeterminismGuardTest` also scans `ProbeController`, and refuses hashed collections and the
  `Set.of`/`Map.of` family, whose order varies per JVM.
- Node:
  - `WorldStore` checks `min_y + height` in 64 bits and refuses a cell `floor` above 32
    (`MaxFloor`). `Cells.MAX_FLOOR` is 32 on the Java side too, and the schema says so.
  - `encodeResponse` returns false instead of truncating a position past 16 bits.
  - `VanillaSearch` cannot be copied.
- Parity test: counts exact cases whose answer differs on the world before any delta, differs with
  an unlimited budget, and climbs two blocks between nodes. Each must be above zero.

## Results

1163 exact cases, all matching (55 decided by a block change, 53 by the visit budget, 17 climbing
two blocks). 34 of 37 approximated cases match. 110 node tests pass, also under ASan and UBSan, and
118 Java tests pass. The release boot passes and loads no dev mod. A re-capture is byte-identical.
The local fuzz tiers pass, and a parallel scan of the CI seed ranges (shallow and deep fuzzed runs
up to seed 100000) found no violation. All 80000 scanned shallow runs and 8267 of 94000 fuzzed runs
were held to the SLO.

## Design decisions made without instruction

**"Fits capacity" is measured, not predicted.** A run fits if its fault plan is clean and no node
ever kept requests past a service. Alternatives: a worst-case bound (twice the mob count at most
the slowest node's capacity), which is never wrong but almost never true; or an expected-load
bound, which some seeds would fail without any bug.

**A timeout counts as an SLO miss.** It ranks above every answer. Without it, a run that loses
most requests could still pass.

**One sampled fault plan in four is clean.** Every sampled plan used to draw nonzero loss and
encode failure, so no fuzzed run could be held to the SLO. Alternative: a separate sweep with the
faults stripped, which costs another run per seed.

**Faulted runs are no longer latency-checked at all.** They used to check answered requests only,
against 3 ticks. Alternative: keep that weaker check beside the SLO.

**The floor limit is 32, not 24.** 32 is what the collision check can see. 24 is vanilla's
tallest, but a limit the server could exceed would make a node drop its state and reconnect on
every resend. Both sides now share 32.

**A malformed answer leaves its request to time out.** Alternative: drop it at once, which needs a
drop reason for a misbehaving node.

**A position past the wire range is dropped as `NO_NODE`.** Its doc now covers this. Alternative: a
new drop reason.

**Unknown config keys abort startup.** This matches "a malformed or incomplete file aborts
startup". Alternative: log a warning.

**`drain` stops at what was queued when it started.** Otherwise a failure that resubmits to a
closed pool would loop forever inside one drain.

**Profiles are interned for every observed search.** Fixture profile ids can now skip numbers,
which matches what the production path will do.
