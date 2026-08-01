MAESTRO

PROJECT OVERVIEW

Server-side Minecraft 26.1 mod, pinned permanently (no version updates). Offloads in-game A* pathfinding computation from the Java server to horizontally scalable C++20 compute nodes, allowing mob counts beyond vanilla limits. Purpose is educational: an experiment in latency-sensitive, high-availability distributed systems, not a polished gameplay mod.

COMPONENTS
Java server mod: authoritative world state, mob/tick logic, node orchestration, failover.
C++20 compute nodes: run A* pathfinding on world data sent by the server.
Wire protocol: Flatbuffers. Blocks sent as Minecraft PathType enum ordinals, not raw block types (the PathType enum is mirrored in the schema so the ordinal contract is explicit). Cells carry the per-block PathType only (getPathTypeFromState, a pure function of one block state); neighbor-dependent and mob-dependent types are resolved on the node at query time, which keeps the delta contract at exactly one cell per block change. Region snapshots are column run-length encoded, so their size tracks terrain complexity rather than world height. Mob metadata sent as low-cardinality IDs plus relative start/target positions and the WalkNodeEvaluator inputs (footprint, per-PathType malus, and door/step/float/fence capabilities). Buffers reused, serialization done off main thread to avoid GC/lag.

COMPUTATION SEMANTICS
Best effort and eventually consistent. The node rebuilds getPathTypeStatic from the per-block types it holds: the OPEN-over-solid promotion that reads the cell below, then checkNeighbourBlocks. That scan must match vanilla exactly, including its dx/dy/dz iteration order over -1..1 skipping the center column, and its return on first hazard found, which is what decides cells bordering more than one hazard. Only A* is offloaded; other mob movement types (e.g. random walk) stay local. Scoped to land mobs only: the offload reproduces Minecraft's ground pathfinding (WalkNodeEvaluator); swimming, flying, amphibious, and wall-climbing evaluators stay local. Overworld only, no other dimensions (disabled by the mod).

NODE MODES
Stateful (default): nodes hold delta-synced world shards (initial snapshot + deltas). World divided into square, full-height regions with a halo buffer around each, so mobs near a boundary are shared with neighboring nodes. Region size must be >= max pathfinding horizon (~64 blocks) to avoid multi-node pathfinds. Two sub-modes, both to be benchmarked: batch (all mob requests within a tick sent together, +/- jitter, allows flow-field sharing for common targets) and stream (requests sent immediately).
Stateless: nodes receive a full snapshot per request, trimmed to pathfinding-relevant surface blocks only (not the entire world). Distributed across nodes via power-of-2-choices. Sharding/halo concepts don't apply in this mode. Bandwidth inefficiency is intentional, for comparison purposes; no RLE or similar compression, to keep complexity down.

DATA STREAMS
Two separate streams: world changes and mob pathfinding requests.
World-delta stream: strictly ordered, sequence-numbered. Supports Kafka-style "resume from last N" on reconnect. Full resnapshot sent only if a node has fallen far behind.
Pathfinding-request stream: at-least-once, idempotent. Last-write-wins if two responses land in the same tick, otherwise first response wins.
Requests are rejected pre-send (not on response) if a node's queue depth implies completion more than 10 ticks after the current world state.

SCALING AND FAILOVER
Adding a node splits an existing node's region. Node death triggers reassignment of its region to another node via snapshot transfer or log replay; during handoff, mobs in the affected region are treated as dropped by the main server until reassignment completes.
Hot nodes (many mobs): since node workload is read-only, replicate the hot node and split load if fleet capacity allows; if fleet is maxed out, fall back to flow-field computation instead.
Liveness detection via phi accrual failure detector.
On node failure: mobs with a path computed within the last 10 ticks continue on that path. Mobs without a recent path get a cheap heuristic path computed on the main server. A small compute budget (<= 1000 microseconds/tick) is reserved on the main thread to run full A* for a limited number of mobs, round-robin, prioritizing those nearest to a player, until the node recovers.

SLO
p99 latency < 3 ticks. Framed as latency, but the real target is throughput under a latency budget. When a saturated fleet can't meet the SLO, drop mobs that are furthest from any player or not actively hunting.

TESTING
Fuzz testing compares Maestro vs vanilla Minecraft pathfinding by malus-weighted A* path cost across randomly generated regions. Test worlds are superflat with mild hills and obstacles but no overhangs or caves, so world state is a single vertical band per column. Some margin of error allowed since vanilla pathfinding isn't always optimal and Maestro uses optimizations like flow fields; this testing is for statistics/observability, not strict correctness.
Live trials run on single-AZ AWS compute nodes. No Kubernetes, to allow direct fault injection. ChaosMonkey and tc used to test failover under network/node faults. A reproducible load generator is needed for live experiments.
Add thorough unit tests.

OBSERVABILITY
OpenTelemetry used throughout. Metrics/traces needed: end-to-end request tracing, per-node consumer lag (Kafka-style), RED metrics per-request, USE metrics per-server, distribution of ticks-late per pathfind response (for SLO visualization), node topology visualization (resharding events, phi values over time), and per-node work queue depth.

CONVENTIONS
Add comments when needed, but not excessively. Keep comments very concise, and wrap identifiers and variable names in backticks. Do not place comments describing a file's functionality at the top of the file. Never use emojis or other unusual text symbols. Keep the comments clearly related to code and the system itself, i.e. do not overly reference prompt specific details/wording or one-off user requests or arbitrary project "milestones"/"phases". Only update the `README.md` when specifically prompted, and never bring overly session-specific details into the `README.md`. If in doubt, follow the existing codebase convention.

After each long task completion, produce a `WHAT_CHANGED_X.md` file in the `ai-changelog/` directory, where X is a counter that is incremented from the last file in the folder (only do this if the change is actually major like an updated schema or new functionality, not just a small bugfix or documentation update). This file should include a high level paragraph summary of what changed in the task completion. If there were any critical design decisions made without my explicit instruction, always mention them and explain why the decision was made, and the alternatives. All sections should be worded concisely and in straightforward terms, but make sure to reference the code when needed (filenames, function names, and variables are good to include). Do not amend previous changelog entries, create a new entry to overwrite.

Follow C++20 best practices and LLVM code style.

When implementing features, prefer a minimalistic implementation, but always ask clarifying questions.

DO NOT EVER DIRECTLY COMMIT CODE, I WILL ALWAYS REVIEW IT FIRST!

Access the Minecraft source at https://mcsrc.dev/1/26.1/net/minecraft/world/level/pathfinder/PathType
