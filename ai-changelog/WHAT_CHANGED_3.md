# Tightening: schema, LLVM style, node cleanup

## Schema
- Merged `WorldSnapshot` back into `WorldDelta`: a snapshot is just a frame whose `cells` cover the whole region, and the node merges every frame, so no separate type is needed. Dropped the `Column` type (cells only).
- Removed `region_id` from `WorldDelta` and `PathRequest`. One connection maps to one region, so the region is implied by the stream.
- Removed `Hello.stateless` (stateless-vs-stateful is a fleet-wide server setting, not per node).
- Removed `halo` from `RegionAssignment` (fleet-wide constant, configured once).

## Node
- Deleted `node/include/maestro/version.hpp` and its uses. The fleet is rebooted together after any upgrade, so a version/handshake is pointless (`protocol_version` was already gone).
- Rewrote `main.cpp` argument parsing to a `std::from_chars`-based parser returning an `ArgsResult` (`Parsed`/`Help`/`Error`); it validates the port range and missing values, with no exceptions or `std::exit`.
- Documented the little-endian bit math in `framing.cpp`.

## Style
- Migrated the C++ to LLVM naming (UpperCamelCase variables/constants, lowerCamel functions: `HeaderSize`, `peekLength`) and backticked identifiers in comments. Added the backtick convention to `CLAUDE.md`.

## Design decisions made without explicit instruction
- Kept `region_id`, but only on `RegionAssignment`, as a server-assigned monotonic id (single orchestrator, so no UUID needed). Rationale: a stable handle is useful for topology/observability and reassignment, but it does not belong on high-volume per-message payloads.
- Kept `PathRequest.mob_id` (not asked to remove). It supports node-side per-mob tracking; it is recoverable from `request_id` server-side, so say the word if you want it dropped too.
