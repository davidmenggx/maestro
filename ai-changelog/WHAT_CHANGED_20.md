# Dead wire messages and fields removed

An audit of the whole repository for declared-but-unused code. Everything removed had no reader,
no writer, or both. The unit suites and a 500-seed run of every fuzz tier pass.

## Removed

`PathResponseBatch` was never built and never read. A response is one datagram, so there is nothing
to batch.

`PathRequest` as a direct member of `RequestMsg`. Every request goes out inside a
`PathRequestBatch`, including in stream sub-mode, where the batch holds one. `ModeledNode` had a
decode branch for the singleton that nothing could reach.

`MobClassTable`. Profiles are registered one at a time now, so the wrapper only ever held one
entry. `ControlMsg` carries `MobClass` directly and `Frames.mobClass` takes one profile.

`Ping.sequence` and `Pong.sequence`. The controller never read either. `send_micros` is echoed
back, which already names the ping it belongs to and is what phi accrual measures, so the sequence
was a second identifier for the same thing. `ProbeController.pingSequence` went with it.

`Transport.disconnect`, declared and implemented by `SimTransport` with no caller. It was kept once
as half a connection lifecycle that production shutdown would need; it goes now, and comes back
when something calls it.

## Kept, with the reason

`maxVisitedNodesMultiplier` is not in this codebase and never was. It is a vanilla field that is
folded into `max_visited_nodes` before the request is built, which is safe because only `Bee`
changes it and `Bee` flies. `hasValidPathType` is dead in Minecraft, not here.

`max_visited_nodes` itself is load bearing and cannot be derived from `max_path_length`. The
navigation only recomputes the budget when an attribute changes, so a caller that passes a shorter
bound leaves the budget where it was, and the two diverge.

`PathResult.cost` is written and not yet read. Its consumer is the vanilla-parity comparison, which
is the stated purpose of the field.

`WorldEvent.PlayerMoved` and `PlayerLeft` are emitted by the workload and ignored by the
controller. They are world input rather than controller code, and the shedding and failover
priorities that read them are ordered by distance to the nearest player.

The one-member unions `RequestMsg` and `ResponseMsg` were kept. Each costs a type byte and an
offset per frame, and dropping them would make those two channels read a bare root type while the
others read a frame envelope. They are the extension point for a second message on either channel.

## Design decisions made without instruction

**Unions kept at one member rather than removed.** Removing them would save about eight bytes per
response, which is a real fraction of a small response. It was not taken because the frame envelope
is uniform across channels today, and a cancel or a retry message on the request channel would put
it straight back.
