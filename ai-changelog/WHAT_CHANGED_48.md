WHAT CHANGED 48: GAME TESTS, LIVE-WORLD AND NETWORK-FAULT E2E RUNS, PARITY FLOORS, INVARIANT GUARDS

Summary
This change closes the testing gaps found in an audit, for the high and medium priority items. A new game test suite (`make test-game`, `runGameTest`) runs inside a headless server with a real overworld. It covers:
- What `Fleet` sends the controller: block changes only when the cell changes, made directly, by a door, by flowing water, by falling sand and by a piston; chunk loads and unloads; mobs but not other entities; players each second until they spectate; and events falling in the tick they name.
- That `Fleet.watches` is true only on the server thread and inside the covered square.
- The navigation hooks: a held mob is neither found stuck nor walked to the centre of its block, and `MeleeAttackGoal.canUse` marks its search.
- `PathOffload.wants`, the failover's full search (`Searches.searchAgain`) and `MobNavigator.follow`.
- That no dimension but the overworld exists, no nether portal lights, and no eye of ender is set in an end portal frame.

Mutating the block-change, stuck-detection and attack-goal hooks makes the matching game tests fail.

The e2e script gains three runs:
- `blocks`: changes blocks among the mobs and unloads and reloads a second crowd's chunks. Once the world settles, every answer must match vanilla.
- `net`: puts a new userspace fault proxy (`tools/fault-proxy.py`) between the mod and the node. It loses, duplicates and reorders datagrams, and cuts the TCP link mid-run. The node must resume its region, not take it afresh.
- `topology`: two regions on two nodes, with a crowd on their shared corner and one outside the square, then a server restart with the nodes still running.

Stream-mode shadow, split and replica runs join the default list. CI runs the e2e runs as a matrix job and the game tests in the main job.

The parity test now has floors: at least 95% exact cases, at least 50 exact cases per captured mob kind, and an exact case for every node type on a vanilla path, except a closed wooden door, which is approximated by design.

The DST gained negative tests showing that each previously unproven invariant and self-check can fire (`InvariantsTest`, `SelfCheckTest`). It also checks that `path.answered` and `path.dropped` match the outcomes. New assertions cover `node.phi`, `node.queue.depth`, `node.searches`, `path.expired`, `path.offload`, `path.failover`, `failover.waiting` and `server.tick.millis`.

Decisions made without explicit instruction
- `Fleet` sends every event through one private `accept`, and a package-private `watch(WorldInput)` lets a game test see them. `PathOffload` is now bound to that same `accept`, so its requests and cancels pass the watcher too. The alternative, refactoring `Fleet` behind interfaces, would leave the real chunk, entity and thread checks untested.
- `PathOffload.MobNavigator` is now package-private, so a game test can drive the real navigation hand-off.
- The tick timing moved from `MaestroServer` into `obs.TickTimer`, so `server.tick.millis` can be unit tested. Behaviour is unchanged.
- The game test server places tests at random up to 15 million blocks out. A test-only mixin (`GameTestServerMixin`) places them from the origin, inside the square the fleet covers. Its config names one node on ports where nothing listens, in shadow mode, so every search stays vanilla's while the fleet still watches the overworld.
- One interceptor that sends watched mobs' searches to their tests and the rest to the fleet's `PathOffload` is installed once and never removed, since game tests run together and nested installs would undo each other.
- The held-mob test gives the husk a walking speed, because vanilla's stuck check scales with the speed a mob last moved at. A mob that never moved can never be found stuck, so without that speed the test passed with the hook removed.
- The node now logs `resumed region N at sequence S` and `took region N afresh at sequence S` beside its existing `kept region` line, so the e2e can tell a resumed stream from a resent one. This is log output only.
- `/maestro load mark` (load mod only) logs the fleet's counts, so the blocks run can compare the counts after the world settles with the counts at the end.
- The blocks run toggles its door with a redstone block. A non-strict `/setblock` makes a door half copy its partner's state, so toggling a half by command would change nothing. The power opens the lower half, and the upper half follows through a shape update, which also exercises an indirect change.
- The `topology` run's crowd stands on the corner x = 128, z = 128. That corner lies on the cut whichever axis the controller halves the square along.
- The fault proxy is Python with no dependencies, rather than `tc netem`, so it needs no root and runs the same locally and in CI.
- The e2e runs moved from the main CI job into a matrix job of one run each. Serially, 13 runs would come near the job timeout.
- The DST invariant that request metrics match outcomes was added so every fuzzed run checks `path.answered` and `path.dropped`, not only the scripted tests.
- Two `SelfCheckTest` cases corrupt `RequestTracker` and `WorldStreams` state by reflection, since only a controller bug can reach those findings. The alternative was a production seam. A field rename makes these tests fail loudly rather than silently pass.
