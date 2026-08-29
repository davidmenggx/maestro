# Schema revision

Reworked the FlatBuffers wire contract in `schema/` for correctness with Minecraft and to drop redundant fields. Regenerated bindings and kept both build trees green (`make node-test`, Java compile).

## Changes
- Split the old `WorldDelta` into `WorldSnapshot` (carries `columns`, node replaces state) and `WorldDelta` (carries `cells`, node merges). The message type now encodes snapshot-vs-delta.
- `PathResponse`: replaced the `PathStatus` enum with a `reached:bool` (mirrors `Path.reached`); empty `nodes` means no path. Renamed `waypoints` to `nodes` to match Minecraft's `Path`/`Node` terminology.
- Removed `Hello.protocol_version` (controlled fleet, per instruction).
- Documented on `CellDelta` that a block change can alter neighboring cells' `PathNodeType`, so the producer emits every affected cell.
- Trimmed comments across all schema files per the CLAUDE.md conventions (no file-level description headers, no prompt-referencing prose).
- `make schema-cpp`/`schema-java` now clear their output dirs first, so bindings for removed types (e.g. the old `PathStatus`) don't linger.

## Design decisions made without explicit instruction
- Dropped `WorldDelta.base_sequence` and `is_snapshot`. Rationale: the world stream is strictly ordered, so a delta always applies to `sequence - 1`, and the snapshot/delta distinction is now carried by the message type. Alternative kept in reserve: an explicit `base_sequence` if we ever allow non-contiguous delta application.
- Dropped `PathRequest.issued_tick` and `max_horizon`, and `PathResponse.mob_id`. Rationale: minimize the high-volume request/response messages; issue-time and mob identity are recoverable from `request_id` server-side, and the A* search bound is the mob class's `max_follow_range`. Alternative: re-add `mob_id` if the server should not keep a `request_id -> mob` table.
- Removed `PathResponse.path_length`. Rationale: derivable from `nodes`. Open: if the fuzz test compares the malus-weighted A* cost rather than geometric length, add a `cost` field instead.

## Open questions (pending Minecraft 26.1 source confirmation)
NodeEvaluator scope (ground only vs swim/fly/wall-climb), world vertical representation (full-height vs surface-band vs sparse), per-request accuracy/reachRange, and whether `Path`/`PathNodeType` semantics are unchanged in 26.1.
