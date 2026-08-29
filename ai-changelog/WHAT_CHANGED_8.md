# Schema union split, boundary contracts made executable, fuzz coverage

Two schema unions were wrong about which channel their members ride, and fixing that closed a real
hole. `RegionAssignment` is out of `ControlMsg`: it travels on the world stream only, because it has
to be ordered ahead of the snapshot and deltas it frames, and being reachable from two channels
invited exactly the race the world channel exists to prevent. `MobClassTable` moved the other way,
out of `RequestMsg` and into `ControlMsg`. It was sitting on the request stream, which is
at-least-once and may drop, so a node could lose the only copy of the profiles every request refers
to and never recover. It is control plane: registered once, required before any request naming a
class, so it belongs on an ordered channel. `RequestMsg` is now `{PathRequest, PathRequestBatch}`
and `ControlMsg` is `{Hello, Ping, MobClassTable}`.

That union had no producer at all, which is why the hole went unnoticed. The boundary now has one:
`WorldSource.mobClasses()` returns the `WalkNodeEvaluator` profiles as a new `MobClass` record
mirroring `schema/mob.fbs`, `ProbeController` sends the table on connect, and `ModeledNode` records
which classes it was told about and reports a violation for any request naming one it was not. Two
mutations confirm the path is live: never sending the table, and forgetting to decrement the
in-flight counter, both fail the suite.

The per-block `PathType` contract is now executable rather than documented. `PathTypes.isPerBlock`
names the seventeen ordinals `getPathTypeFromState` can return, `WorldEvent.BlockChanged` rejects
anything else at construction, and `ModeledNode` rejects a derived type in a snapshot run or a delta
cell. Previously the rule lived only in a schema comment, so shipping a `WALKABLE` would have
surfaced much later as unexplained path-cost drift. `PathTypes.count()` is read from the generated
enum, so the malus-array length check in `MobClass` cannot drift from the schema either.

`WorldEvent` gained `PathCanceled` and `DropReason` gained `CANCELED`. A mob that stops hunting had
no way to withdraw a request, so the fleet kept spending capacity on an answer nobody would read,
and the shedding policy in `CLAUDE.md` refers to mobs "not actively hunting", which the controller can
only know if told. Adding the event to the sealed interface broke the controller's switch at compile
time, which is the boundary working as intended.

Chunk unload now means something. The controller drops a region from its snapshotted set when one of
its chunks leaves, so requests for that region are refused until the chunk returns and a fresh
snapshot goes out; previously it kept streaming deltas for a region whose state it could no longer
read. `SyntheticWorkload` reaches every event the boundary defines, including chunk churn, cancels
and player departure, so the seed sweep is a real fuzz over their orderings; the chunk churn rate is a
`Scenario` field, off in `DEFAULT` and on in the new `CHURN`, and `Scenario` moved out of `SimRun` to
its own file.

Invariants and validation grew. `Invariants.check` now also runs `ProbeController.selfCheck`, which
verifies the per-mob index and the per-node in-flight counters agree with the outstanding set and
that the admission bound was never crossed, and it checks the modeled network never delivered more
frames than it sent plus duplicated. Argument validation landed on the paths where a bad value would
otherwise corrupt a run quietly: duplicate node ids, zero-length runs, a second transport listener,
connecting to a node outside the fleet, a region index out of range, a block outside the world, a
non-chunk-aligned region or world size, a mob class with the wrong malus length, and a `PathResult`
computed before it was requested. All of these throw rather than using `assert`, so they hold in
production too.

Tests went from 32 to 51. New `BoundaryContractTest` covers the per-block set, the derived-type
rejections, event field validation, mob class copying and result ordering. `WorldBoundaryTest` gained
cancellation, chunk unload and reload, and the unregistered-class case. `SimulationTest` gained the
mob class table reaching every node, and a chunk-churn sweep that also re-runs each seed to confirm
more event kinds cost nothing in determinism. `HarnessGuardsTest` covers the machinery the assertions
themselves lean on, including the percentile the SLO check is computed from, and every new guard.

## Correction to WHAT_CHANGED_6

That entry justified sending `RegionAssignment` on the world channel with "the schema already allows
`RegionAssignment` in both unions". Allowing it in both was the defect, not the justification. The
schema now permits it on the world stream only.

## Design decisions made without explicit instruction

- **`MobClassTable` moved channels rather than being retried.** The alternative was leaving it on the
  request stream and having the controller resend until acknowledged, which means inventing an ack
  the protocol does not have. An ordered channel already provides the guarantee.
- **A cancel for a mob with nothing outstanding is a silent no-op**, not an error. The mod cannot
  know whether the controller still holds a request, so requiring it to only cancel live requests
  would make the boundary harder to use than it needs to be. Both paths are covered by a test.
- **Chunk unload drops the region rather than pausing the stream.** Pausing would need the controller
  to buffer deltas for an unknown period and then decide whether the buffer is still valid; a
  resnapshot on return is one code path and is what the far-behind case already does.
- **`float[] malus` stayed an array inside a record.** It is copied in and out of nothing, but record
  equality on an array is reference-based, so `MobClass` must not be used as a map key. A
  `List<Float>` would have fixed equality at the cost of boxing the table on every encode. The
  javadoc says which.
- **`ModeledNode.id()` came back.** It was removed last round as unused; the transport accounting
  invariant needs it to build metric labels, so it is no longer speculative.
