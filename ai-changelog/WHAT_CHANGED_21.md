# World state sent a chunk at a time, so a region no longer has to be complete to serve

The world stream carried one snapshot per region and could not send it until every chunk of that
region was resident. At the 256-block region size that is 256 chunks at once, which a live server
rarely holds, so regions would have gone unserved; and a single chunk leaving threw the whole
region away and resent it. State is now per chunk. The unit suites and a 400-seed run of every
fuzz tier pass.

## What the node holds

`RegionAssignment` gained `min_y` and `height`, so a node can size its area from the assignment
alone and fill it with air before any content arrives. Air is the right default: vanilla's
`PathNavigationRegion` returns air for a chunk it could not fetch, so a node missing a chunk now
reads exactly what vanilla reads.

`WorldSnapshot` is replaced by `ChunkSnapshot`, which carries one chunk's 256 run-length encoded
columns. An empty `run_counts` means the server is not holding that chunk and it reads as air, so
one message covers a chunk arriving, changing and leaving. `chunk_xz` is the chunk's index within
the assigned area rather than a world chunk coordinate, matching every other position on the wire.

## What the controller does

`snapshotted[]` and `regionLoaded` are gone. Each node tracks the chunks it has been sent and the
chunks whose state has changed and not been sent; a chunk load or unload marks one chunk dirty on
one node, and `flushChunks` sends one frame per dirty chunk at the tick boundary. A resnapshot
marks every resident chunk of the region dirty rather than reading the whole region at once.

Requests are gated on the chunk the search starts in having reached the node, rather than on the
whole region being resident. Chunk state is produced at the tick boundary and a request is admitted
inside the tick, so a world frame is always a tick ahead of the first request that needs it, which
is the same head start that made the two channels safe for mob profiles.

Chunk state belongs to the region rather than the connection, so the sent and dirty sets survive a
reconnect the way the outbox does. They are cleared only when the controller decides to resend.

A cell delta is only produced for a chunk the node already holds and that is not being resent this
tick, since a chunk read at the tick boundary already carries every change made during it.

## Sequence baseline

The controller's sequence carries on across a reset, but a node that drops its state has no way to
know where the controller got to. Previously the region snapshot carried an absolute sequence the
node adopted. A node now takes the first frame after a fresh assignment as its baseline and
requires contiguity from there. The alternative, a `next_sequence` on the assignment, would let the
node check that first frame too, at the cost of a field whose only reader is a check the node
cannot otherwise perform.

## Tests

`anUnloadedChunkStopsItsRegionUntilTheChunkReturns` became
`anUnloadedChunkIsClearedOnTheNodeAndTheRegionKeepsServing`: the region keeps answering, two chunk
frames go out (one to clear, one to fill), and no region is resent. `eachRegionIsSnapshottedOnce`
counts chunk frames. The chunk-churn sweep asserts chunks were cleared and sent again rather than
counting region resnapshots, and no longer asserts that churn makes a region unreadable, because
it no longer does. The C++ snapshot test covers a 16x16 chunk and a chunk with no columns.

## Design decisions made without instruction

**Absent columns rather than a separate clear message.** A `ChunkCleared` table would read more
plainly, but it is a second union arm and a second decode path for a message that differs from
`ChunkSnapshot` only in having no content. The absent vector is checked in the C++ suite so the
distinction between "no columns" and "an empty vector" is not left to a comment.

**Requests gated on the start chunk only.** Vanilla reads a box of about six chunks around the
start, so gating on the whole footprint would be closer to it. The start chunk is what decides
whether a search can begin at all, the surrounding chunks read as air exactly as vanilla reads
them, and a mob is always in a loaded chunk, so the wider gate would reject work that vanilla
would do.
