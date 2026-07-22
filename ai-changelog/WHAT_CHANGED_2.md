# Schema finalization (land-mob scope, PathType enum)

Resolved the open questions from WHAT_CHANGED_1 and finalized the wire contract. Regenerated bindings; both suites green (`make node-test`, Java compile).

## Changes
- Scoped the offload to land mobs only (Minecraft's `WalkNodeEvaluator`). Updated `CLAUDE.md` (COMPUTATION SEMANTICS, wire-protocol note, TESTING).
- `schema/common.fbs`: added a `PathType` enum mirroring the Minecraft 26.1 enum (27 values, ordinals pinned to match `PathType.ordinal()`). `CellDelta.node_type` and `Column.node_types` are now typed `PathType`; the `MobClass.malus` array indexes by the same ordinals.
- `schema/mob.fbs`: reworked `MobClass` to the `WalkNodeEvaluator` input set: `width`, `height`, `malus[]`, `max_up_step`, `can_pass_doors`, `can_open_doors`, `can_float`, `can_walk_over_fences`, `max_follow_range`.
- `schema/pathfinding.fbs`: added `PathResponse.cost` (malus-weighted A* cost, the fuzz-test metric). Confirmed `nodes:[Vec3i]` + `reached:bool` matches the 26.1 `Path(nodes, target, reached)` constructor; `target` is not echoed back (the server already has it from the request). No per-request accuracy field (fixed value).
- `CLAUDE.md` TESTING: fuzz metric is malus-weighted A* cost; test worlds are superflat (single vertical band per column, no caves/overhangs).

## Design decisions made without explicit instruction
- Encoded `PathType` as a schema enum rather than an opaque `ubyte`. Rationale: pins the ordinal contract in one shared place for both languages. Safe because the mod is pinned to 26.1, so the enum never drifts.
- Dropped `MobClass.can_swim` and `avoid_water`. Rationale: swimming is out of scope (land only), and water avoidance is already expressed by the WATER/WATER_BORDER entries in the malus table plus `can_float`. Alternative: keep them if we later widen scope to amphibious mobs.
