# Heartbeats pushed by the node, and `Ping`/`Pong` removed

The control channel had the controller sending a `Ping` and the node answering with a `Pong`
carrying its queue depth. The node now pushes a `Heartbeat` on its own timer and nothing solicits
it. The unit suites and a 400-seed run of every fuzz tier pass.

## Why this does not remove phi accrual

Phi accrual works on the intervals between heartbeat arrivals, measured by the receiver. It does
not use round trip time and it does not need the sender's clock. Request and reply provided neither
of those things any better than a push does, and it provided one thing that actively gets in the
way: a `Pong` interval mixes how long the node took to answer with how long the network took, while
the algorithm assumes samples of one arrival process.

Pushing also removes a timestamp from the wire. With an echo the node had to send back a time so
the controller could compute a round trip against its own clock. With a push the controller stamps
arrival itself, so `Heartbeat` carries queue depth and nothing else, and no comparison between the
server's clock and a node's clock exists to go wrong.

What is lost is that a missing `Pong` was unambiguous, because the controller knew it had asked.
With a push, silence and a slow cadence look alike until a detector is actually reading the
intervals. That is what phi accrual is for, so the ambiguity is the detector's input rather than a
gap.

## Changes

`Ping` and `Pong` are gone from `schema/control.fbs`, replaced by `Heartbeat { queue_depth }` in
`ControlReplyMsg`. `ControlMsg` is down to `MobClass`, so the controller-to-node control channel now
carries profile registrations only.

`ProbeController` no longer sends anything on its tick loop; that loop is now reconnects only and
its constant is `RECONNECT_TICKS`. `ModeledNode` counts its own serviced ticks and sends a heartbeat
every twentieth, which is the deterministic stand-in for a wall-clock timer on a real node.

The controller records each reported depth as `node.queue.reported`, which is the only view of a
node's backlog production has, and is one of the metrics the observability list asks for.

## Tests

`aNodeReportsItsQueueDepthWithoutBeingAsked` asserts a non-zero depth reaches the controller under
a saturated fleet. Deleting the node's heartbeat call makes it fail, which was checked; without it
the whole message would have had no coverage, since admission control passes on its own in-flight
count alone.

The C++ `PingRoundTrip` case was deleted rather than ported. It built a `Ping`, read it back and
asserted the fields matched, which tests the FlatBuffers runtime rather than anything this project
decides.

## Design decisions made without instruction

**The node's cadence is its own constant, not something the assignment carries.** A controller-set
cadence would let it tighten detection under load, but a node that ignores the setting looks slow,
which is exactly what the detector is there to notice, so the setting would add a knob without
adding a guarantee.

**No sequence number on the heartbeat.** The control channel is ordered and loses nothing without
breaking the connection, and the detector does not distinguish a lost heartbeat from a late one by
design.
