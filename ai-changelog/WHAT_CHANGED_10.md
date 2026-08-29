# Resume-from-N implemented, and the control channel split by direction

Resume-from-N was in the design but had never been built: `RegionAssignment.resume_from_sequence`
was declared with no reader or writer, and every reconnect got a full snapshot. It is now
implemented end to end, which forced the schema change that had been pending.

A node opens each new connection with `Hello { region_id, last_sequence }`, saying which region it
still holds and how far its world stream got. `last_sequence` is 0 when it holds nothing, which is
what a restarted process reports. The controller replies with a `RegionAssignment` whose
`resume_from_sequence` is either that sequence, meaning deltas continue from the next one, or 0,
meaning a full snapshot follows. The controller sends nothing until the greeting arrives, because
until then it cannot tell a replay from a resnapshot; a `greeted` set gates world frames and path
requests, while `connected` still drives the reconnect loop.

This supersedes WHAT_CHANGED_9, which removed `Hello` on the grounds that nothing produced it and
its only field was redundant. That analysis was correct for the message as it stood; what changed is
the decision to build resume-from-N, which needs a node-to-controller handshake. `Hello` therefore
returns with different fields in a different union, because the old one could not have done this
job: `node_id:string` duplicates what the connection already establishes, and `ControlMsg` is the
controller-to-node direction, so a node could not have sent it.

The replay window is the outbox. `drainOutbox` used to remove each frame as it sent it; it now
advances a send pointer and leaves frames behind it, evicting only once they are `RETENTION_FRAMES`
(48) behind. That one structure is both the in-order send buffer and the retention log, so there is
no second copy to keep consistent. Deltas are now produced for a region even while its node is
away, so the window rolls during an outage and a node that fell far enough behind is resnapshotted
rather than replayed. Three conditions must all hold to replay: the node reports the region it was
actually assigned, its sequence is at or after the snapshot the retained frames continue from, and
that sequence has not been evicted.

The world stream and its window now belong to the region rather than the connection. `reset` became
`resetConnection` and clears only `queueDepth` and `pendingRequests`; the sequence counter, the
outbox and the region's snapshotted flag survive a reconnect. The `generation` epoch that discarded
in-flight encodes on disconnect was deleted: dropping a frame mid-encode would leave a permanent
hole in the sequence that `drainOutbox` could never advance past, and with sends gated on `greeted`
the epoch was no longer preventing anything.

The schema split follows from `Hello` being a second node-to-controller message on the control
channel. `ResponseMsg` was direction-scoped while the other unions were channel-scoped, so
`ProbeController.onFrame` could parse every inbound frame as a `ResponseFrame` without looking at
the channel. That worked only while every node-to-controller message lived in one union. `Pong`
moved out of `ResponseMsg` into a new `ControlReplyMsg { Hello, Pong }` with a `ControlReplyFrame`
root, so each union is now one channel in one direction, and `onFrame` dispatches on the channel
first. A frame arriving on the world channel is now a recorded self-violation rather than a
misparse.

Resume needed a fault that had no model: a connection that breaks while the process lives.
`FaultPlan.LinkReset` does that, and `FaultPlan.sample` now draws it as a third option alongside
process death and partition, so the fuzz sweep reaches the replay path on its own (about 10 replays
across 120 seeds). Without it the feature would have been untestable, since the existing `NodeDied`
wipes node state and a partition never breaks the connection.

Two mutations confirm the path is checked. Replaying from one sequence too high is caught by both
the targeted test and the fuzz sweep, each reporting the exact gap. Dropping the eviction guard so
the controller resumes into a window it has already discarded is caught by a new `selfCheck`
assertion that the outbox never holds more than `RETENTION_FRAMES`.

`Invariants.checkAgainstFreshSnapshot` builds the fresh comparison node itself, so a test only has
to name the node it wants compared. Controller tests went from 45 to 48: two simulation tests for
the replay path and the too-far-behind path, and two guard tests for the fault sampler reaching
every fault kind and for a node refusing to resume a stream it does not hold. The C++ node tests,
the boot smoke test and 200 fuzzed seeds all pass.
