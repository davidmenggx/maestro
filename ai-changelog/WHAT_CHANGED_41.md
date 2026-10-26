# Node-reported search rates, capacity-aware admission, and the third audit's fixes

This change fixes what the third audit of the Phase 5 change found. A node's capacity was measured
from one heartbeat sample per second, and once lowered it decided what "busy" meant, so it sank to
the offered load and a replica could hold its spare forever. Now each heartbeat reports the
searches the node's workers finished and the worker time they took, and the controller takes the
median of recent samples as the node's rate. Admission uses that rate too, so a configured
`nodeCapacity` that is too high no longer breaks the SLO once the node has been measured. A lost
check probe no longer takes a node out of service while it answers requests in time. The DST now
holds admitted requests to the SLO under overload, the case shedding exists for, and the e2e split
run checks that the node really kept its half. CLAUDE.md drops two lines that described nothing the
code does.

Results: see "Verification" below.

## Changes

- **Wire.** `Heartbeat` gains `searches` (searches finished since the timer last fired),
  `search_micros` (worker time they took, from taking each job to settling it) and `workers` (the
  node's search threads). New fields come last. The conformance `Effect` gains `searches`, so the
  model and the node must count the same searches. Parity fixtures do not change.
- **Node.**
  - `NodeOptions.Workers` replaces `ServerOptions.Workers`, so the core can report it.
  - `NodeCore::run` times each job that searched the held area; probes, requests for another region
    and jobs that waited too long do not count. `heartbeat()` takes and resets the counts even when
    it has no link to send on, so a beat always covers one interval.
  - The node logs `kept region N as region M at sequence S` when it applies a kept assignment.
- **Controller.**
  - New `core/Capacity`: heartbeats are pooled until they hold 64 searches, which makes one sample
    (workers x tick / mean search time). The rate is the median of the last 5 samples, the higher
    of two middles, capped at the assumed capacity. A lost link starts it over.
  - `Placement` loses its heartbeat-sampled throughput (`Throughput`, `busy`, the four-cold-period
    lapse, `answered`, `depth`, `capacity`). The cold rule reads `Capacity`.
  - `RequestTracker` models each node's queue at that node's capacity: admission shares, the drain
    after each batch, and the conversion of a heartbeat's depth into ticks.
  - `Priority.allowedAhead` takes a fractional capacity and never returns less than 0.
  - `NodeHealth.answered` also clears a check probe that is out, so a lost probe cannot open the
    circuit of a node that answers in time.
  - `Reply.Depth` became `Reply.Beat` with the new fields. Metrics `node.searches` and
    `node.capacity`.
  - A batch enters the queue model when it is handed to the encoder, as leaving a tick later
    (`RequestTracker.leaving`, state `ENCODING`), instead of when its encode is handed back. Found
    by the coverage sweep: on a server that works all tick, the tick-end batch is still encoding at
    the next tick's start, so its requests counted as waiting ahead of that tick's requests, and a
    load that fits was shed (seed 561; half of all requests in the new regression test).
  - `NodeHealth` keeps the requests that fell due unanswered when a node leaves service by
    suspicion. Found by deep seed 19059: a node paused, its requests fell due, and a 58-tick server
    stall made phi suspect it a tick before the quiet rule would have fired. Leaving service wiped
    that evidence, so when heartbeats came back while its datagrams were still lost, it was sent
    new requests. Live requests are canceled at the suspicion and never counted, as before.
- **DST.**
  - `ModeledNode` reports searches and their time like the node, a pause included.
  - `SimRun.admissionFits`: the network is clean, the server's tick-end encodes finish before the
    next tick starts, and at worst cost a queue of the assumed capacity is served before the next
    batch can arrive and answered by the hand-over that ends the tick after it left (a tick plus the
    server's busy steps, less the round trip). A run held to the SLO only by admission counts
    answers and timeouts; requests dropped because a hot region moved are by design (deep seeds
    3362 and 14926). The check that counts sheds as late now needs the load to fit, as CLAUDE.md
    already said. `heldToTheSlo` and the "no node out
    of service" check now apply when the load fits or admission fits (`judgedHealthy`).
    `heldWithoutShedding` judges each node at the least capacity the controller can take it to
    have: the assumed one or its rate at worst cost.
  - `SimRun.withControllerCapacity` runs a controller that assumes more than the harness expects.
  - Coverage tier requires a run held to the SLO while shedding past capacity, and a node measured
    below the assumed capacity.
  - `Scenario`: the capacity estimate is drawn from 50% (was 75%), and 30% of sampled scenarios
    (`MODEST_ESTIMATE_SHARE`) assume no more than a normal node serves at worst cost.
  - New tests: `CapacityTest`; placement cold rule at a measured rate and at a capped fast one
    (`PlacementRulesTest`); a check whose probe was lost (`NodeHealthTest`); an overloaded node held
    to the SLO, an over-estimated node taken at its measured rate, and a busy server whose batches
    encode late (`SimulationTest`); a guard
    that a controller admitting past its node is caught (`HarnessGuardsTest`); two conformance
    cases for heartbeat counts; node tests for the heartbeat fields over the core and the server.
- **E2E.** The split run fails unless node-0 logs that it kept region 1 as region 2.
- **Docs.** CLAUDE.md: the capacity rule, the heartbeat contents, admission at the holder's
  capacity, the check probe rule, and the DST conditions. "Last-write-wins" became "its first
  answer counts". "+/- jitter" in the batch sub-mode became "when its work ends". The `nodeCapacity`
  config doc says it is a ceiling.

## Design decisions made without instruction

**The heartbeat reports counts per interval, not totals or a rate.** The controller pools them, so
nothing is lost when a beat splits a search's count from its time.
- Alternative: cumulative counters since the node started. Needs a baseline per link.
- Alternative: the node reports its own rate. Hides the sample size the controller needs.

**Only searches over the held area count, timed from taking the job to settling it.** Probes and
requests for another region cost almost nothing and would make the node look faster than it is.

**The rate is the median of pooled samples.** One search paused for seconds (the e2e failover run
does `SIGSTOP` a node mid-search) would otherwise cut the rate for a whole sample, and a low rate
admits fewer searches, so the next sample comes slowly.
- Alternative: one sample of the last 64 searches. Simpler, but a stall throttles the node for
  seconds.
- Alternative: thread CPU time instead of wall time. Immune to pauses, but blind to a node with more
  workers than cores.

**The assumed capacity stays a ceiling.** At low load fewer workers share the cores, so a measured
rate runs high. The spec already said "or less if measured".
- Alternative: let the measured rate exceed it. Self-tuning, but can over-admit when load arrives.

**No lapse; a lost link resets.** The rate comes from the node's own searches, so it does not go
stale the way a load-based guess did. A reconnect may be a new process, so it starts over.

**A node slower than one request a tick still takes one request with nothing ahead.** Otherwise
admission would send it nothing and its rate could never be measured again.

**CLAUDE.md wording.** I replaced the last-write-wins sentence with what the code does (first answer
counts; copies carry the same bytes), as the audit suggested. I also replaced "+/- jitter" with
"when its work ends", since nothing adds jitter.
- Alternative for jitter: add a random send delay in batch mode. It adds latency for no gain the
  spec states.

**A guard instead of a latency negative control.** A run past the latency edge still met the SLO
under overload: requests were superseded and the admitted rate fell, so the check passed for a good
reason. The guard runs a controller that assumes four times the node's capacity, and the overload
check catches its warm-up burst.

**The overload check uses the simulator's exact timing.** The "load fits" check asks that a batch
be answered within one tick, round trip included. Under overload the queue alone takes most of a
tick, so that bound almost never held. The overload check uses the real deadline: the hand-over
that ends the next tick.
- Alternative: keep the one-tick bound and make the sampler draw tiny latencies. It would rarely
  test anything realistic.

**A suspicion keeps the request path's evidence instead of opening the circuit.** Heartbeats alone
still restore a suspected node whose requests were answered, as the spec says.
- Alternative: open the circuit on every suspicion. Simpler, but every heartbeat hiccup would then
  cost a probe and a backoff.

**Under overload, only answers and timeouts count against the SLO.** A cut or a replica drops the
requests in flight of the region it changes, and its new holder waits for chunks. That is the
placement design, and runs whose load fits still count it.

**Some sampled scenarios assume a modest capacity.** Without it, under 1 in 2000 coverage seeds was
overloaded and held to the SLO.

**Test-only accessors stay.** `NodeHealth.samples/suspected/circuit` and `Controller.serves` and
`regionSettled` follow the existing test-seam pattern.

## Verification

- Unit suites: node 207 tests (also under ASan with UBSan, g++, and under TSan, clang++), controller
  266, mod 49, `mixinTest` 47.
- Conformance: regenerated with `make conformance`; two new cases, and every heartbeat case now
  states its search count. `ModeledNode` and the C++ node both pass. Parity fixtures unchanged.
- Fuzz at CI sizes, as parallel JVMs from a class snapshot: shallow 4 x 8000, deep 8 x 2500,
  coverage 2000, all pass. Coverage: 96 seeds held to the SLO (34 at the latency edge), 9 of them
  while shedding past capacity, 77 with sheds counted late, 54 halves kept, 7 replicas reclaimed,
  964 runs with a node measured below the assumed capacity. The first sweeps failed seed 561 and
  deep seeds 3362, 8937, 14926, 16603 and 19059; each is fixed above.
- Boot test passes. E2E passes in all seven runs:
  - shadow: 254 of 254 answers matched vanilla;
  - `on` batch: 280 answers, all moved a mob; `on` stream: 285, 0 or 1 tick late;
  - failover batch: the failover served 81 calls while the node was paused and 33 while it was
    down; stream: 72 and 53;
  - split: node-0 logged `kept region 1 as region 2 at sequence 162`, the spare ran 301 searches,
    and answers moved 598 mobs after the cut;
  - replica: the spare ran 168 searches.
- Throwaway calibration (not kept): one node serving 4 a tick under overload. With the measured rate
  in admission, admitted p99 was 2 ticks at an estimate of 200% (3 without it, with 50 times the
  resends). The new busy-server test sheds 44 of 90 requests with the old batch accounting.
