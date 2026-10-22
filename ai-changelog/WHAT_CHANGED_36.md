# Failover on the server, fixed health checks, and a stricter simulator

This change fixes the problems found in the audit of 77e2037 and 29af375, and the three deep fuzz
seeds that failed in CI (3408, 16668, 8288).

- **Failover.** When a node cannot take a request, the server now serves the mob through a
  budgeted failover, as the spec says, instead of running vanilla for every mob of that node.
- **Request-path health check.** The check no longer takes a healthy node out of service because
  of an old unanswered request. Two of the CI seeds failed on exactly that.
- **Answers to declined searches.** An answer is no longer applied to a mob whose caller searched
  and then declined, as the Drowned's attack goal can.
- **Heartbeat times.** Phi now reads the time the transport heard each heartbeat, so a server
  stall no longer stretches the intervals it learns.
- **Simulator.** It now models slow ticks and server stalls. Its SLO check counts requests lost
  with their node. It checks that profiles are sent once per link, tests the reach rule
  independently, and checks that probes back off.
- **The third CI seed** failed because the quiet tail of a run was too short for a reconnect and a
  full resend. A run now lasts until that has had time to finish.

Results:
- The three CI seeds pass. They were replayed on the final controller code with the new sampler
  draws turned off, so each seed drew its original run.
- Every CI fuzz shard passes at CI's sizes:
  - shallow, 4 shards x 8000 seeds;
  - deep, 8 shards x 2500;
  - coverage, 2000 seeds. 69 of those seeds are held to the SLO, 26 of them at the latency edge.
- The sweeps found 4 more problems on the way, all fixed below:
  - the SLO had been applied to a lagging server;
  - late answers to requests given up on were ignored;
  - the fail-open check did not know about link loss;
  - the sampler never drew a server that kept up.
- Unit suites: 280 Java tests (controller, mod, mixinTest) and 195 node tests pass. The node also
  passes under ThreadSanitizer.
- The boot test and the e2e test pass:
  - shadow mode: 271 of 271 answers matched vanilla;
  - `on` mode: 254 of 254 answers moved a mob, with no drops;
  - answers came 1 or 2 ticks late in both.
- The failover is covered by unit tests only. The e2e test never takes a node down.

## Changes

- Controller:
  - `NodeHealth`:
    - The request-path check now forgets old evidence. The path is quiet only when 2 requests,
      sent within `QUIET_WINDOW_TICKS` (22) of each other, go unanswered and the older is
      `QUIET_TICKS` old. Before, one unanswered request from long ago and any later one opened
      the circuit at once.
    - Evidence is cleared when the link is lost, and when the node goes out of service, since
      its requests are canceled then.
    - The heartbeat detector takes arrival times from the transport. The "one arrival per
      hand-over" rule and `handedOver` are gone.
    - A probe given up on before it left (`withdrawn`) puts the circuit back to half open. Before,
      it counted as a failed probe and doubled the backoff.
    - An answer that comes within the tick budget to a request given up on still counts as an
      answer in time. `RequestTracker` stops counting such a request against its node when its
      answer is due, so before, an answer that came a little after that was ignored, and the
      request counted as unanswered. While a lagging server catches up, this happened often
      enough to take healthy nodes out.
  - `RequestTracker`:
    - A first copy still being encoded when its mob gives it up is not canceled. It leaves
      anyway, and a working node answers it.
    - New drop reason `UNAVAILABLE`: the region's holders cannot take the request now (not
      linked, out of service, or missing the start chunk). `NO_NODE` now means no node serves
      requests from there at all: outside every region, past the reach rule, or not fitting the
      wire.
  - `Transport.Listener.onFrame` takes `arrivedNanos`, when the transport heard the frame.
  - `NodeLinks` is public, so the harness can read `RECONNECT_TICKS`.
- Mod:
  - New `bridge/Failover`. Mobs whose node cannot take them:
    - keep a path made in the last 10 ticks;
    - otherwise get a cheap path, vanilla's search on 1/16 of its node budget, and wait for a full
      search.
    - Full searches run within 1000 µs a tick. One runs in the call itself while no other mob
      waits; the rest run at tick end, in turns.
    - A mob's turn is the tick it asked, plus one tick per 16 blocks to the nearest player.
    - A search that overruns the budget is paid back from the next ticks.
  - `PathOffload`:
    - A request counts as wanted only once the navigation was handed its placeholder.
    - A request refused as `UNAVAILABLE` goes to the failover.
    - A hold whose request is canceled at tick end is released.
    - Failover counts were added to the summary line.
  - `Searches.Vanilla` replaces the plain `Supplier`. It runs vanilla's search once in the call,
    in full or on a share of its node budget, and `later()` runs it again from where the mob
    stands (`Searches.searchAgain`).
  - New hook `Searches.Interceptor.handed`, fed by a `PathNavigation.moveTo(Path)` injection.
  - `HeldPaths`:
    - Its mixin interface is renamed `Marks`.
    - Every `Path` is now dated when it is made (`PathMixin`), and `HeldPaths.madeWithin` reads
      that date.
  - `NettyTransport` stamps each frame with the time the network thread heard it.
- Node:
  - Failed UDP sends are counted and logged at most once a second, with the running count.
    Before, each failure wrote a log line.
- DST harness:
  - New `FaultPlan.ServerStalled` (1 to 5 s of work in one tick) and `Scenario.Chance.SLOW_TICK`
    (a tick whose work takes 1 to 3 ticks). 30% of sampled plans stall once, and 30% of sampled
    scenarios have slow ticks (`SLOW_SERVER_SHARE`), so most runs keep up and the SLO still
    applies to them. A guard test checks both kinds are sampled.
  - `Simulation` starts a tick when it is due, or as soon as the last one ends. Like Minecraft,
    it gives up catching up after 2 s.
  - Stalls count as clean. A run whose load fits its fleet must take no node out of service, a
    stalled or lagging server included, and coverage requires such runs. The latency SLO applies
    only when the server also keeps up (`SimRun.heldToTheSlo`).
  - `SimRun` runs past its planned length until a reconnect (`RECONNECT_TICKS`) and a full
    resend of every loaded chunk can finish after the last fault (`settledBy`).
  - `RecordingOutput.sloMisses` counts `NODE_LOST`, and servable `UNAVAILABLE` drops.
  - New invariant: each node is sent each profile at most once per link (`transport.connected`
    metric).
  - `ModeledNode`:
    - Re-derives the reach rule from the spec for each request and records a violation if a
      request breaks it.
    - Marks any answer searched without the right area as over air.
  - `FailOpenCheck`:
    - Pairs blind requests within the quiet window.
    - Judges each link of an episode on its own: the controller forgets requests sent on a link
      it heard go down, unless it had already noticed (`linkLost`).
    - Bounds probes by the doubling backoff, not one per 20 ticks.
    - New `FailOpenCheckTest` feeds it good and bad traces.
  - `NondeterminismGuardTest` also bans the platform-dependent `Math` functions (`exp`, `log`,
    `pow`, ...), `RandomGenerator.getDefault`/`of`, the no-argument
    `RandomGeneratorFactory.create`, and `new SplittableRandom()`. `SimTransport` uses
    `StrictMath.pow`.
  - `ScriptedWorkload.replayState` replays chunks, mobs and players. The default
    `Workload.replayState` now throws instead of doing nothing.
  - Dead code removed: `RecordingOutput.unservedServable()` and `ModeledNode.staleRequests`.
- CLAUDE.md:
  - refusal goes through the failover;
  - the placeholder must be handed to the navigation;
  - what the cheap path and the turn order are;
  - server stalls in the DST;
  - node-lost requests count as SLO misses.

## Design decisions made without instruction

**Quiet evidence is aged out by a window, not by the newest request's age.** The first fix judged
the newer request's age. That delayed detection on nodes with little traffic, and a replica test
caught it. A window keeps the old "oldest request is 11 ticks old" timing and forgets old
evidence.
- Alternative: judge only requests still live when due. That would also need the fail-open check
  to know which requests the controller counted, which it cannot see on the wire.

**A first copy still encoding is no longer canceled.** The cancel raced the request, and a node
that honored it left a request unanswered that the controller counted as evidence. This caused
two of the CI failures. Letting it go costs at most one wasted search.
- Alternative: exclude such requests from the evidence. The fail-open check then needs to know
  about cancels, and timing alone cannot tell this cancel from one sent because the node was
  taken out of service.

**The cheap path is vanilla's search on 1/16 of its node budget.** The spec says "cheap heuristic
path" without saying what it is. A short vanilla search follows vanilla's rules, so it cannot
walk a mob off a cliff, and its cost is bounded.
- Alternative: a straight-line path, which is cheaper but ignores terrain.

**Full searches wait in turns at tick end, not in the call.** Calls come in entity order, so a
budget spent in the call would always favor the same mobs. A queue served at tick end can put
the nearest mobs first and still give every mob a turn. A call still runs its search at once when
no one waits.
- Alternative: grant the budget first come, first served in the call, which starves mobs late in
  the entity list.

**Only `UNAVAILABLE` refusals go to the failover.** Queue-depth refusals still run vanilla in full.
They are the saturation case, which the SLO's shedding policy (Phase 5) is meant to cover.

**The latency SLO does not apply while the server lags.** The first sweep failed 3 deep seeds
at p99 = 3 ticks, all with slow ticks. A server that falls behind runs its owed ticks back to
back, so an answer that takes one tick's real time spans three ticks by the count. The SLO is
counted in ticks, so it cannot hold then. Such runs still check that no node is taken out of
service. New scripted tests cover a 3 s stall and a server that keeps falling behind.
- Alternative: judge latency in real time for lagging runs. That is not what the spec measures.

**Arrival times come from the transport.** `Transport.Listener.onFrame` gained a parameter. The
detector needs true arrival times. The transport is an injected seam, so DST still controls them.

**Things left as they are:**
- The node still drops the whole link on a profile it refuses. The Java and C++ checks match, and
  a softer reaction needs a wire message to stop requests naming that profile.
- The node still accepts requests on a half-joined link. Requiring both connections broke 11 node
  tests and the shared conformance contract. The controller never sends a request before `Hello`,
  so it cannot matter.
- These C++ items look dead but are test seams, kept as `Area::column` was:
  - `Outcome::Searched`, `RequestQueue::tryTake`, `NodeCore::store`, `Column::with(int, Cell)`.
  - `HeaderStatus::Incomplete` is the safe default of a header peek.
  - The "double clear" on a refused frame is not redundant: `NodeCore` also clears for refusals
    that never reach `WorldStore::apply`.
- The circuit still outlives the link. With evidence now cleared on link loss, a restarted node
  is no longer taken out again by its first request.
