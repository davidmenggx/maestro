# One-expression methods removed where they earned nothing, and vague names replaced

A follow-up cleanup on the previous change. No behavior changed; the unit suites and all three
sweep tiers pass.

## One-expression methods

A method that is a single expression now has to pay for itself. Kept are the ones that name a
derived value (`Topology.extent`, `Topology.nodeCount`), hide real work behind a much shorter call
(`ProbeController.state`, `Frames.vec3`, `RegionMap.count`), supply a default
(`SimRun.violations()`, the `SimRun.of` overloads), or are the only way to read private state
(`Scenario.Count.floor`, `ModeledNode.lastSequence`, every plain accessor).

Removed are the ones that re-expose something already public, or had a single caller:

- `Scenario.count(Count)` and `Scenario.chance(Chance)` wrapped the record's own `counts()` and
  `chances()`. Call sites now read `scenario.counts().get(Count.MOBS)` and
  `scenario.chances().get(Chance.CHUNK_CHURN)` directly. This supersedes what
  `WHAT_CHANGED_13.md` says about how a setting is read.
- `RegionMap.regionSize()` was a pass-through to `topology().regionSize()`, and all three external
  callers were already binding it to a local named `regionSize`. `RegionMap.count()` was kept: it
  has nine callers and is half the length of `topology().nodeCount()`.
- `Chunks.firstBlock` had one caller, which now multiplies by `Chunks.SIZE` directly. `Chunks` is
  down to `SIZE`, `of` and `key`, which also supersedes the helper list in `WHAT_CHANGED_13.md`.
- `SyntheticWorkload.nth`, `HarnessGuardsTest.widths`, and `FuzzTest.coverageSeeds`, each with one
  caller. `coverageSeeds` was also the bound of the loop it sized, so it was recomputed every
  iteration; it is a local now.

## Naming

Variables that did not say what they held were renamed. The worst were in `Frames.RegionRuns`,
where `size` meant the number of runs and `counts` meant runs per column: they are `runCount` and
`runsPerColumn`, and the local `counts` that held a FlatBuffers vector offset is `runCountsVector`.
Elsewhere: `Digest.mix(long v)` and `mix(String s)` take `number` and `text`; `SimMetrics.percentile`
takes `quantile` rather than `p`; `ProbeController.selfCheck` builds `violations` rather than
`found`; `FaultPlan.Partitioned`'s `on` component is `active`; `SimWorld`'s `base` is
`lowestGround`; `MaestroConfig.rootCause` takes `thrown`.

Switch pattern bindings over `WorldEvent` were all named `e`. They are named for the event now
(`began`, `changed`, `loaded`, `unloaded`, `spawned`, `removed`, `requested`, `canceled`), which is
how `Simulation.applyFaults` already read. In C++, `Got` and `Read` in `test_schema.cpp` are `Frame`
and `Decoded` (with the builder offset renamed `FrameOffset`), the `Resource` in `obs.cpp` is
`ServiceResource` rather than `Attrs`, and `asio::io_context Io` is `IoContext`.

Left alone: loop indices, block coordinates (`x`, `y`, `z`), caught exceptions (`e`), and Mixin
callback parameters (`ci`, `cir`), where the name is either conventional or the use is immediate.
