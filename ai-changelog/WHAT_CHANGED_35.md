# A node that cannot answer no longer freezes mobs

This change fixes the problems found in the audit of 77e2037. The main one: a node that kept its
TCP link but stopped answering requests held its mobs in place, one timed-out request after
another, because nothing took it out of service. The controller now has a `NodeHealth` that takes
a node out of service when either of two signals fires. A phi accrual detector watches heartbeat
arrivals. A request-path check watches for requests that leave and get no answer within the tick
budget. A node out of service gets no requests: its live requests are dropped and its mobs are
searched by vanilla. Heartbeats bring a suspected node back. A quiet request path is probed with one
request after a backoff that doubles with each failed probe. The DST gained a `Blackholed` fault,
long faults, and a fail-open invariant. The mod got fixes for how holds and answers meet the
navigation, a shadow compare that cannot be fooled by the navigation trimming vanilla's path,
per-node request sockets, zero-copy sends and readable histogram buckets. The e2e test now
requires every shadow answer to match.

Results:
- All three fuzz tiers passed at their default sizes: coverage 2000 seeds, deep 1500, shallow
  5000.
- With admission made to ignore `NodeHealth`, the new invariant failed at seed 34. A node that could
  not answer for 91 ticks was sent 41 new requests where 4 probes were allowed.
- In the e2e test, shadow mode matched 245 of 245 answers. In `on` mode 295 of 295 answers moved a
  mob, with no drops and no cancels. Answers came 1 or 2 ticks late.

## Changes

- Controller:
  - New `core/NodeHealth`:
    - Phi accrual over heartbeat arrival times, on `nowNanos`. Arrivals in one hand-over count as
      one. The threshold is 8. It starts from an assumed 1 s interval, with a 200 ms spread floor
      and 1 s of acceptable pause. Math uses `StrictMath`, so phi is the same on every platform.
    - Request-path check. The path is quiet when 2 requests were first sent since the last answer
      in time, and the oldest is 11 ticks old. A quiet path opens the circuit.
    - Circuit: open, then half open after a backoff of 20 ticks, doubling up to 160 with each
      failed probe. The probe is the next admitted request. Any answer in time closes it.
    - Only answers to outstanding requests count. A stale answer does not, so a node that only
      answers late is taken out too.
    - Heartbeat history resets with each link. The circuit outlives links, since datagrams do not
      travel on them.
  - `RequestTracker`:
    - A holder out of service is skipped, so a replica serves its region's mobs.
    - Live requests of a node that goes out of service are dropped as `NODE_LOST` and still counted
      against the node until due.
    - New metrics: `path.dropped{reason}` for every drop, `path.answered`, and
      `path.rejected{reason=unavailable|not_ready|outside}`.
  - New metrics: `node.phi`, `node.in_service`, `node.queue.depth` (gauges), `node.suspected`,
    `node.quiet` and `node.circuit{state}`.
  - `Metrics.gauge` takes a `double`, so phi fits.
  - `Transport.send` no longer promises a copy: the caller must not change a sent frame.
  - `Encoder` is public, so the harness can read `MAX_ATTEMPTS`.
- Simulation:
  - New fault `Blackholed`: datagrams to and from a node are lost while its link and heartbeats
    carry on.
  - 30% of partitions, pauses and blackholes now last up to 160 ticks, so both detectors fire and
    probes fail and succeed.
  - New `FailOpenCheck` invariant. While a node truly cannot answer, it may be sent only the few
    requests that show the path is quiet, the ones admitted before that, and one probe per backoff.
    It is judged from requests on the wire against the fault plan.
  - New invariant: in a run that fits capacity, no node is suspected or has its path go quiet.
  - Coverage now requires suspicions, quiet paths, answered probes, refusals for a node out of
    service, blackholed runs and judged fail-open episodes.
  - Scripted tests replace the two that asserted the old freeze: a partition, a long pause, a short
    pause, a blackhole, and a replicated region served by its second holder.
- Mod:
  - `PathOffload`:
    - A mob that no longer waits for its answer cancels it when the tick's work ends
      (`tickEnding`), not when the navigation stops.
    - A one-node answer replaces the hold it matches. Before, the navigation kept the hold.
    - Shadow mode compares with a copy of vanilla's path taken when its search returned.
    - The first 20 shadow mismatches are logged with both paths.
    - Searches of mobs outside every region are not sent.
    - The navigation is reached through a small `Navigator` interface, for tests.
  - `Searches.Interceptor.displaced` and the `stop`/`moveTo(Path)` hooks in `PathNavigationMixin`
    are gone.
  - `LevelChunkMixin` compares cells even when vanilla returns null.
  - `Fleet`:
    - Block changes are watched only in chunks inside a region.
    - A removed mob is forgotten before its drop is reported.
  - `NettyTransport`:
    - Each node has its own UDP socket, so an answer belongs to the node whose socket it reaches,
      whatever address it comes from.
    - Frames are wrapped, not copied.
  - `OtelMetrics`:
    - `path.latency.ticks` has one bucket per tick from 0 to 10. `frame.bytes` and
      `node.queue.reported` have doubling buckets.
    - Gauges are doubles.
- Tests:
  - New `NodeHealthTest` (12).
  - `PathOffloadTest` moved to `mixinTest` and grew from 2 to 20 tests.
  - New `OtelMetricsTest` (4).
  - `NettyTransportTest`: per-node sockets, zero-copy sends and a duplicate request address.
  - `HarnessConstantsTest`: long faults outlast both detectors, and stray answers arrive within
    their window.
- E2E:
  - Shadow mode must match 100%, and mismatches are printed on failure.
  - No admitted request may be dropped.
  - In `on` mode at least half the answers must move a mob.
  - Blocks are placed before the mobs exist, and leftover mobs are killed first.
- CLAUDE.md: tick-end cancel, the request-path liveness check and fail-open, and the new DST
  invariants.

## Design decisions made without instruction

**Two liveness signals, not only phi accrual.** Heartbeats travel on TCP, so they cannot show a UDP
path that fails alone: a blocked port, a NAT that eats answers, or a node that answers only late.
Phi alone would leave those freezing mobs. A circuit on the request path covers them.
Alternatives:
- phi only, which misses these faults;
- a circuit only, which misses a silent node with no traffic and is not the spec's detector.

**Out of service means fail-open, link kept.** The node's live requests are dropped, new ones
refused, and its mobs searched by vanilla. The link and world stream stay up, so recovery is cheap.
Alternative: close the link on suspicion, which forces a reconnect and resend. That is wasteful for
a short stall.

**A quiet path needs 2 requests and 11 ticks.** One lost request and its resends on a lossy network
must not trip it. With the zombie chase case (a request superseded every few ticks) a single
request never times out, so the check counts requests sent, not timeouts.

**Phi is built now, not in Phase 5.** The audit found mobs freezing in Phase 4 itself. The rest of
Phase 5 (reassigning a region, the fallback budget, shedding, the halo) is not built.

**Phi uses `nowNanos`, not ticks.** The node's heartbeat timer runs in real time, so real time
matches it, and server tick-rate changes do not move it. A server stall cannot cause a false
suspicion: arrivals queued during it are handed over before phi is evaluated. Alternative: ticks,
which a TPS change would distort.

**Displacement is judged at tick end.** The goal selector runs the new goal's `canUse` before it
stops the old goal, and the old goal's `stop` stops the navigation. Judging on `stop` canceled the
request the new goal had just made. Alternative: give `canUse` a fresh hold. That is smaller, but
mobs would stop instead of keeping their live path.

**Answers are matched to nodes by socket.** One UDP socket per node, bound for the transport's
life, so late answers still arrive after a reconnect. Alternative: match answers by request id in
the controller. That changes the transport contract and loses the misrouted-answer check.

**The e2e test demands 100% shadow matches.** The spec says answers must match. Blocks are now
placed before mobs exist, so no search races a delta.

## Not done

- The halo, failover to another node, the fallback budget and shedding (Phase 5).
- Traces (Phase 7).
- Brain mobs and the spider's own `moveTo(Entity)` stay vanilla.
