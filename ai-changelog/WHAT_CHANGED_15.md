# Trace context and ownership epoch on the wire, request channel moved to UDP

The schema gained the two fields that get expensive to add once a fleet is running, and the
transport split by channel was decided. No controller or node logic was added: the harness builds
the new fields as zeros and the C++ side only proves they round-trip. The unit suites and a short
sweep of every fuzz tier pass.

## Schema

`PathRequest.trace` is a new `TraceContext` struct (`trace_id_hi`, `trace_id_lo`, `span_id`,
`flags`) in `schema/pathfinding.fbs`, carried on every request rather than sampled. Two `ulong`s
for the trace id rather than a 16-byte array because that is how both OpenTelemetry SDKs expose it
(`TraceId.fromLongs` in Java, a 16-byte span in C++), so neither side converts. `Frames.requests`
writes a zero context for now; minting it needs a tracer seam in `ControllerEnv` that does not
exist yet.

`RegionAssignment.epoch` and `Hello.epoch` are new in `schema/control.fbs`. The epoch increments
each time a region changes owner, and a node reports the one it holds on reconnect, so a node that
was reassigned away during a partition is recognized rather than resumed. `ModeledNode` stores the
epoch it was assigned and echoes it in `Hello`; `ProbeController` does not read it, since it never
reassigns.

The `world_sequence` comment on `PathRequest` no longer says the node may block on it. The node
answers against what it holds and `PathResponse.world_sequence` reports the position used.

## Transport

The world and control channels are TCP and the request channel is UDP, one frame per datagram.
This matches what `SimTransport` already modeled (the unordered channel drops, duplicates and
reorders), so the sweeps were already testing the real channel semantics. Consequence recorded on
`PathRequestBatch`: a batch is bounded by the datagram size, so the controller will have to split a
tick's requests across several. `Transport.Channel` javadoc updated; the enum itself is unchanged.

## Design record

`CLAUDE.md` now states the decisions confirmed in review: interception at
`PathNavigation.createPath`; region size at least 4x the pathfinding horizon because halo cost
grows as `((R + 2H) / R)^2`; TCP for world and control, UDP for requests; never blocking on
`world_sequence`; controller-authoritative ownership with epochs and a fresh server snapshot as the
handoff source; and the main-thread fallback served by passing through to vanilla pathfinding.
The node's concurrency model is not recorded because it is still under discussion.

## Design decisions made without instruction

**`Hello.epoch` alongside `RegionAssignment.epoch`.** Only the assignment side was asked for. The
epoch does nothing unless the node reports what it holds, which is the `Hello` frame's whole job,
so both were added. The alternative was a separate acknowledgement message, which would be one
more frame on the control channel to carry a number `Hello` already has room for.

**`TraceContext` as a struct in `pathfinding.fbs`, not `common.fbs`.** Only `PathRequest` carries
it. It moves to `common.fbs` if a second message needs it.
