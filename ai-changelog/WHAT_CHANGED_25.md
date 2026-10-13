# Vanilla parity fixtures: real vanilla searches, replayed on the node

The node's search had only been checked against hand-traced tests. A dev-only server command now
builds seeded terrain, runs real vanilla searches on it, and writes each search as wire frames.
`make capture` drives this on a headless server. The node replays every committed case and must
give vanilla's exact path, `reached` flag and cost. The Java side replays the same files as the
shared wire fixtures. On the first replay the port matched vanilla on every exact case. 12 seeds
of 100 cases are committed (2.6 MB). 1157 cases are exact and all match. 42 of the 43 approximated
cases also match.

## Capture (`server/src/main/.../capture`, `bridge`, `mixin`)

- `PathFinderMixin` wraps vanilla's inner `findPath` call, which runs after the evaluator's
  `prepare` and before its `done`. It reports each intercepted search to an observer, if one is set.
  `Searches.intercepted` accepts only a single target with exactly `PathFinder` and
  `WalkNodeEvaluator`. Phase 4 will reuse this hook for the offload.
- `SearchCall` reads the profile (footprint, step height, door and float flags, all 27 malus
  values), the resolved start node, the target and the search inputs, including the budget as
  vanilla computes it.
- `BlockCells` gives a block's per-block `PathType` (vanilla's own `getPathTypeFromState`) and its
  collision height in sixteenths, rounded up. `LevelWorldSource` is the `WorldSource` over a level.
- `/maestro capture <seed> <cases>` is registered only in dev mode:
  - `Terrain` builds a 128 block area at x = z = 4096. It has hills, terraces and pits, and every
    per-block type: water, lava, fire, magma, campfires, berry bushes, wither roses, rails,
    trapdoors, leaves, snow, carpet and soft floors. It adds patches of mixed hazards, fences,
    walls, gates, panes, stairs, top slabs, cactus, honey, powder snow, cocoa, lily pads, and
    rooms and pens with doors and gates.
  - `ParityCapture` makes mobs without adding them to the world: zombie, baby zombie, villager,
    spider, iron golem, sniffer and cow. It draws difficulty, target, health, follow range, step
    height, door and float flags and a few malus values, then calls the mob's own
    `createPath`. Some mobs start inside a room or pen.
  - Everything runs in one command on the server thread, so no tick changes the world in between.
- `tools/capture-fixtures.sh` and `make capture SEED="..." N=...` boot a dev server on a fresh copy
  of `worlds/perf` and copy each `seed-<n>.bin` into `fixtures/parity`. A capture is deterministic:
  the same seed and case count give the same bytes, whatever order the seeds run in.

## Wire and controller

- `schema/fixture.fbs` adds `ParityFixture`: the framed world stream, then one `ParityCase` per
  search. A case has the entity id, an `exact` flag, and its `MobClass`, `PathRequest` and
  `PathResponse` frames.
- `Frames` moved from the simulator to `controller/src/main` (package `wire`). It now takes the
  area corner instead of the simulator's `RegionMap`. `Frames.response` is new.
  `Frames.assignment` rejects a size that is not whole chunks.
- `ProfileTable` interns profiles by content and gives ids from 1.

## Tests

- `node/tests/test_parity.cpp` replays every fixture. It applies the world stream to a
  `WorldStore`, decodes each frame as the node would, and runs `VanillaSearch`. It prints each
  approximated case that differs.
- `ParityFixtureTest` (controller) checks the wire contract: sequences, every chunk sent once, full
  height columns, per-block types only, one profile per id, and responses at the last sequence. It
  also checks that the encoder rebuilds every frame byte for byte.
- `FramesTest` takes the two wire tests moved out of `HarnessGuardsTest` and adds tests for
  relative positions, responses and area size. `ProfileTableTest`, `BlockCellsTest` (against real
  block states) and `SearchesTest` are new.
- I mutated 20 rules in the node's search, one at a time. 17 make exact cases fail, for example
  the tie order (853 cases), the walked distance (559), door rules, rails, falls, the jump sweep,
  the fence collision sweep and the neighbour scan order. Of the 3 that make none fail, one is a
  no-op (it reorders cases inside one `switch`), one cannot change an answer with the malus values
  drawn (the closed node malus), and one (the floating floor level) never decides a search in these
  worlds.
- `make test` passes, and the node suite passes under ASan and UBSan. A 1000-seed shallow sweep
  and a 300-seed deep sweep pass, as does the boot smoke test. The simulator change only moves
  call sites, so the full sweep was not rerun.

## Design decisions made without instruction

**Exactness is measured per search, not per block palette.** The plan was to put narrow shapes in
a separate tagged set. The first version tagged a case approximated if any block in reach was not a
full-width box. With that rule, door and fence rules were never asserted, because every case near a
door was approximated. Now a probe (`WalkNodeEvaluatorMixin`, inert outside a capture) compares
each `hasCollisions` and `getFloorLevel` answer vanilla gives with the node's cell model. A case is
exact if all of them agreed and the mob stood at the bottom centre of its start node. If every
answer agreed, the node makes the same decisions, so the result cannot differ. Alternative: the
palette rule, which is simpler but leaves door rules untested.

**A fixture is a FlatBuffers container around wire frames, not a bare frame stream.** A reader of a
bare stream cannot tell which channel a frame belongs to, and the `exact` tag needs a place.
Alternative: a one-byte channel tag before each frame, which needs a hand-written reader on both
sides.

**One terrain per seed with many cases.** The world is 64 chunks, about 170 KB, so sharing it keeps
1200 cases at 2.6 MB. The area is 128 blocks so the villager's 48 block follow range fits.
Alternative: one small world per case, which is larger in total and gives fewer search lengths.

**The byte-for-byte rebuild test makes any encoder layout change regenerate the fixtures.** That is
the point of a wire fixture. Alternative: comparing decoded values, which tolerates harmless layout
changes but would miss an unintended one.

**The capture clears vanilla's path type cache for the area.** A block change clears its cache entry
only in a ticking chunk, and a freshly forced chunk may not be ticking yet. A stale entry would make
vanilla search a world other than the one recorded.

**Tag-typed blocks are checked by floor only in `BlockCellsTest`.** Water, lava, fences, walls,
trapdoors and fire get their type from tags, and tags are bound only in a running server. The
fixtures, which come from a real server, cover their types.
