# Deterministic simulation seams and harness

The controller now has somewhere to live and something to be tested by. `server/controller` is a new
plain Java Gradle module with no Minecraft, Fabric or Loom on its classpath, so controller code
cannot reach a game type even by accident and its tests run on a bare JVM in seconds. The generated
FlatBuffers bindings moved there too (`server/controller/src/generated/java`, `GEN_JAVA` in the
`Makefile`), since the transport seam carries encoded frames; the mod picks them up through an `api`
dependency and the mod jar merges the module's classes rather than nesting them, which
`unzip -l` on `maestro-0.1.0.jar` confirms.

The seams themselves are in `com.smpler.maestro.controller.env`: `Clock` (tick plus monotonic
nanos), `Rng` (a `RandomGenerator` with a named `derive` for independent substreams, implemented
once by `SeededRng` on a pinned `L64X128MixRandom`), `Offload` (work off the main thread whose
completion runs back on the controller thread at a tick boundary), `Transport` (byte frames over
three `Channel`s) and `Metrics`, bundled into a `ControllerEnv` record that controller code takes
instead of reaching for a global. The world boundary is in `...controller.world`: a sealed
`WorldEvent` covering tick boundaries, block changes, chunk load and unload, mob lifecycle, path
requests and player positions; `WorldInput` to push them in; `WorldOutput` to hand back a
`PathResult` or a `DropReason`; and `WorldSource` for the pull-side region reads a snapshot needs.
`com.smpler.maestro.bridge` holds the production half, `ServerClock` and `PoolOffload`, wired in
`MaestroServer` to `END_SERVER_TICK`.

The harness is in the controller module's test sources. `Simulation` runs a tick-driven event queue
where one tick is 1000 steps of 50 microseconds each, ordered by (time, insertion), and folds every
event, frame and output call into a `Digest`. `SimTransport` models the network with per-channel
semantics, `ModeledNode` decodes real frames with the generated bindings and holds the region state a
stateful node would, `SyntheticWorkload` generates the world events a recorded trace will later
supply, and `Invariants` checks sequence monotonicity, request accounting, p99 against the tick
budget and post-failover state equality. `SimulationTest` covers replay from a seed, node death,
partition, loss and duplication, and saturation; `ModeledNodeTest` covers failover equivalence and
proves the gap detector actually fires; `NondeterminismGuardTest` reads the compiled controller with
the JDK class-file API and fails on a direct clock, random, thread or socket reference, and includes
a deliberately bad sample class so the scan cannot pass vacuously. `make dst` runs the lot,
`make dst SEED=n` replays one seed.

## Design decisions made without explicit instruction

- **Ordered channels never lose a frame; only unordered ones do.** `Transport.Channel` carries an
  `ordered()` flag: `WORLD` and `CONTROL` are TCP-shaped, `REQUEST` is not. A simulator that dropped
  a world delta silently would manufacture sequence gaps TCP cannot produce, so the controller would
  be tested against a failure mode it will never see, while the failure it will see, a connection
  reset, went untested. For the same reason a partition holds ordered frames and releases them in
  order when it lifts, rather than discarding them.
- **Process death and partition are modeled as different faults.** Death resets the connection, so
  `onDisconnected` fires; a partition is silent, and its requests only end via timeout. That
  distinction is the entire argument for a phi accrual detector, and
  `aPartitionedNodeIsSilentAndItsRequestsTimeOut` pins it.
- **The example controller is a test double, not shipped code.** `ProbeController` lives in test
  sources. It proves every seam carries a real workload end to end, but region assignment,
  resharding, failover and the detector are absent on purpose, since those are the logic this
  harness exists to test. The wire codec stayed with it rather than moving to main for the same
  reason: it should be written once, next to the real controller.
- **Frame encoding is offloaded but the world read is not.** `WorldSource` is only safe on the main
  thread, so `ProbeController` reads a region's runs inline and offloads only the encode. This
  exposed a real hazard the seam creates: two encodes can complete in either order, so the world
  stream would gap. The probe parks frames by sequence in an outbox and sends the contiguous prefix,
  which is what the real controller will have to do.
- **Region assignment is sent on the world channel, not the control channel.** It has to reach a
  node before the snapshot it frames, and only one channel orders the two. The schema already allows
  `RegionAssignment` in both unions.
- **`SUPERSEDED` was added to `DropReason`.** A mob that repaths before its previous request is
  answered would otherwise leave a request with no callback, which makes "every request is answered
  or dropped" uncheckable. One outstanding request per mob, and the abandoned one is reported.
- **Requests that go unanswered past the queue-depth bound are dropped as `SHED`.** A lost request
  frame or a silent node would otherwise leave a request outstanding forever. `SHED` fits: the
  controller gives up to hold the SLO. A dedicated timeout reason was the alternative and can be
  split out later if the mod needs to tell the two apart.
- **Admission control estimates queue depth from what is in flight, not from the last `Pong`.**
  Heartbeats are 20 ticks apart, and a reported depth that stale rejected everything once a node
  fell behind. The controller already knows its own outstanding count per node, which is the
  tighter bound.
- **The simulated world is 32x32 blocks and 64 tall**, not a real overworld column. Height changes
  what a snapshot costs, not what the controller does with one, and a short column keeps a run fast.
- **`PoolOffload.drain` runs before `ServerClock.advance`.** A completion is then attributed to the
  tick that just ran rather than the one about to start, which is what the simulator models.
- **Iteration order is not checked by the bytecode guard.** It cannot be seen in a constant pool.
  The repeated-run digest covers it instead: two runs in one JVM see different identity hash codes,
  so an identity-ordered collection breaks the digest.
