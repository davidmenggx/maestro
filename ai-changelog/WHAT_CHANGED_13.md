# Cleanup pass: one state object per node, a declared setting table, and shared geometry constants

This change is a cleanup pass over the whole repository. No behavior was added or removed. The
sweeps still pass at every tier, and the C++ and Java unit suites are green.

## Long signatures replaced

`MobClass` had ten components, four of them booleans in a row, which is a transposition waiting to
happen at any call site that builds one positionally. It is now
`MobClass(classId, Shape, Abilities, malus, maxFollowRange)`, where `Shape` carries width, height
and `maxUpStep` and validates its own footprint, and `Abilities` carries the four
`WalkNodeEvaluator` toggles. `malus` is now copied on the way out as well as in; before, the
accessor handed out the array the record was holding, so the "defensive" copy in the constructor
protected nothing.

`Invariants.check` took six arguments that all came from one `SimRun`, and
`checkAgainstFreshSnapshot` took four of the same. Both now take the `SimRun`.
`checkStateEquality` was only ever called from inside `checkAgainstFreshSnapshot` and was folded
into it.

`FaultPlan.withLoss(double, double)` took two adjacent doubles whose order the reader had to know;
it is now `withDropRate` and `withDuplicateRate`. `Topology` gained `withRegionSize`,
`withRegionsPerSide`, `withMinY`, `withHeight` and `withRelief`, so a test that varies one dimension
of `Topology.DEFAULT` no longer restates five integers.

`SimTransport` passed a bare `boolean toNode` through `transmit`, `arrive` and the `Link` key. That
is now a `Direction` enum, and the ordered-delivery arithmetic that appeared twice (normal send and
partition replay) is one `deliverInOrder` method.

## Scenario is now a declared table rather than twelve fields and a hand-written builder

`Scenario` was a twelve-component record, six of them `double` in a row, plus a 105-line builder
that restated every field three times, plus twelve `MAX_*` constants, plus a test that declared
twelve `Span` locals to assert twelve ranges. Adding a setting meant touching five places, and a
setting whose ceiling drifted from its assertion would fail silently.

Each setting is now declared once, as an enum constant carrying its own range: `Scenario.Count` for
whole numbers (with a floor and the ceiling `sample` draws up to) and `Scenario.Chance` for
probabilities. `Scenario` holds two `EnumMap`s, and the sampler, the validation, the `with` methods
and the coverage assertions all read the same table. The record is 160 lines against 220, the
builder is gone, and the coverage test loops over `values()` instead of listing every setting.

Reads at use sites became `scenario.count(Count.MOBS)` and `scenario.chance(Chance.CHUNK_CHURN)`
rather than named accessors. The draw order and the value of every sample are unchanged, so a
sampled scenario for a given seed is bit-identical to before.

A ceiling bounds the sampler only, and the floor is the validity bound. `SimulationTest` sets
`BLOCK_CHANGES_PER_TICK` above its ceiling on purpose, so making the ceiling a hard bound would have
been wrong.

## One state object per node in `ProbeController`

`ProbeController` kept ten parallel collections keyed by `NodeId`: two sets and eight maps, one per
field. Every operation on a node did up to six hash lookups, and the invariant that they agree was
implicit. They are now one `Map<NodeId, NodeState>`, seeded in region order at construction so
anything iterating the fleet does so the same way whatever order nodes first spoke in. `selfCheck`
went from three passes to one.

The FlatBuffers encoding moved out to a new `Frames` class. It is what tests reach for when they
build the frames a run would build, which they previously did by calling into the controller's own
static methods. `ProbeController` is 604 lines against 792.

Other fixes in the same file: `dropWhere` collected matching request ids into a list and then looked
each one up again, while `expireOutstanding` right above it already removed through an iterator, so
`dropWhere` now does the same; `onChunkUnloaded` rescanned every region calling a per-chunk
residency check when only the region containing the unloaded chunk can change, so it now computes
that region directly; `snapshotted` is a `boolean[]` indexed by region rather than a
`Set<Integer>`; `flushCells` and `flushRequests` no longer copy their batches or build a per-tick
map, they hand the list to the encoder and put a fresh one in the node's state; `regionLoaded`
hoisted two bounds-checked calls out of its inner loop.

The per-tick fault lookup in `Simulation` was a stream filter over the whole fault list; the plan is
now indexed by tick once at construction. `Frames.RegionRuns` sizes its run array from the column
count instead of growing from 64.

## Magic numbers and where the constant tests live

The chunk width 16 was written out in four places (`Topology`, `RegionMap`, `ProbeController`,
`WorldBoundaryTest`) and aliased in a fifth (`SimWorld`). There is now one
`controller.world.Chunks` holding `SIZE` along with `of`, `firstBlock` and `key`, and every block-to
-chunk conversion goes through it. `Chunks.of` uses `Math.floorDiv`, so the conversion is correct
for negative coordinates; the open-coded `x / 16` was not.

`RegionMap` restated `regionSize`, `regionsPerSide`, `extent`, the region count and the chunk
alignment check that `Topology` already had. It now holds the `Topology` and delegates, so the
geometry has one definition. `regionIndex(Pos)` became `regionAt(int x, int z)`, which drops a `Pos`
allocation on the hot path.

The constant-relationship assertions were spread between
`theHarnessConstantsAgreeWithWhatTheyAreSizedAgainst` in `HarnessGuardsTest` and various inline
checks. They now live in one `HarnessConstantsTest`, which runs no simulation and holds only claims
about how constants relate: sampled latency against the tick length, run length against the fault
span, the SLO budget against the admission bound, sampled world shape against what the `Topology`
constructor demands, every sampled setting range being non-empty, comparison node ids clearing real
ones, and `NANOS_PER_STEP * STEPS_PER_TICK == NANOS_PER_TICK` (previously a comment asserting
itself).

## Tests merged and removed

`ModeledNodeTest` is gone. `aGapInTheWorldStreamIsReported` moved to `HarnessGuardsTest` next to the
resume-point test it mirrors. `stateAfterFailoverMatchesAFreshSnapshot` restated what
`Invariants.check` already asserts for every caught-up node on every run, with the same fault shape
as `SimulationTest.nodeDeathDropsItsRequestsAndTheRunStaysDeterministic`; that test now makes the
fresh-snapshot comparison explicitly, and the duplicate is gone.

`BoundaryContractTest` had two tests covering the per-block path type set from two directions;
they are one. `MaestroConfigTest` had four near-identical rejection cases, now one test over a list
of unusable files. `HarnessGuardsTest` had separate percentile and label tests over `SimMetrics`,
now one.

`WireProbe` and `WireProbeTest` were deleted. The class documented itself as a placeholder to be
replaced by real framing code, and what it proved (that the FlatBuffers runtime and the generated
sources are on the mod's classpath together) the controller module now proves at far greater depth.
`Metrics.NOOP` was unused and is gone. `FaultPlan.at` lost its only caller when `Simulation` indexed
the plan.

## C++

`peekLength` returned 0 both for "the header has not arrived" and for "the payload is empty", which
a caller cannot tell apart. It returns `std::optional<std::uint32_t>`. The byte-by-byte header
comments explaining what a shift does were removed and both loops are now written as loops over
`HeaderSize`. `--help` printed a default port that was a copy of the one in `Options`; it now reads
the struct. `test_schema.cpp` had vector lengths written out as literals next to the arrays they
described; those are `std::size` now.

## Design decisions made without instruction

**`Scenario` as an enum-keyed table.** The alternative was to keep twelve named record components
and either keep the builder or add `with` methods per field. That keeps `scenario.mobCount()`
reading slightly better than `scenario.count(Count.MOBS)`, but it leaves the sampler bounds, the
validation and the coverage assertions as three lists that have to be kept in step by hand, which is
the drift the sweeps are supposed to catch and cannot. The table was chosen because it makes the
drift impossible rather than tested for.

**`Chunks` in the `world` package, not in `sim`.** Only harness code uses it today, so `sim` would
have been defensible. It sits with the boundary because chunk residency is part of the world
contract (`WorldEvent.ChunkLoaded` and `WorldSource` already speak in chunk coordinates), and the
controller's own region-to-chunk alignment logic will need it.

**Deleting `WireProbe` rather than keeping it as a classpath guard.** It was the only thing in the
mod module referencing FlatBuffers, so removing it removes a compile-time check that the
`api` dependency reaches the mod. That check is worth little next to a placeholder class that the
real transport will replace anyway, and the mod jar's runtime bundling is unaffected.

**`Transport.disconnect` kept.** It is declared, implemented by `SimTransport` and never called by
anything, which makes it dead by the same test that removed `Metrics.NOOP`. It was kept because it
is half of a connection lifecycle that production shutdown will need, and removing it is a design
change rather than a cleanup.
