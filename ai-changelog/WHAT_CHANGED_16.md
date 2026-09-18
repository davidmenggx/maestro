# Schema audited against the 26.1 pathfinder: per-call search inputs added, six fields removed

The wire contract was checked field by field against the decompiled Minecraft 26.1 source
(`PathNavigation`, `PathFinder`, `WalkNodeEvaluator`, `Path`, `Node`, and every vanilla caller of
`createPath`). Three inputs the pathfinder takes per call were missing, six fields carried nothing
the receiver used, and positions were four times wider than they need to be. This supersedes the
epoch and trace-flag additions in `WHAT_CHANGED_15.md`. The harness was adapted; the unit suites
and a 500-seed run of every fuzz tier pass.

## What the source showed

`PathFinder.findPath(region, mob, targets, maxPathLength, reachRange, maxVisitedNodesMultiplier)`
is the whole computation. Its per-call inputs are the target set, `reachRange` (Manhattan distance
that counts as arrival; callers pass 0, 1, or a POI's `validRange`), `maxPathLength` (a float:
`max(FOLLOW_RANGE, requiredPathLength)`, or an explicit override in one `createPath` overload), and
the visit budget `(int)(maxVisitedNodes * multiplier)`, where `maxVisitedNodes` is set from the
base follow range at construction and only sometimes updated. None of these were on the wire, and
`MobClass.max_follow_range` stood in for all three, which is wrong whenever they diverge.

The start node is not the mob's block position. `WalkNodeEvaluator.getStart` reads `isInWater`,
`onGround`, `canStandOnFluid` and the bounding box, and probes the four corners of the box when the
centre cell is unusable. That is live entity state, so the start is resolved on the server and the
request carries the resulting node.

`PathNavigation.followThePath` reads `path.getNextNode().type` to decide corner cutting, so a
response has to carry each node's `PathType`.

Exactly one vanilla caller passes more than one target (`AcquirePoi`). Matching vanilla's choice
among equal-length paths would mean reproducing `HashSet` iteration order over `Target` objects,
so multi-target searches stay on vanilla and `PathRequest` has one `target`.

`getFloorLevel` reads the collision shape of the block below (slabs, carpets, fences, soul sand)
and `hasCollisions` sweeps the mob's box against block shapes. Neither is derivable from a
`PathType`. The wire does not carry shapes, so the node treats every solid cell as a full cube and
parity is exact only for full-cube worlds. `CLAUDE.md` now says so. Adding a floor-height byte to
`CellDelta` and `CellRun` would cost nothing on the wire (both structs pad to that size already)
and would close the `getFloorLevel` half of the gap; it is left as a decision.

## Schema changes

Added to `PathRequest`: `reach_range:ubyte`, `max_path_length:float`, `max_visited_nodes:ushort`
(already multiplied). Added `PathNode { pos, type }` as the element of `PathResponse.nodes`.

Removed: `PathRequest.mob_id` (the controller maps `request_id` to the mob; the node never read
it), `PathRequest.world_sequence` (with no blocking there is nothing for the node to do with it;
`PathResponse.world_sequence` stays), `PathResponse.computed_tick` (a node has no server tick, and
the last-write-wins rule is arrival order within a tick), `TraceContext.flags` (with every request
traced, sampling is the collector's decision), `MobClass.max_follow_range` (replaced by the per-call
bounds), and the `epoch` fields added in the previous change (see below).

`Vec3i` is now `Vec3s`: three shorts, `x` and `z` relative to the assignment's `min_xz`, `y`
absolute. This halves every position on the request, response and delta paths. A target beyond the
node's area is still representable. `RegionAssignment.max_xz` became `size:ushort`, which makes a
non-square region unrepresentable rather than merely unexpected, and `resume_from_sequence:ulong`
became `resume:bool`, since the node already knows its own sequence and the controller only ever
resumed from exactly that.

A request is now 8 + 2 + 6 + 6 + 1 + 4 + 2 + 24 = 53 payload bytes, of which the trace is 24.

## Epoch removed

`WHAT_CHANGED_15.md` added an ownership epoch to `RegionAssignment` and `Hello`. Working through
what it would catch showed nothing the existing invariants do not already cover: a late answer from
a superseded owner is rejected by `request_id` because the controller drops that node's outstanding
requests on reassignment; a reconnecting node is placed by the `(region_id, last_sequence)` it
reports, which is unambiguous while region ids are never reused and a region's sequence stream is
monotonic across owners; and a node whose assignment was lost in flight reports the region it
actually holds. Those two conditions are now stated in `CLAUDE.md` in place of the epoch.

## Java boundary

`WorldEvent.PathRequested` carries a `Search(reachRange, maxPathLength, maxVisitedNodes)`.
`WorldOutput.PathResult` is now `(mobId, target, reached, nodes, cost, issuedTick)` with
`nodes` a list of `WorldOutput.Node(pos, pathType)`: exactly what `Path(nodes, target, reached)`
takes, plus the issue tick so the server knows the answer's age. `MobClass` lost `maxFollowRange`.

## Harness

`Frames.requests` and `Frames.delta` take the region so positions go out relative; the controller
adds the origin back on responses. `ModeledNode` stores what it is told and reads relative
positions directly. `SyntheticWorkload` draws the search inputs from the seed. The C++
`PathRequestCarriesTraceContext` test was removed: it exercised generated accessors and asserted
nothing about this system.

## Design decisions made without instruction

**Interception moved from `createPath` to `PathFinder.findPath`.** `createPath` has four early
returns and reuses the current path when the target is unchanged; intercepting at its head would
mean re-implementing those. `findPath` is called with exactly the inputs the request needs, and
its `PathFinder` and `NodeEvaluator` types are the scoping check: the Warden's anonymous subclass,
the Creaking's evaluator subclass, and the fly and amphibious evaluators all fall through to
vanilla. The alternative, a redirect of the `findPath` call inside `createPath`, is equivalent but
needs a method descriptor that breaks on any signature change.

**Return the mob's latest fresh result rather than null while a request is pending.** Every
vanilla caller of `createPath` reads the result immediately: `MeleeAttackGoal.canUse` treats null
as "cannot start", `TargetGoal.canReach` treats it as "unreachable", and `MeleeAttackGoal.tick`
adds a 15-tick penalty when `moveTo` fails. A chasing mob's target block changes every few ticks,
so a cache keyed on the exact target would never be hit. Serving the newest result for the mob
regardless of which target it was for, while issuing a new request, keeps the mob moving on a path
one request cycle stale, which is the eventual-consistency model already stated. The first ask
still returns null; whether a one-node placeholder path is better there is left to measurement.

**`max_visited_nodes` carried pre-multiplied.** Sending `maxVisitedNodes` and the multiplier
separately would be faithful to the Java shape but the node only ever uses their product.
