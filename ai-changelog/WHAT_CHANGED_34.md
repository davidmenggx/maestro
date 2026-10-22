# The mod offloads searches in a real game

The controller now runs in the mod, and a real server sends real mob searches to a real node and
moves its mobs with the answers. The controller moved from the test source set into
`controller.core`. The mod has a Netty transport, an OpenTelemetry `Metrics`, world hooks for
block changes, chunks and mobs, and a `Fleet` that wires them together from the config. A search
is offloaded only when its caller follows the path it gets: a navigation `moveTo`, or
`MeleeAttackGoal.canUse`. While a search waits, the mob keeps its live path, or holds in place on
a one-node path that the answer replaces. A request the controller refuses at admission is
searched by vanilla in the same call. A search that could read past its region is now refused.
The controller hears the network and finished encodes at two points per tick, when the tick
starts and when its work ends, and the simulator models this. A new `make test-e2e` runs the
released mod against `maestro-node`. In shadow mode every answer matched vanilla's path
(266 of 266). In `on` mode every answer moved a mob (194 of 194). Answers came 1 or 2 ticks after
their request.

## Changes

- Controller (`server/controller`):
  - `Controller`, `NodeLinks`, `WorldStreams`, `RequestTracker`, `Encoder` and `Violations` moved
    from `src/test/.../sim` to `src/main/.../core`. `Controller.Parts` is gone, since the
    nondeterminism guard already scans the whole main tree. `run` is now an accessor.
  - Admission refuses a search that could read past its region (`SearchReach.fits`), as `NO_NODE`
    with metric `path.rejected{reason=reach}`. The profile is looked up before this check.
  - `SEND_TICKS` is 1: a batch encoded at the end of a tick leaves when the next tick starts.
  - New `region/RegionLayout`: a fixed 256-block lattice with one cell centered on the origin,
    and a spiral order of cells for a static fleet.
- Simulation:
  - Transport callbacks and finished offload work wait in an inbox and reach the controller only
    at the two hand-overs per tick (`Simulation.handOver`, `toController`).
  - New sampled setting `SERVER_BUSY_PERCENT` (20 to 100, default 50). The server ends its tick
    after that share of the tick, then idles.
  - `Topology` cuts its grid from `RegionLayout`, so the origin is at the center of a region.
  - The servable rule now needs the search to fit its region.
  - The check that a fresh assignment is not behind the stream now runs when the assignment is
    sent (`Simulation.worldFrameSent`), not when it is applied.
  - New tests: hand-over timing, region layout, and a search refused for its reach.
- Mod (`server/src`):
  - `Searches.Interceptor` replaces `Searches.Observer`. It decides each answer, and may run
    vanilla's search. `Searches.enter`/`leave` mark callers whose searches may be offloaded.
  - `PathFinderMixin` asks the interceptor first. Vanilla's search runs only if the interceptor
    runs it.
  - New `PathOffload`:
    - Sends requests and hands out placeholders.
    - Applies answers, and ends holds on drops.
    - Cancels the request of a mob that stops following its placeholder.
    - Compares answers with vanilla's in shadow mode.
    - Logs a summary with a ticks-late histogram when the server stops.
  - New `HeldPaths` with `PathMixin`: a held path is never done and never advances. It ends on
    release, after 12 ticks, or when its node is trimmed away.
  - New `PathNavigationMixin`:
    - Wraps the three `moveTo` overloads that search.
    - Skips stuck detection and the move target while a path is held.
    - Reports a displaced path.
    - Exposes `speedModifier` and `targetPos`.
  - New `MeleeAttackGoalMixin` wraps `canUse`.
  - New `LevelChunkMixin`: reports a block change whose cell changed.
  - New `NettyTransport`:
    - Two TCP connections per link, with 4-byte little-endian length frames.
    - One UDP socket for all requests. An answer is matched to its node by its sender address.
    - A link whose unsent bytes pass 64 MiB is closed.
  - New `Fleet`: builds the controller and its environment when the overworld loads, feeds it the
    game's events, hands over at both tick points, and self-checks every 100 ticks.
  - New `OtelMetrics`.
  - `MaestroConfig` gains `offload` (`shadow`/`on`), `nodes` and `nodeCapacity`. Startup refuses
    `stateless` and `stream`.
  - `ParityCapture` implements the interceptor.
- Build and CI:
  - New `mixinTest` source set run under `fabric-loader-junit` (`make server-test` runs it).
  - New `tools/e2e-test.sh` and `make test-e2e` (both modes by default). CI runs it after the
    boot test and uploads the logs on failure.
  - Dev servers set `pause-when-empty-seconds=0`.
- CLAUDE.md:
  - which callers are offloaded;
  - what a waiting call returns, and admission passthrough;
  - shadow mode;
  - the region lattice;
  - the tick hand-overs;
  - the send-time assignment invariant;
  - the end-to-end and mixin tests.

## Design decisions made without instruction

**The simulator draws how busy the server is.** A start-of-tick hand-over only helps when the
server idles between ticks. Without idle time in the model, almost no encode would finish before
the next tick starts, and the new path would go untested. The default is 50%, which also kept the
scripted tests' timings. Alternative: model a server that is busy for the whole tick.

**The assignment check moved to send time.** With a fully busy server, the sweep reported a
holder assigned one sequence behind a frame that another holder had already applied. Both were
sent correctly. The frame simply crossed a faster link. The real property is that the controller
never assigns behind a frame it has already sent, and that is now what is checked.

**The simulated grid uses the production lattice.** The old grid put the origin at a region
corner, so the reach check would have refused almost every spawn-area request. Some fixed
positions and test victims moved for this. The scripted warm-up moved to tick 2, because a node's
hello now waits for a hand-over.

**Refusal is detected without an API change.** The controller reports a refusal through
`pathDropped` during `accept`, and `PathOffload` notes drops for the mob it is admitting.
Alternative: return an admission result from the controller.

**Cached answers are narrower than the spec text was.** An answer is kept only if it arrived
after its mob moved on, and it is used once. An applied answer could otherwise come back to a
`recomputePath` after a block change.

**Hold details.**
- A hold ends on its own after 12 ticks, so a lost request cannot freeze a mob.
- A held path at the same block is renewed, not replaced. The navigation keeps a path with the
  same nodes anyway.
- An answer starts from the nearest of its first 4 nodes.
- An answer with no nodes stops the navigation, as vanilla's null would.

**Transport.**
- Requests use one UDP socket, so late answers still arrive after a reconnect.
- Frames that arrived on a link which has since ended are dropped at the hand-over.

**Config.**
- `nodeCapacity` defaults to 100.
- A missing node port means the node's default port.
- Unknown keys inside a node entry are refused.

**Tests.**
- The tests that need mixins got their own source set. Running every mod test under the loader
  broke 6 existing tests, because the loader throws on unbound block tags.
- The end-to-end test uses husks (they do not burn in daylight) and villagers.
- It needs at least 20 answers, and in shadow mode at least 80% matching vanilla.

## Not done

- No halo, failover, phi accrual, splitting, replication policy, shedding or stream sub-mode
  (Phase 5). Mobs within reach of a region edge stay vanilla.
- Brain mobs (`MoveToTargetSink`) and the spider's own `moveTo(Entity)` are not offloaded.
- No player events, traces, or transport metrics yet.
- The main-thread time saved has not been measured.
