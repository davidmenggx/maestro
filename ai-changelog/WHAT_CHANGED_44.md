# Load generator mod

A new mod, `maestro-load`, makes reproducible load for experiments. It lives in its own source set
(`server/src/load`) and jar (`loadJar`, built by `assemble`), and is never part of the released
jar. `/maestro load start <name>` reads `<game dir>/maestro-load/scenarios/<name>.json`, places
server-side fake players and seeded mobs, waits out a warmup, then measures until its duration ends
or `/maestro load stop`. Natural spawning is off during a run, and everything the run placed is
removed at the end. A run that measured writes a JSON report to `<game dir>/maestro-load/reports/`:
the scenario, the released mod's version and config, tick times (mean, p50, p99, max, ticks over
50 ms), what became of the offloaded requests, and answers by ticks late. `runLoadServer` boots the
released mod with the load mod. The end-to-end test now uses it in place of hand-written `summon`
commands and also checks the report. The released mod gained one public read: `Fleet.stats()`
returns an `OffloadStats` (request outcome counts and the ticks-late histogram), and the stop
summary is built from it in the same format as before.

## Design decisions

- **A separate mod, not the dev mod or the released jar.** Chosen with the user. The dev mod's
  capture mixin hooks `hasCollisions` and `getFloorLevel` in every vanilla search, which would skew
  benchmarks. The released jar should not carry fake players or mass spawning.
- **Report data from a public `Fleet.stats()`.** `PathOffload` already kept outcome counts and a
  ticks-late histogram for its log summary. Alternatives: a `Metrics` wrapper keeping in-memory
  totals of every metric (more code on the hot path), or parsing the logged summary (fragile).
- **Fake players are Fabric `FakePlayer`s added straight to the level, in creative mode.** They
  count among `level.players()` (so the fleet reports them and chunks load around them) but not the
  server's player list. A subclass makes a fresh instance each run, since `FakePlayer.get` caches
  one per profile and a removed entity cannot be added again. Creative mode keeps mobs from
  targeting them, so hunting mobs chase their scenario targets. Alternative: a Carpet-style join
  through `PlayerList` with a fake connection, which needs more engine internals and mixins.
- **The run refuses to start if `pause-when-empty-seconds` is above 0.** The server counts only
  its player list, so it would pause with only load players present.
- **Only the `spawn_mobs` game rule is turned off.** In 26.1 it gates natural spawning and every
  custom spawner (phantoms, patrols, cats, sieges, traders). Its old value is restored at the end.
- **Placement.** Each group's entities stand at blocks drawn by `java.util.Random(seed)`, uniform in
  a square of `spread` around its `x`, `z`, at the block centre on the surface. Coordinates are
  absolute, since regions sit on a lattice around the origin, not around spawn. Entities are built
  as `/summon` does with NBT, so `finalizeSpawn` is skipped (no random babies or equipment). They
  are tagged `maestro_load` and made persistent. 64 are placed per tick. There is no ramp: a sweep
  over load levels is a series of runs.
- **The run waits until every spawn chunk ticks entities** (60 s at most, then it fails), rather
  than force loading. A scenario whose mobs are out of every player's range fails loudly instead of
  measuring mobs that never tick.
- **Tick time is measured from the first `START_SERVER_TICK` listener to the last
  `END_SERVER_TICK` listener**, using event phases. Vanilla's `tickTimesNanos` is taken before
  the end-of-tick listeners, so it would miss the controller's end-of-tick work.
- **Durations count ticks, 20 to a second.** A lagging server measures the same number of ticks
  over a longer wall time, which the report gives as `wallSeconds`.
- **Load mod unit tests run in the plain `test` source set**, with the load classes on its
  classpath, instead of a new test source set and task.
- **End-to-end placement changed.** Mobs now stand at seeded positions within the old areas, and
  a load player stands 24 blocks south of each crowd. Force loading now covers only the
  block-change area.
