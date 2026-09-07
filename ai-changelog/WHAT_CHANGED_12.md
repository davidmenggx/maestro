# Sweeps split into tiers, sizes raised, and the harness constants tied to their dependencies

The sweeps are now divided into three tiers by how much work a seed does and how much of the run
that seed controls, and a tier is a task, a tag and a CI job rather than only a way the pipeline
happens to be sliced. `shallow` runs one run a seed with every setting pinned, so the seed moves
only the event ordering and what the network does. `deep` runs several runs a seed, or a run whose
whole configuration is drawn from the seed. `coverage` asserts what the sampler reaches rather than
anything about a single run. A deep seed costs about two and a half times a shallow one, measured,
so one budget across all of them would have been set by the slowest sweep and wasted on the rest.

Each tier has a Gradle task (`fuzz-shallow`, `fuzz-deep`, `fuzz-coverage`) with its own default
size, `make fuzz TIER=deep` runs one of them, and plain `make fuzz` runs all three at their own
budgets. The tiers default to 20,000, 6,000 and 2,000 seeds locally, against a single 2,000-seed
sweep before.

CI went from one job to thirteen: four shallow shards of 25,000 seeds, eight deep shards of 12,500,
and one coverage job. Shard counts are chosen so every job lands in the same wall-clock band despite
the per-seed cost difference, so a push covers 100,000 seeds in each of the two run-level tiers in
the time the slowest single job takes. Splitting this way also means a red build names the tier that
broke before anyone opens a log. The default for a sweep started without a run count, as from an
IDE, went from 8 to 100. The coverage sweep is clamped between 300 and 2,000 seeds rather than
following the run count: it is a claim about the sampler and not about any one run, so sharding it
or running it 12,500 times would cost a great deal and say the same thing.

`FaultPlan` now draws up to five faults a run instead of three. Faults may already land on the same
node, so this mainly deepens the pile-up cases, where a second failure arrives during a recovery
from the first. That is the shape that found the connection-epoch bug in the previous change.

Three cases were deleted from `SimulationTest`. `invariantsHoldOnAReliableNetwork`,
`lossHitsTheRequestStreamAndNeverTheWorldStream` and
`chunkChurnResnapshotsTheRegionAndLeavesRequestsUnservable` each asserted exactly what the
`FuzzTest` sweep of the same name asserts over every seed, so they were one sample of a sweep that
already runs thousands. The nine that remain each pin a behavior to an exact count a sweep cannot
state: which recovery a given fault takes, how many snapshots it costs, and which drop reason a
request gets.

`Scenario` grew a builder, replacing five near-identical `with` methods that each restated all
twelve components. `Scenario.DEFAULT.toBuilder().mobCount(40).build()` replaces `withMobCount(40)`,
and adding a component now costs one setter rather than a new method per component. It also removes
the twelve-argument positional constructor calls from the guard tests.

The rest of the change is about constants that are only correct relative to another constant, which
drift apart silently: the sweep keeps passing, having quietly stopped reaching the case the constant
was sized for. The sampler bounds in `Scenario`, `Topology` and `FaultPlan` are now package-private
instead of private, and `HarnessGuardsTest` asserts the draws span those bounds rather than against
copies of their values, so widening a range cannot leave the assertion behind. A new
`theHarnessConstantsAgreeWithWhatTheyAreSizedAgainst` states the relationships that were implicit:
worst-case sampled latency stays inside half a tick, the shortest fuzzed run leaves room for a fault
to start and finish, sampled relief cannot outgrow the shortest sampled column, and the comparison
node ids clear every id a fleet can assign. `Invariants.FRESH_NODE` is now
`Topology.maxNodeCount()` rather than the magic 900, since that is exactly the property it needs.
The retention test derives its partition length from `ProbeController.RETENTION_FRAMES` and its
block-change rate from the region count instead of hardcoding 200 ticks and 8 changes.

## Design decisions made without explicit instruction

- **Tiers are drawn on cost and on what the seed controls, not on subject matter.** Grouping by what
  each sweep is about (ordering, faults, determinism) would have put sweeps with very different
  per-seed costs in the same budget, which is the problem the split exists to solve. Cost and seed
  control happen to line up: the sweeps that draw the whole run from the seed are also the ones that
  run it twice to check replay. The rule for placing a new sweep is one line, which is what keeps
  the tiers from drifting into taste: one run a seed with settings pinned is shallow, more than one
  run a seed or settings drawn from the seed is deep, and a claim about the sampler is coverage.
- **CI shards each tier instead of running one long job.** 100,000 seeds in a single job is roughly
  an hour, past any sensible timeout, and a job that slow stops being run. The sweeps are
  independent runs over independent seeds, so splitting the seed range across parallel jobs costs
  nothing and the wall clock stays at one shard. `fail-fast` is off, so a bad seed in one shard does
  not cancel the others and every shard reports its own failing seeds. Raising the seeds per shard
  is the knob for a bigger sweep; adding shards is the knob for the same sweep in less time.
- **Total CI compute is about unchanged.** Thirteen jobs against eight sounds like more, but each is
  shorter: the previous job ran every sweep at every seed, and the tiers now run each sweep at the
  seed count it is worth. The coverage tier is one job because sharding a fixed-size claim about the
  sampler would only run it several times over.
- **Five faults per run, not more.** Raising the cap does widen the reachable space, but past about
  five the average run spends its whole length recovering rather than getting far enough to answer
  anything, so the sweep trades coverage of the ordinary paths for more of the same rough ones.
  Five keeps pile-ups common without making them the typical run.
- **Span assertions allow a margin at each end rather than demanding the exact endpoint.** On a wide
  range, hitting the exact highest value in 300 draws is a coin toss, so an exact assertion would be
  testing luck. The margin is a tenth of the range, which every current sampler clears with a
  probability of failure below one in a trillion.
- **`FRESH_NODE` is derived exactly, with no headroom.** Comparison nodes take ids
  `FRESH_NODE + region`, and a run assigns `0` through `nodeCount - 1`, so starting the block at the
  largest node count any grid can have is precisely the non-collision condition. Adding a margin
  would have put back the arbitrary number the change was removing. The relationship is asserted, so
  a wider grid fails the guard rather than silently overlapping.
- **The retention test keeps a fixed multiple of the retention window.** Its partition lasts four
  times `RETENTION_FRAMES`, which is not derived from first principles: a region emits at most one
  frame per tick and only on a tick that changes a block inside it, so the exact multiple needed
  depends on the workload rate. Four is a wide margin at the current rate, and if it ever stopped
  being enough the test fails on the resume count rather than passing for the wrong reason.
