# Audit fixes: node reset on a bad frame, unknown profiles, encode retries, and cheaper versions

An audit of the C++ node found no divergence from vanilla in the search, but found gaps where the
node meets the rest of the system. This change closes them. A node that cannot apply a world frame
now drops all world state, so it reconnects and gets its area again instead of stalling. A request
that overtakes the profile it names is answered `UnknownProfile` and dropped at once. A failed
encode is run again under the same sequence. `Area` versions copy two rows of pointers instead of
the whole chunk table, and search scratch maps clear in constant time. The DST model now refuses
exactly what the real node refuses, the simulator can stall the ordered channels and fail encodes,
and CI runs the node's tests under ASan, UBSan and TSan.

## Spec (`CLAUDE.md`)

- Interception covers only searches whose path finder and evaluator are exactly `PathFinder` and
  `WalkNodeEvaluator`. The Warden (a `PathFinder` subclass with XZ distance) and the Creaking (an
  evaluator subclass) stay vanilla. The profile is read between `prepare` and `done`, because
  `Sniffer.onPathfindingStart` changes its water malus.
- A request is sent only if its search fits the node's area:
  `ceil(maxPathLength) + floor(width + 1) + 1` blocks around the start. A node reads past its area
  as air. `SearchReach` implements the check; it is wired in when the halo exists.
- Collision heights are rounded up to a sixteenth on purpose. Only rare shapes such as the lily
  pad (1.5/16) move.
- A node that cannot apply a world frame resets fully; there is no NACK. A failed encode is redone
  under the same sequence. A request that overtakes its profile gets `UnknownProfile`.

## Wire (`schema/`)

- `ResponseMsg` gained `UnknownProfile { request_id, class_id }`.
- `RegionAssignment.min_xz` is removed. The node never read it: every position it sees is relative.

## Node (`node/`)

- `WorldStore::apply` drops everything held on any status other than `Applied`.
- `Area` now carries `run()` and `regionId()`. `WorldStore::run`, `regionId` and `lastSequence` are
  gone, so a `Hello` built from one `pin()` cannot mix two stream positions.
- `Area` is a two-level table: `Rows` indexed by chunk X, each a shared row of chunk pointers. A
  frame copies the row table and the rows it touches. Measured on the same machine, a one-block
  delta went from 3.2 to 1.2 us at a 384 area and from 8.1 to 1.5 us at 640.
- `CoordMap` marks live slots with a 16-bit generation. `clear` is constant time, and a wrap wipes
  the slots once. A short search after a 2048-node one went from about 20 us to 2.7 us.
- `encodeResponse` clears the builder and writes nodes in place, and asserts each position fits
  16 bits. `encodeUnknownProfile` is new.
- `net::peekHeader` replaces `peekLength` and reports `TooLarge` past `MaxPayloadSize` (8 MiB).
- `obs` uses the batch span processor, so ending a span never waits on the network.
- Removed: `--stateless`, `--port` and the acceptor that bound and closed at once, and the redundant
  range check in `isValidPerBlock`. The file comments in `framing.hpp` and `obs.hpp` moved onto the
  declarations.

## Controller and simulator (`server/`)

- `Offload.submit` takes a `failure` callback. `PoolOffload` reports failures at the tick boundary
  instead of dropping them.
- `ProbeController.encode` runs a failed job again at the next tick boundary. The job captured its
  inputs, so the retry builds the same bytes under the same sequence.
- `ProbeController` handles `UnknownProfile` by dropping the request as `CLASS_UNREGISTERED`.
- `WorldEvent.Search` now bounds `reachRange` (255) and `maxVisitedNodes` (65535). `Frames` narrows
  through checked `int16` and `uint16` helpers.
- `ModeledNode` checks the same contract as `WorldStore`: geometry, resume run, region and shape,
  sequence 0, chunk position and columns, and delta cells inside the area. A refusal records a
  violation, kills the node's state and resets its link. An unknown profile gets `UnknownProfile`.
- `FaultPlan` gained a `Stalled` fault and `offloadFailureRate`. Both are sampled.

## Tests

The C++ suite has 108 tests (19 new): a 300-frame random run against a dense model, readers
pinning and searching while frames are applied (also the TSan check), the two-level sharing, the
reset rule, `CoordMap` including a generation wrap, the frame cap, builder reuse and
`UnknownProfile`. Removing the wipe on a generation wrap, and writing through a shared row instead
of copying it, each made the new tests fail. The suite passes under ASan and UBSan with GCC and under TSan with Clang, and the node builds with
OpenTelemetry on. Java gained `SearchReachTest`, model refusal and wire width tests,
`aRequestThatOvertakesItsProfileIsDroppedAtOnce` and `aFailedEncodeIsRetriedUnderTheSameSequence`.
Both simulation tests fail when their fix is removed. The coverage tier now requires an overtaken
profile and a retried encode. The shallow tiers require no `UnknownProfile` at all, because nothing
stalls there.

## Design decisions made without instruction

**`UnknownProfile` is its own response, and the controller does not resend the profile.** A
status field on `PathResponse` would make an empty path mean two things. Resending is not needed:
the profile is already on a TCP connection that delivers it or breaks.

**The controller checks that an overtaken profile was really sent.** Before, the model flagged
any request naming an unregistered profile, which caught a controller that forgot to register.
Now that case is a normal reply, so `onUnknownProfile` records a violation for a profile never
sent on the connection, and the stall-free sweeps assert there are none.

**A stall holds every ordered channel of the node.** World and control are separate TCP streams,
so a stall could hit one. Holding both is simpler and still lets UDP overtake the profile.
Alternative: a stall per channel.

**Request batches are retried too.** Only world frames need the same sequence, but retrying
everything keeps one rule. Without it a failed batch would expire as `SHED`, which names the wrong
cause.

**Collision height rounds up.** It is conservative for collisions. Alternative: round to nearest.

**The generation counter is 16 bits.** Slot size is the same as with 32 bits, the full wipe costs
one pass every 65535 searches, and the wrap can be tested.

**The model resets its link on a refusal.** This mirrors the reconnect the real node does. The run
has already failed by then, but later behavior stays realistic.

**Every sampled run draws a failure rate and may draw stalls.** This changes what every fuzz seed
does, so the full sweep was run again.
