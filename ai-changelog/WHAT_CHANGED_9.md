# Flow fields and `Hello` dropped, and the simulator now fuzzes its own settings

Flow-field pathfinding is gone from the design. `CLAUDE.md` had it in three places: as a reason to
prefer the batch sub-mode, as the fallback when the fleet is saturated, and as an excuse for path
cost drifting from vanilla in the fuzz comparison. It added a second pathfinding algorithm to build
and a second reason a measured path could differ from vanilla, which would have made the batch and
stream benchmarks hard to read. The saturated-fleet fallback is now the shedding policy the SLO
section already defines: drop mobs furthest from a player or not actively hunting. No code changed,
because none of it was built yet.

The bigger change is that a seed now picks the run's settings, not just its event ordering. Before,
each test named a fixed `Scenario` and a fixed `FaultPlan`, and the seed only moved the workload
around inside them, so the sweep never explored load, chunk churn, loss rate or fault timing.
`Scenario.sample(Rng)` draws node capacity, mob count, request chance and chunk churn chance from
their full ranges; `FaultPlan.sample(Rng, nodeCount, activeTicks)` draws loss and duplication rates
and may schedule one node death or one partition. `SimRun.fuzzed(seed, ticks)` derives both from the
seed and runs them. The new `SimulationTest.fuzzedLoadAndFaultsHoldTheInvariantsAndReplay` sweeps
those runs, checks the invariants and re-runs each seed to confirm the digest is stable. The named
`Scenario` constants stay for tests that need one specific pressure.

A mutation shows this finds things the fixed scenarios do not. Replacing the admission estimate
`Math.max(reportedDepth, inFlight)` with the stale reported depth alone is caught by the fixed
`SATURATED` test, but the fuzz sweep also finds it on its own at seed 52, where it happened to draw
capacity 1 with 48 mobs. The failure message now prints the drawn `Scenario`, so a failing seed says
what load it was under.

Sampled faults are constrained to start and end inside the active part of the run, before the quiet
drain ticks. Without that, a run could end with a node still dead and requests still outstanding,
which would fail the "every request was answered or dropped" invariant for a reason that is about
the test setup rather than the controller. `HarnessGuardsTest` asserts both that the samples span
their ranges and that faults stay inside the window.

`Hello` is gone from `schema/control.fbs`. It had been reduced to a single `node_id:string` field
(`protocol_version` went in WHAT_CHANGED_1, `stateless` in WHAT_CHANGED_3), and that field is
redundant: `Transport.connect(NodeId, endpoint)` means the controller dials a node it has already
named, and every callback carries the `NodeId`, so a node never has to announce who it is. Nothing
produced the message and its only consumer was an empty case in `ModeledNode`. Resume-from-N will
need a node-to-controller handshake so the node can report its last applied sequence, but that is a
different message in the other direction, and it should be added with the resume logic rather than
kept as an empty shell. `ControlMsg` is now `{Ping, MobClassTable}`.

Three smaller things. British spellings were replaced with American ones throughout, including in the
earlier changelogs, which renames `WorldEvent.PathCancelled` to `PathCanceled` and
`DropReason.CANCELLED` to `CANCELED`. And a pass over the comments removed restatements of things
the reader already knows (several comments explained what TCP does), phrases like "TCP-shaped", and
sentences that only added emphasis. The outbox comment in `ProbeController.offload` was rewritten to
say plainly what the method does: store the finished frame under its sequence number, then send
every frame from the lowest unsent sequence up to the first missing one. A schema comment said
"phi-accural"; it now says "phi accrual".

Controller tests went from 42 to 45. The C++ node tests, the boot smoke test and 200 fuzzed seeds
all pass.
