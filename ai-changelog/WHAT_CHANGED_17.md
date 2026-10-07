# Search selector, derived trace ids with a controller run id, and a collision height per cell

Three decisions from review applied to the schema and the boundary. The unit suites and a
500-seed run of every fuzz tier pass.

## Search selector

`PathRequest.pathfinder:Pathfinder = VANILLA` names which search answers a request, so different
implementations can be measured against each other on identical inputs. `VANILLA` is the
decision-for-decision port of `PathFinder` and `WalkNodeEvaluator`; other entries are appended as
they exist. It is the FlatBuffers default, so a request that asks for `VANILLA` carries no byte for
it. On the node this is one interface with one implementation per enum entry; on the mod it is a
setting the controller stamps on each request. Neither exists yet.

## Trace ids derived, and a controller run id

`TraceContext` is gone from `PathRequest`. A request's trace id is `(run, mix(request_id))` and its
span id is `request_id`, where `mix` is the splitmix64 finalizer written out in
`schema/pathfinding.fbs`. Both sides compute the same ids, the request payload drops from 53 to 29
bytes, and under DST a seed names its traces.

`run:ulong` is new on `RegionAssignment` and `Hello`. It identifies one controller process and it
is needed on its own, not only for tracing: region ids and sequence numbers restart with the
controller, so after a server restart a node reporting `(region 0, sequence 500)` could land inside
the new run's retention window and be replayed deltas that follow a different snapshot. The
controller resumes only a node whose `run` is its own; a node told to resume under another run
records a violation. `ProbeController` draws its run from the controller `Rng` stream, which
nothing else reads, so every other draw in a seeded run is unchanged.

This is the field the previous change removed, with a different meaning: the epoch guarded owner
changes, which the request and sequence rules already cover; the run guards controller restarts,
which they do not.

## Collision height per cell

`CellDelta` and `CellRun` gained `floor:ubyte`, the top of the block's collision shape in
sixteenths (16 for a cube, 8 for a bottom slab, 24 for a fence, 0 for no collision). Both structs
already padded to that size, so snapshots and deltas are the same length as before. A run now
breaks on a floor change as well as a type change, so a slab on top of stone is its own run. This
is what `getFloorLevel` reads from the block below a node; `hasCollisions` still has no shape to
sweep against and treats a cell as a full-width box of that height, which `CLAUDE.md` now says.

On the boundary, `WorldEvent.BlockChanged` carries `floor` and `WorldSource` gained `floorAt`.
`Cells` holds the two constants the boundary and the encoder share. `SimWorld` tops a quarter of
its columns with a slab and gives block changes a height that matches their type, so the run split
is exercised and the fresh-snapshot comparison covers floors. The C++ snapshot test now has a
column whose runs differ by floor alone.

## Heartbeats

Unchanged. Pushing a heartbeat from the node would remove `Ping` and the sequence, but the
request-reply form measures round trip for free and keeps the cadence in one place; phi accrual
works on either.

## Design decisions made without instruction

**`floor` quantised to sixteenths in a byte.** A float would be exact for every shape but costs
four bytes and breaks the struct padding that makes this change free. Vanilla shapes sit on a
sixteenth grid, so the byte reproduces `getFloorLevel` bit for bit for them, and the byte still
holds heights up to nearly sixteen blocks.

**`run` on `Hello` as well as `RegionAssignment`.** The controller cannot tell a node from a
previous run apart by sequence alone, which is the whole point, so the node has to say which run it
holds.

**Selector on the request rather than the assignment.** Per node would also work and would cost
nothing either way, but per request lets one fleet serve a comparison sweep and lets the parity
test send one input to every implementation.
