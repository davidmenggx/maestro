# Wire contract moves to per-block PathType, run-length encoded snapshots

Cells on the wire now carry the per-block `PathType` only -- Minecraft's
`getPathTypeFromState`, a pure function of a single block state -- instead of the fully resolved
type. Reading `WalkNodeEvaluator` in the 26.1 jar showed `getPathTypeStatic` is a composition of
three layers: the per-block type, a promotion that reads the cell below when the base type is
`OPEN`, and `checkNeighbourBlocks`, which scans 24 surrounding cells. Only the first layer is a
pure per-cell function, and it is the only one Minecraft's own `PathTypeCache` stores, which is why
that cache's `invalidate` takes a single position. Shipping that layer means a block change produces
exactly one `CellDelta`; shipping the resolved type would have required emitting a 26-cell dirty set
per change (the 3x3x3 cube around the block, minus the cell directly below it, which never depends
on it). `schema/world.fbs` and `schema/common.fbs` record the new contract, including which of the
27 ordinals can appear in a cell -- the 17 `getPathTypeFromState` returns -- and where the other ten
come from. The enum is unchanged: ordinal parity still has to hold and `MobClass.malus` still
indexes across all 27.

Snapshots are now column run-length encoded. A 64x64 full-height region is 1,572,864 cells, so the
previous "a snapshot is just a `WorldDelta` whose cells cover the region" shape reached roughly
25 MB per snapshot. A new `CellRun` struct plus a `WorldSnapshot` table carry each column as
`(length, node_type)` runs, so size tracks terrain complexity rather than world height: a superflat
column is two runs, putting a whole region near 40 KB. The columns are flat parallel arrays rather
than a table each -- `run_counts` gives every column's run count and `runs` is all runs
concatenated, both ordered `(x - min_x) * width + (z - min_z)` against the bounds in
`RegionAssignment`, so a column's slice begins at the prefix sum of the counts before it. Column
positions are therefore implicit, as the snapshot covers the region densely and the bounds already
arrive with the assignment. `WorldSnapshot` is appended to the `WorldMsg` union in
`schema/control.fbs`. `node/tests/test_schema.cpp` gains a snapshot round-trip test over a 2x2
region whose columns deliberately differ in run count, walking the prefix sum the way a node would
and asserting every column tiles the full 384-block height; its existing delta test no longer puts
`WALKABLE` in a `CellDelta`, which the new contract forbids.

## Reversals of earlier decisions

`WorldSnapshot` was merged into `WorldDelta` in `WHAT_CHANGED_3` and is split out again here. That
merge was sound while snapshots were assumed to be sparse, but it does not survive a full-height
region, where a per-cell encoding is roughly 150x larger than a run encoding. The `Column` type
dropped in the same entry stays dropped: columns are expressed as parallel arrays, not as a type.

## Design decisions made without explicit instruction

- **`base_y` lives on `WorldSnapshot`, not per column.** Full-height regions mean every column
  shares one vertical range, so a per-column field would repeat a constant 4096 times and permit
  states that cannot occur. The field only needs to move if columns later carry trimmed vertical
  bands.
- **Column positions are implicit rather than carried per column.** A snapshot covers its region
  densely and `RegionAssignment` already supplies `min_xz`/`max_xz`, so positions are derivable;
  sending them would cost 8 bytes per column to restate what the reader knows. This follows the
  same reasoning that removed `region_id` and `halo` earlier. The cost is a real one: the ordering
  convention is now load-bearing on both sides, which is why the round-trip test exercises the
  prefix-sum indexing rather than a single column.
- **`CellRun` stays a struct instead of splitting into parallel `lengths` and `types` vectors.**
  Struct padding costs one byte per run (2 for `length`, 1 for `node_type`, 1 pad), so splitting
  would save about 25% of the runs array. Keeping the pair atomic was judged worth more than the
  bytes, since two vectors that must stay the same length is a new invariant to violate.
- **`CellRun.length` is `ushort`.** The overworld is 384 blocks tall, so a run cannot exceed that;
  `ushort` leaves headroom without paying for a `uint` on every run. `run_counts` is `ushort` for
  the same reason: a column cannot hold more runs than it has cells.
- **`WorldSnapshot` is appended to the end of the `WorldMsg` union**, keeping the existing union
  ordinals for `WorldDelta` and `RegionAssignment` stable.
- **`CLAUDE.md` now pins the `checkNeighbourBlocks` reproduction requirement.** The scan returns on
  the *first* hazard it finds while iterating `dx` outer, `dy`, `dz` inner over `-1..1`, with no
  priority ordering between hazard types. A cell with `WATER` at `dx=-1` and `DAMAGING` at `dx=+1`
  resolves to `WATER_BORDER` purely because of visit order. A C++ port that iterates differently
  would disagree with vanilla only in cells bordering more than one hazard, which is rare enough to
  pass casual testing and would surface later as unexplained path-cost drift.
