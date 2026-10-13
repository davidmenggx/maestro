# Problem C, timeouts, stronger invariants, and the spec deviations

This round implements the decisions from the audit follow-up. The controller no longer sends a
block change after the chunk that held it was sent as cleared in the same tick (problem C). The
DST now draws the order of a tick's event kinds from the seed, so it can produce that sequence,
and the quiet tail keeps unloaded chunks unloaded, so the final comparison can see such a bug. A
request that times out is dropped as `TIMEOUT`, not `SHED`. The invariants now check every
request against a per-mob ledger, require every live node to catch up by the end of a run, and
require a region's sequence to keep rising across resnapshots. The spec deviations are fixed: the
request stream is at-least-once (an unanswered request is resent), world-stream state belongs to
the region, `Frames` reuses its builder, a search whose vanilla node hashes could collide stays on
the server, and the parity test records how far approximated cases land from vanilla.

## Changes

- Problem C: `ProbeController.onTickEnded` flushes block changes before chunks, so a change in a
  chunk about to be sent is left out. Regression test in `WorldBoundaryTest`.
- Event order: `SyntheticWorkload` shuffles its seven kinds of activity each tick with its own
  derived `Rng`. The quiet tail no longer reloads every chunk.
- `DropReason.TIMEOUT` for an expired request. `SHED` stays for load shedding, which nothing
  produces yet, so the coverage sweep no longer requires it.
- At-least-once: an unanswered request is resent under its id, at most `MAX_RESENDS` (2) times.
  It is resent once its answer is `RESEND_TICKS` (1) late, counted from when its batch left the
  controller plus the ticks its node needs for the requests queued ahead of it. The node already
  answered a repeat with the same bytes, and the controller already ignored a second answer.
- Per-region stream: `ProbeController.RegionStream` holds the sequence, outbox, replay window and
  chunk sets of one region. `NodeState` keeps only the connection. `producedSequence` became
  `heldSequence`, which is 0 when nothing was produced since the holder's snapshot.
- Invariants:
  - `RecordingOutput` keeps a ledger per mob. `SimRun.go` enters each request before the
    controller sees it, and every outcome must close an open request. This replaces the global
    total.
  - Every node still alive at the end must have caught up. `Simulation.settleFrom` stops encode
    failures when the run goes quiet, so the tail can settle. `ModeledNode.assigned` lets a node
    that holds only its assignment count as caught up and be compared.
  - `Simulation.applied` records the highest sequence any fleet node applied per region. A lower
    one, even after a fresh assignment, is a violation.
- `Frames` encodes with one `FlatBufferBuilder` per thread, cleared before each frame.
- `NodeHashes.mayCollide` (controller library) says whether two nodes of a search could share
  vanilla's `Node.createHash`. `SearchCall.read` returns null for such a search, so it stays
  vanilla. Captured searches are unaffected (positive coordinates, a 48-block band).
- Parity test: approximated cases report the mean and largest difference in cost and node count,
  printed and recorded as gtest properties.
- `schema/fixture.fbs`: the file comment moved onto `ParityFixture`.
- CLAUDE.md: the hash rule, the resend policy, and the full invariant list.
- Tests: `WorldBoundaryTest` (problem C, a lost request resent and answered, the resend cap),
  `HarnessGuardsTest` (the ledger, a region sequence going back, a node left behind, the event
  order varies), `NodeHashesTest` (the hash, target and in-reach collisions, a brute-force check
  that a passed search has no shared hash).

## Results

130 Java tests (109 controller, 21 mod) and 110 node tests pass, the node tests also under ASan
and UBSan. The local fuzz tiers pass. A parallel scan of every CI seed range (seeds 1 to 100000 of
the fuzzed, chunk-churn, reliable and lossy sweeps) found no violation. Reliable and churn runs
resend nothing and time nothing out. At 5% loss each way, 4278 of about 8.1 million requests timed
out. The parity test still matches all 1163 exact cases. On the 37 approximated cases the node's
cost is 1.7 lower than vanilla's on average (largest gap 30.5) and its path 1.3 nodes shorter
(largest gap 23). A re-capture of seed 3 is byte-identical, and the release boot passes.

## Design decisions made without instruction

**A resend waits for its batch to leave.** A batch is encoded off the main thread and leaves at
the next tick boundary, a tick after it is built. Counting from the issue tick resent almost every
request on a reliable network. Counting from the send resends none there.

**The resend wait covers the node's queue.** A request is due once the node has served the
requests issued before it at the capacity admission assumes. Without this, a backlogged node had
most of its queue resent. Slow nodes still get resends, because the controller assumes uniform
capacity, and so do requests behind ones the controller abandoned but the node still serves. A
resend only costs bandwidth: the node drops a copy it holds and replays an answer it sent.
Alternatives: resend on a fixed age only (more copies), or learn each node's rate (later).

**At most two resends.** A third would arrive past the SLO anyway. Alternative: resend until the
timeout, which costs more bandwidth on a dead link.

**Lossy runs are still not held to the SLO.** With up to 10% loss per frame, two losses in a row
hit more than 1% of requests, so p99 below 3 ticks fails without a bug. The audit reply said
resends would let lossy runs be checked; that was wrong for the sampled loss rates.

**Encodes stop failing when the run goes quiet.** Liveness needs the tail to settle. A late
reconnect plus repeated encode failures could otherwise leave a correct node behind at the end.
Alternative: accept rare false failures, or only require that frames are still in flight.

**The region ledger lives in `Simulation`, keyed by region.** It survives node death and would
cover a new owner after handoff. A node built outside the fleet (the fresh comparison) does not
report to it.

**The ledger counts requests from the event stream, not from the controller.** The invariant
should not trust the controller's own count.

**Vertical bounds for the hash check are conservative.** A mob that cannot float sinks through
water down to the world floor, so its search is kept on the server when its start is within 256
blocks of the floor plus its reach. In the overworld that keeps, for example, zombies (follow
range 35) above about y=157 on the server. Alternative: send the region's world origin in the
assignment so the node keys its nodes by vanilla's own hash. That is exact and keeps every search
offloaded, but changes the wire and the node's node table.

**Builder reuse uses a `ThreadLocal` in `Frames`.** It needs no new parameter on any encoder.
The finished bytes are still copied, because the outbox keeps world frames for replay. Writing
requests straight into transport buffers waits for the Netty transport.

**Only the order of event kinds is shuffled.** Events of one kind stay together. Interleaving
single events across the tick is left for the stream sub-mode.
