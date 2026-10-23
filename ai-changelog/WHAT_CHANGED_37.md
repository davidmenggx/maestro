# Holds that stick, a stricter quiet-path rule, probes that finish, and a failover e2e test

This change fixes four problems from the audit of PR #12 and adds an end-to-end test that takes
a real node down.

- **B1, a hold swallowed by an ended one.** When a hold ended (a drop, a decline, or its time ran
  out), the navigation kept the finished one-node path. A new hold from the same block has the
  same node, and vanilla's `moveTo(Path)` keeps a path whose nodes match (`sameAs`), so the new
  hold was never followed and its request was canceled at tick end. A mob whose attack goal never
  gives up (wolf, iron golem, ravager) stood still for as long as it kept its target. Now a held
  path handed to the navigation replaces a path with its nodes that is no longer held.
- **B2, a quiet path judged too early.** The circuit opened when two requests had left since the
  last timely answer and the older was 11 ticks old. The newer could have left one tick before,
  with no time to be answered. Now a request counts only once its first copy falls due
  unanswered, and only if no cancel was sent for it, since a node may honor a cancel.
- **B3, a mob left waiting in the failover.** A mob that got a cheap path and then had its next
  request taken by the node still had its full search run at tick end, which replaced the
  placeholder and wasted the node's answer. The failover now forgets a mob as soon as its node or
  vanilla serves it.
- **B4, a probe its mob gave up.** A probe whose mob gave it up was canceled and not resent. A
  node that honored the cancel never answered, so the probe failed and the backoff doubled. The
  probe is now never canceled and is resent like a live request until it is answered or its time
  runs out.
- **Failover e2e test.** A third e2e run (`OFFLOAD=on FAULTS=failover`) pauses the node with
  `SIGSTOP`, resumes it, then kills it with `SIGKILL` and restarts it. It checks that the failover
  served mobs while the node could not, and that the node's answers moved mobs again after each
  fault. `make test-e2e` runs it after the shadow and `on` runs.

Results:

- The new tests were written first. On the old code, 6 of the 7 failed for the reason they
  target. The seventh (a request canceled before it fell due) passes on the old code too: it
  guards the new rule, which would fail it without the cancel exclusion.
- Unit suites: 294 Java tests pass (48 plain mod, 43 `mixinTest`, 203 controller).
- Fuzz, coverage tier (2000 seeds): passes, with the same sampler reach as before (69 seeds held
  to the SLO, 26 of them at the latency edge).
- Fuzz, deep and shallow tiers at CI sizes: every shard passes (deep 8 x 2500 seeds, shallow
  4 x 8000).
- E2E:
  - shadow: 250 of 250 answers matched vanilla;
  - `on`: 246 of 246 answers moved a mob, no drops;
  - failover: while the node was paused the failover served 85 calls; while it was down, 21.
    Answers moved 73 mobs between the two faults and 43 after the restart. The 10 drops were
    requests in flight when the node failed.
- The boot test passes.

## Changes

- Controller:
  - `NodeHealth`:
    - `sent` is replaced by `missed(node, firstSent)`: a request whose first copy fell due
      unanswered. Misses are kept by first-send tick in a sorted map, so a late miss still times
      the quiet from when its request left. The quiet rule is unchanged otherwise: 2 misses within
      `QUIET_WINDOW_TICKS`, the oldest `QUIET_TICKS` old.
    - New `probing(node, requestId)` and `inService(node)`.
  - `RequestTracker`:
    - Each request records when its first copy left and whether a cancel was sent for it.
    - `settleDue` reports a miss when a first copy falls due unanswered and uncanceled.
    - The probe is never canceled. An abandoned probe is resent at its due ticks and requeued
      after a given-up encode, as a live request is.
  - `Controller.linked(node)` and `Controller.inService(node)`, for the mod's log.
- Mod:
  - `HeldPaths.swallows(kept, handed)`: whether the navigation would keep `kept` in place of the
    held path `handed`.
  - `PathNavigationMixin`: before `moveTo(Path)` runs, a path that would swallow the held path
    handed is dropped, so the hold is followed.
  - `Failover.mobRemoved` is now `forget`. `PathOffload` calls it whenever the node takes the
    request, an answer is reused, or vanilla searches in full.
  - `PathOffload`: the shadow path moved into its own method. Behavior is unchanged.
  - `Fleet` logs each node whose state changes (`not linked`, `linked and in service`,
    `linked but out of service`), with the offload summary so far.
- DST harness:
  - `FailOpenCheck` takes a request's resend as the sign its first copy fell due unanswered. The
    controller is held to notice once two such requests show it. Requests never resent are not
    counted, so the bound errs late.
- Tests:
  - `PathOffloadTest`: a mob asking again where its hold ended is held again; a finished one-node
    path does not keep a mob from being held; the failover forgets a mob its node or vanilla
    served.
  - `HeldPathsTest`: which paths `swallows` applies to.
  - `NodeHealthTest`: quiet tests use `missed`; misses that fall due late or out of order.
  - `WorldBoundaryTest`: a request answered by its due tick keeps its node in service after an
    earlier one was lost; a request canceled before it fell due shows nothing; a probe given up
    in a blackhole is resent and closes the circuit.
  - `FailOpenCheckTest`: rewritten for resends; requests never resent show nothing; the newer
    request's due tick times the notice.
- `tools/e2e-test.sh`, `Makefile`: the failover run. Its mobs cannot be hurt, so chases last, and
  an iron golem chases the husks.
- CLAUDE.md: the e2e test covers a node pause and a crash and restart.

## Design decisions made without instruction

**B1 is fixed where the navigation takes the path, not where the placeholder is made.** A held
path handed to `moveTo(Path)` replaces a path with its nodes that is not held. This covers every
way such a path can be left there (a released hold, an expired hold, a finished vanilla path at
the block), and leaves vanilla's behavior alone for every other path.
- Alternative: revive the ended path as the hold. It mutates a path object that a goal may also
  hold, and keeps the old target.
- Alternative: stop the navigation when making the placeholder. It changes the navigation even
  for a caller that then declines the placeholder.
- The mixin cannot be unit tested without a live level. Its logic is in `HeldPaths.swallows`,
  which is tested, and the test navigation calls the same method.

**A quiet path counts misses at the due tick, not sends.** The due tick is when the controller
already resends a request, so a request counts only once it had the time the controller expects
an answer to take. Detection is at most a tick or two later than before, for nodes with little
traffic.
- Alternative: require the newer request to be `QUIET_TICKS` old too. That delays detection by up
  to 11 ticks on nodes with little traffic, which a replica test caught before.

**A request canceled before it falls due is not evidence.** With misses counted at due, a cancel
the node honored would leave a miss that no later answer clears, if it fell due after the other
answers came in. `aRequestCanceledBeforeItFellDueShowsNothingAboutThePath` builds that case. A
node that cannot answer still shows misses, since most requests fall due before their mob gives
them up.
- Alternative: have the node acknowledge each cancel it honors. That is a wire change, and an
  acknowledgment must not count as an answer, or a node whose workers are stuck but whose network
  thread runs would never leave service.

**The oracle reads misses from resends.** A live request is resent exactly when its first copy
falls due unanswered, and the resend is on the wire, so `FailOpenCheck` needs no copy of the
controller's due-tick estimate. Abandoned requests are not resent, so the oracle does not count
them, which only makes its bound later.

**The probe is sent until it is answered or its time runs out.** Its answer is what brings the
node back, so canceling it only risks a false failure. It costs at most one search the mob no
longer wants.
- Alternative: on a canceled probe, make the next request the probe. A node that truly cannot
  answer would then be sent a probe per cancel, not one per backoff.

**The failover e2e test reads node state from the server log.** The mod now logs each node's
state change with the offload summary so far, so the test can compare counts between phases.
This is also what an operator would want to see in a log.
- Alternative: a status console command in the released mod. It is more surface for a test-only
  need.

## Not done

- Phase 5 work (halo, reassignment, splitting, shedding, replication policy, stream sub-mode).
