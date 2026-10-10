# The node can now compute a path: a versioned world store and a port of vanilla's search

Until now the C++ node only parsed flags and bound a port. It now holds an assigned area built
from world frames, and runs a decision-for-decision port of vanilla's `PathFinder` and
`WalkNodeEvaluator` against it. Nothing is networked yet: frames go in through
`WorldStore::apply`, and paths come out of `Search::find`. One input vanilla reads was missing from
the wire, so `PathRequest` gained `max_fall_distance`. Stale comments from before profile interning
and per-chunk state were corrected.

## World store (`node/src/world`)

`Area` is one immutable version of the assigned area. Columns stay run-length encoded and are read
by binary search. A version is a table of chunks, each a table of 256 column pointers, so a delta
copies only the chunks and columns it touches and shares the rest. A chunk with no state, and
anything outside the area or the build height, reads as air, which is what vanilla reads for a
chunk it does not hold.

`WorldStore` applies one `WorldFrame` at a time and publishes a new `Area` through an atomic
`shared_ptr`, so a search pins a version and never sees a frame half applied. It follows the stream
rules the controller already uses: a fresh assignment starts the area as air, its first frame
sets the sequence baseline, and every frame after must be the next sequence. A resume is accepted
only if the node holds that run, region and geometry with at least one frame applied.

## Search (`node/src/search`)

- `pathTypeStatic` and `checkNeighbourBlocks` rebuild the neighbour-dependent types, in vanilla's
  scan order.
- `WalkNodeEvaluator` ports neighbour generation, jumps, falls, door and rail rules, the large-mob
  malus cap and the collision checks. Its method names match vanilla's so the two read side by side.
- `BinaryHeap` is ported with vanilla's tie-breaking, since the order equal-cost nodes leave the heap
  decides which equal path is found.
- `VanillaSearch` ports `PathFinder`, quirks included: the 1.5 heuristic weight, walked distance
  overwritten even when a node is not updated, a cost that is only ever raised, and the result
  ending at the node nearest the target.
- `Search` is the interface, `makeSearch` picks one by `Pathfinder`, and `Request::decode`,
  `Profile::decode` and `encodeResponse` convert to and from the wire.

The core library builds with `-ffp-contract=off`, so float costs round exactly as Java's do.

## Wire and Java

`PathRequest.max_fall_distance` carries `Mob.getMaxFallDistance`. It depends on the mob's target,
health and the difficulty: 3 with no target, up to 16 for a zombie on hard, so it cannot live in the
profile. `WorldEvent.Search` gained `maxFallDistance`, `Frames.requests` encodes it, and
`SyntheticWorkload` draws it: 3 half the time, otherwise 3 to 70. `RegionAssignment` now documents
that `min_xz` and `size` are multiples of 16.

The stale comments fixed: `MobClass.java` and `mob.fbs` said profiles are registered once per mob
type, and `WorldEvent`, `WorldSource` and `Chunks` still described whole-region snapshots.

## Tests

The C++ suite has 89 tests (73 new). They cover the store (decoding, sharing between versions,
sequencing, resume, every malformed-frame case), the static types (including scan order), the
heap's tie order, and hand-traced search scenarios: straight and diagonal paths, step up, walls,
fences, the fall limit, malus, reach range, visit and length budgets, every door rule, rails, the
large-mob cap, the fence collision sweep and jump headroom. Each rule was checked by breaking it on
purpose and confirming a test fails. The suite is clean under ASan and UBSan and builds with Clang.
Java gained `everySearchInputIsCarriedOnTheWire` and range checks for the new field. The Java unit
suites pass, and so does every fuzz tier at its full CI size (20000 shallow, 6000 deep and 2000
coverage seeds).

## Design decisions made without instruction

**`max_fall_distance` is a `ushort`, not the `ubyte` proposed.** A mob with boosted health can
accept a fall above 255 blocks, and a world is 384 tall. A `ushort` is exact without a clamp on the
server. Alternative: `ubyte` with a saturating clamp, which differs from vanilla only for such mobs.

**Run-length columns rather than dense arrays.** A dense 384 by 384 area at full height takes about
113 MB; runs cost what the terrain costs. Identical neighbouring columns in a chunk
share one copy, which collapses flat terrain. Alternative: dense columns, faster to read and far
larger.

**A frame that breaks the contract is rejected whole.** Bad sizes, derived types, positions outside
the area, gaps in a column or a sequence of 0 all return `Malformed` and change nothing. The
modeled node skips bad cells instead, but a node that half-applies a frame silently disagrees with
the controller. Rejecting leaves recovery to the stream layer.

**An edit to a chunk with no state starts it from air.** The controller should never send one,
since Minecraft cannot change a block it does not hold. If it does, the edit lands rather than
being lost. Alternative: reject the frame.

**Caches differ from vanilla's but give the same answers.** The per-search cache of mob types is
vanilla's. Static types are cached too, and vanilla's collision cache is not ported, since boxes
are cheap here. A pinned version cannot change, so neither choice can alter a result.

**Nodes are keyed by full coordinates.** Vanilla's 32-bit node key gives the same key to y and
y + 256. One search cannot span that, so the result is the same, and a full key is simpler to
trust.

**`find` is not `const`, so each thread needs its own `Search`.** Scratch memory (nodes, heap,
caches) is kept between calls to avoid allocating per search, as the spec asks. Alternative: a
stateless search that allocates every call.
