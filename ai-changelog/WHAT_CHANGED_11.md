# Fuzz sweeps split out of the unit suite, and the sampler widened to the whole run

The seed sweeps are now their own test class, Gradle task and CI job. `FuzzTest` carries the JUnit
tag `fuzz`, `:controller:test` excludes that tag, and a new `:controller:fuzz` task includes only
it; `make fuzz` runs the sweeps and `make test` no longer does. `make dst` is gone, replaced by
`make fuzz SEED=n RUNS=n`. The split is 50 unit tests that finish in seconds against 6 sweeps whose
size is a time budget, so the fast suite can sit in front of every build while the sweep runs
beside it. The fuzz task defaults to 2000 seeds (about a minute); CI runs 5000 in a separate job
and uploads the report on failure, because a red sweep is a seed to replay rather than a broken
build. The nine fixed-seed cases that were mixed into `SimulationTest` stayed there, one behavior
each, and the sweeps that used to share the file moved out.

The fuzzer was drawing six settings: node capacity, mob count, request rate, chunk churn rate, and
loss and duplication rates, plus at most one fleet fault. Everything else the simulator can vary was
pinned at a constant, so a sweep of any size explored one geometry, one world shape, one pair of mob
profiles, one latency range, one run length and one fault at a time. The audit of what the system
can vary against what the sampler drew found ten gaps, and all ten are now drawn from the seed.

A new `Topology` record holds the coordinate space and the fleet laid over it: region size, grid
width (so node count is 1, 4 or 9 rather than always 4), world floor, column height and terrain
relief. `minY` mattered most: it was fixed at 0, and every region index, snapshot `base_y` and delta
position on both sides of the wire is computed against it, so a negative floor, which is what the
overworld actually has, had never been exercised. `Scenario` grew from four fields to twelve: mob
class count, block changes per tick, player count, cancel rate, mob churn rate, player churn rate,
stray request rate, and a count of nodes that serve at a quarter of the rest's capacity. `FaultPlan`
now samples its latency range as well as its loss rates, and draws zero to three faults that may
overlap on one node or hit several at once, sometimes killing a node for the rest of the run.
`SyntheticWorkload` draws block changes from all seventeen types a cell may carry rather than two,
places them in a band around the surface rather than one block above it, walks mobs so successive
requests for one mob can land in different regions, and lets players leave and come back. Mob
profiles are drawn per run rather than being two constants, so every field of the class table meets
values that differ between runs.

Counting only the discrete settings, that is about 10,500 geometries by 198,000 load settings by
8,000 latency profiles by 261 run lengths by roughly 10^13 fault plans, or 10^28 combinations
before the six continuous rates, the per-column terrain and the per-tick event draws. In practice
the space is bounded by the 64-bit seed, and `everySeedProducesItsOwnRun` checks that distinct seeds
really do produce distinct runs.

Two invariants were strengthened to match. State-equals-a-fresh-recomputation used to run only in
two targeted tests; `Invariants.check` now runs it at the end of every run, for every node whose
world stream reached the last sequence the controller produced for it, which
`ProbeController.producedSequence` now exposes. The comparison node is built directly rather than
through `sim.addNode`, so checking an invariant no longer changes the fleet the next assertion sees.
A new `theSweepReachesEveryOutcomeItClaimsTo` sweeps 300 seeds and fails unless the sweep actually
produced every drop reason, both a resume and a resnapshot, a duplicated request, a frame held
behind a partition, a link reset with the process alive, more than one region size and grid width, a
world with a negative floor, a fleet with a slow node, a run with no player online, and at least one
node caught up far enough to compare. A sweep that reaches nothing proves nothing, and that check is
what keeps the widened ranges honest.

The wider sweep immediately found a bug in the transport model. `SimTransport.revive` bumped the
connection epoch unconditionally, including for a node that was already alive, and `arrive` discards
any frame whose epoch no longer matches. A fault plan that scheduled two overlapping deaths on one
node therefore produced a `NodeRecovered` for a live connection, which silently dropped the world
frames in flight on it and surfaced as `node-0: world sequence gap, expected 11 got 12` at seed 816.
That is a loss the ordered channel is not allowed to have: on an ordered channel a loss must reach
the controller as a disconnect. `revive` now bumps the epoch only when the node was actually dead.
The old sampler could not reach this, because it drew at most one fault per run. After the fix,
20,000 seeds pass.

`PathTypes` gained `perBlockCount()` and `perBlock(int)` so the workload can draw a cell type by
index; the ordinal list was already there as a static initializer and is now the single source for
both the predicate and the enumeration, with a test that the two agree.

## Design decisions made without explicit instruction

- **A JUnit tag, not a separate source set.** `src/fuzz/java` would need the whole `sim` harness on
  a second classpath or a shared source set to depend on, for no gain: the sweeps use exactly the
  classes the unit tests use. A tag plus a `Test` task is the smaller change and keeps one place
  where the harness lives.
- **`make dst` was replaced rather than kept alongside `make fuzz`.** After the split it would have
  meant "the controller's unit tests", which `make server-test` already covers, and the `SEED=`
  replay it existed for now belongs to the sweep.
- **Heterogeneous node capacity is modeled as `slowNodes`, with the controller still told one
  number.** Admission control assumes a uniform fleet, and a slow node is exactly the case where
  that assumption is wrong; the requests it cannot serve in time expire as `SHED`, which is the
  behavior the SLO policy describes. Giving the controller per-node capacities instead would have
  been testing a feature that does not exist yet.
- **Region size stayed a multiple of 16 and below the 64-block pathfinding horizon.** The simulated
  world is a scaled-down model; a region large enough to satisfy the real horizon constraint would
  make snapshots dominate the sweep's runtime without changing which controller paths run.
- **A node may now be lost for the rest of a run.** The old sampler always paired a death with a
  recovery inside the active window. Permanent loss is the case failover exists for, and it settles
  during the quiet tail because outstanding requests expire, so the run still ends with everything
  accounted for.
- **The caught-up state comparison is skipped rather than failed for a node that is behind.** A node
  that is dead, partitioned or mid-replay at the end of a run has no state to compare, and requiring
  one would rule out the fault plans worth running. The coverage test asserts the comparison happens
  often enough to mean something.
