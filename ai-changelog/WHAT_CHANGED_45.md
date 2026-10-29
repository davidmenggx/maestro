WHAT CHANGED 45: REQUEST TRACING, NODE TIMINGS, CONSUMER LAG, TICK TIME

Summary
The controller now traces a sampled share of admitted path requests from ask to outcome and reports each through a new injected `Tracing` interface in `ControllerEnv`. The mod maps each finished request to OpenTelemetry spans: a `path.request` root with child spans `batch.wait`, `encode`, `node` and `handover`, plus a `resent` event per resend. A trace id is the run id followed by the request id, set through a custom `IdGenerator`, so the wire carries no trace context. The node stays light and exports nothing. `PathResponse` gains `wait_micros` and `search_micros`, and `Heartbeat` gains `region_id` and `sequence` (the area held for the link's run). From these the controller records `node.wait.micros` and `node.search.micros` histograms per node and a `world.lag` gauge (frames produced for the node's region minus frames it applied). The mod also records `server.tick.millis` for every server tick. The DST checks each trace against what its mob was told, and the conformance fixture now checks the new heartbeat fields on both the model and the real node.

Decisions made without explicit instruction
- Sampling uses a SplitMix64 hash of (run, request id), not the controller `Rng`. Changing the rate then changes nothing else the controller draws. A uniform `Rng` draw would have shifted every later draw.
- Finished traces are buffered and reported when the next tick opens or closes. A hand-over runs network frames before offload completions, so in stream mode an answer can be handled before the completion that carries its send time. Alternatives were a cross-thread stamp written by the worker (breaks the offload contract) or reordering the hand-over (changes controller behavior).
- In stream mode the worker stamps the send time itself, read from the injected clock, because the completion that records the send runs later.
- A send recorded after the outcome is ignored, so a trace's stages stay in time order. A batch encode can finish after its mob gave up.
- Node timing histograms count answers to admitted requests only (live or abandoned), not probes and not stale copies, so each search counts once.
- `world.lag` is skipped while a node still reports another region (a hand-off in flight), and is 0 for a node holding none.
- The default `traceSampleRate` is 0.01 in `maestro.json`. Scripted DST runs trace every request; synthetic and fuzzed runs trace all requests in half the runs and a random share in the rest, drawn from a separate derived stream so existing seeds replay the same scenarios.
- `server.tick.millis` covers the whole tick, Maestro's hand-overs included, and is recorded with or without a fleet, so a vanilla baseline is comparable.
- Conformance answers still compare only request id, run and sequence; the timings are not checked there.
