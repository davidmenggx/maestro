# Audit fixes: admission, the SLO check, datagram-sized batches

This round fixes what the audit of the parity work and its follow-up found. The controller no
longer forgets work it handed to a node: a request it drops for the mob's sake keeps counting
against the node until the node should be done with it, and the queue-depth bound now allows for
the two ticks a request takes to reach the node. The resend timer had counted elapsed time twice,
so deep queues were resent early; each copy now gets a fixed due tick when it leaves. A tick's
requests to one node leave in frames of at most 24, so each fits one unfragmented datagram, and
the simulated network refuses oversized datagrams and loses fragmented ones more often. The DST
SLO check now counts queue-depth rejections and timeouts as misses, measures latency from its own
request ledger, and half of the clean runs sit at the latency edge, where a round trip takes nearly
a tick. The ledger matches each outcome to one request instead of counting per mob. The minor
issues, weak tests and stale comments from the audit are fixed, except the two numeric parity gaps,
which are deferred.

## Changes

- Admission (`ProbeController`):
  - A request dropped as superseded, canceled or mob gone is marked `abandoned` and stays in
    `outstanding`, so it still counts in `inFlight`. It is released when its answer arrives, when
    its due tick passes, after `MAX_TICKS_LATE`, or with its connection. One that never left the
    controller is removed from the pending batch and released at once.
  - The bound is `ahead / nodeCapacity + SEND_TICKS > MAX_TICKS_LATE`, with `SEND_TICKS = 2`: the
    batch leaves at the end of the next tick, and the node serves it in the tick after.
  - A repeat request supersedes the older one before any admission check, so a refused repeat
    still drops the older one, as `WorldEvent.PathRequested` says.
- Resend timing: `Outstanding.dueTick` is set when the batch leaves, from the requests the node is
  still expected to hold (`expectedQueue`) plus the whole batch, since its frames may arrive in any
  order. `settleDueAnswers` resends a due live request and releases a due abandoned one. The old
  check compared ticks since sending with the requests ahead now, which shrinks as the queue
  drains, so a deep request looked due at about half its wait.
- Datagrams:
  - `Frames.MAX_REQUESTS_PER_FRAME` (24) and `MAX_REQUEST_FRAME_BYTES` (1200, under IPv6's
    minimum MTU payload). `Frames.requests` refuses a larger batch. A node's tick batch is one
    offload job that returns its frames (`requestFrames`), counted as `requests.frames`.
  - `Frames.MAX_FRAME_BYTES` mirrors the node's `MaxPayloadSize`. `Frames.delta` takes at most
    `MAX_CELLS_PER_DELTA` (65536) changes, and `flushCells` splits a region's changes into several
    deltas, so a huge tick of block changes no longer makes a frame the node refuses forever.
  - `SimTransport` refuses an unordered frame over 65507 bytes or an ordered one over
    `MAX_FRAME_BYTES` (`transport.oversize`, an invariant violation), and loses a datagram split
    into `FRAGMENT_BYTES` fragments with `1 - (1 - p)^fragments`.
- SLO (DST):
  - `RecordingOutput` keeps a ledger of open requests per mob, oldest first, and measures each
    answer's latency against it. Between events a mob has at most one request open, and a mob with
    two must close the older as `SUPERSEDED`. An answer must match the open request's tick and
    target.
  - `Invariants.checkSlo(latencies, misses)`: misses are `QUEUE_DEPTH` and `TIMEOUT` drops
    (`RecordingOutput.sloMisses`). A run that refuses everything now fails.
  - `FaultPlan.MAX_ONE_WAY_LATENCY_STEPS` (498): the longest one-way latency at which a correct
    controller still answers in two ticks. Half of the clean plans (`EDGE_CHANCE`) draw latency in
    the 100 steps below it.
- Responses: `ProbeController` decodes each frame in full before using it
  (`decodeResponse`, `decodeControlReply`), so a cut-short or foreign datagram is counted as
  `controller.malformed.frame` instead of throwing. An answer from a node the request did not go
  to is ignored (`path.response.misrouted`).
- Start chunks: `RegionStream.resident` maps each chunk the holder has as resident to the sequence
  that made it resident, replacing `sentChunks`. A request must start in a resident chunk. An
  answer whose `world_sequence` is below that sequence was searched over air, and is dropped as
  `NO_NODE` (`path.response.behind`). A block change in a chunk sent as cleared is no longer sent.
- Profiles: `MobClass.Shape` and the node's `Profile::decode` take width up to 64, height up to
  256 and step height up to 16, and refuse NaN. `SearchCall.read` leaves a larger mob to vanilla.
- Encoder checks: `Frames.readChunk` refuses a floor outside 0..32 or a derived type, and
  `Frames.assignment` refuses an area or column a short cannot index.
- Node: `obs::shutdown` installs a no-op tracer provider instead of a null one.
- Mod: `LevelWorldSource.uniformCell` asks the section's palette (`maybeHas`) instead of scanning
  all 4096 entries.
- Harness:
  - `ModeledNode` refuses a floor above 32, as the real node does.
  - The fresh-state comparison reads the world directly (`Invariants.freshDigest`), not through
    the encoder under test. `FRESH_NODE` and the comparison node are gone.
  - `SimWorld.uniformCell` reports solid and air sections, so the DST runs the section shortcut.
  - `NodeHashesTest` finds the positions a search can create from vanilla's rules, not from the
    bounds under test.
  - `NondeterminismGuardTest` also refuses `Collections.shuffle`, parallel streams,
    `Runtime.availableProcessors` and `System.getenv`. A rule owner may be `*`.
  - The coverage tier checks transport duplication instead of node-side duplicates, and that some
    run at the latency edge was held to the SLO, an abandoned request was answered, and an answer
    was searched before its start chunk arrived.
  - C++: the reader test compares every concurrent search with one result, four `WorldStore`
    validation branches have tests, and a test that rebuilt what it had just built is gone.
- Removed: `RecordingOutput.dropped()`, `RegionStream.cells`, the per-node `RETENTION_FRAMES`
  wording, and the wrong `PathCanceled`, `reportedDepth` and `framing.hpp` comments.
- CLAUDE.md: the resend sentence now says the wait counts the requests the node holds ahead.

## Results

- 124 controller and 21 mod Java tests pass. 111 node tests pass, also under ASan and UBSan. The
  parity test still matches every exact case, and a re-capture of seed 3 is byte-identical.
- A parallel scan of every CI seed range (seeds 1 to 100000 of the fuzzed, reliable, lossy and
  churn runs, 400000 runs) found no violation. 9513 fuzzed runs were held to the SLO, 4689 of
  them at the latency edge. Reliable and churn runs resend nothing and time nothing out. At 5% loss
  each way, 4262 of about 8.1 million requests timed out. The coverage tier, small shallow and
  deep sweeps, and the release boot pass.
- Saturated scenario, seeds 1 to 20: 1517 answered and no timeouts, against 1424 answered and
  2370 timeouts before. Nodes still answered 8321 requests their mobs had already replaced, which
  is the work a `Cancel` message would save.

## Design decisions made without instruction

**A dropped request is released when it is due, not when the node confirms.** Nothing tells the
controller when a node finishes a request it no longer wants, so the controller uses the due tick
it already computes for resends. A `TIMEOUT` releases at once, since admission keeps work inside
the same budget. Alternative: the `Cancel` message (option b), which was not chosen.

**The whole tick batch shares one due tick.** Its frames leave together over UDP and may arrive in
any order, so a per-position due tick resent whole frames that merely arrived second. The cost is
slower loss detection inside a large batch. Alternative: per-frame due ticks, which still resend on
reordering.

**A request frame holds 24 requests, a fixed count.** 24 of the largest requests fit 1200 bytes,
which a test checks. Alternative: measure the encoded size while building, which FlatBuffers makes
awkward because it builds back to front.

**The fragment model draws once per datagram.** A one-fragment datagram uses the plan's rate
unchanged, so runs with small frames replay as before. Alternative: one draw per fragment.

**The edge latency is 498 steps one way.** A batch leaves at step 999, must arrive by the service
step (500) of the next tick, and its answer must arrive before step 999, where the resend check
runs. A test at 510 steps shows that even this controller then misses the SLO.

**`fitsCapacity` still means no backlog.** Only the two SLO changes that were chosen were made. A
controller that over-admits still removes its run from the SLO check, but admission is now covered
by the saturation test, which requires no timeouts.

**A request must start in a resident chunk, and an answer must postdate it.** Requests from a chunk
sent as cleared are refused instead of searched over air. An answer searched before the chunk
arrived is dropped rather than resent, because the node replays its cached answer to a resend.
Alternative: admit only once the chunk frame has left, which delays every new chunk by a tick.

**Malformed control frames are ignored too.** They come over TCP from a node, but one bad frame
should not stop the controller. Alternative: reset the connection, which the transport has no call
for yet.

**The heartbeat depth is not aged.** Aging it at the assumed capacity would under-count a slow
node, which is what heartbeats are there to catch. Only its comment changed.

**The profile bounds are 64, 256 and 16.** They hold the giant at scale 16 (57.6 by 192) and the
step attribute's maximum (10). Alternative: vanilla's exact maxima, which leave no margin.

## Not done

- The two numeric parity gaps (the 1e-7 collision tolerance, and sweep maths in relative
  coordinates) are deferred.
- `make test-boot` still proves only `MinecraftServerMixin` by its log line. A broken
  `PathFinderMixin` fails the boot only if `PathFinder` is loaded during it.
- After a fresh assignment the node still takes any first sequence, so a skipped first frame is
  caught only by the final state comparison.
