# Audit fixes: dev mod, allocation-free profiles, wider fixtures

The capture command, its probe and its mixin moved out of the released jar into a separate dev mod
(`server/src/dev`) that only `runServer` loads. The `devMode` setting is gone, since a jar without
the code needs no switch. `ProfileTable` now looks profiles up without allocating, and returns null
when its ids run out so the search stays vanilla. `SearchCall` fills a caller-owned malus buffer.
`PoolOffload` reports an `Error` as a failure and no longer lets one bad completion stop the tick
boundary. The parity fixtures were regenerated with longer paths, small visit budgets, block
change deltas, and more exact cases. Entity collisions and the world border are now documented as
intentional approximations.

## Changes

- Dev mod: `maestro-dev` has its own `fabric.mod.json`, mixin config and entrypoint (`CaptureMod`).
  `build.gradle` adds a `dev` source set, registers it as a Loom mod, and makes `runServer` use it.
  The jar holds no capture class (checked). `make capture` and `make test-boot` need no config.
- `ProfileTable`: open-addressed table over `Arrays.hashCode`/`Arrays.equals`. A known profile
  allocates nothing. `size()` removed. `MobClass.matches` added.
- `SearchCall.read` takes the malus buffer. `PathFinderMixin` keeps one static buffer.
- `PoolOffload`: worker catches `Throwable`; `drain` logs a throwing completion and goes on.
- Removed the unused `MaestroServer` accessors and stale comments.
- Schema: `ParityCase.world_frames` (frames applied before the search). The world stream may now
  end with `WorldDelta` frames.
- Capture: area 160 blocks, follow range up to 64, a small visit budget in one case of five,
  three delta frames per file, and non-player entities removed from the area.
- Exactness: a case is also exact when the mob stood in its start block and no sweep read its
  position. A probe hook on `canReachWithoutCollision` detects the sweep. Carpet, snow and low slab
  starts are now exact.
- Parity tests apply frames per case, and assert that exact cases include long paths, cases after a
  delta, and small budgets. Result: 1167 exact cases all match; 30 of 33 approximated cases match.

## Design decisions made without instruction

**`devMode` removed instead of defaulted to false.** With the code out of the jar the flag guards
nothing. Alternative: keep the flag, default false, and gate the dev mod on it. That keeps a switch
that does nothing in production.

**Dev code is a second Fabric mod, not a Gradle-only exclusion.** The mixin and the command need
their own mixin config and entrypoint, which a second mod gives cleanly. Alternative: exclude the
classes from the jar and keep one mod; the main `fabric.mod.json` would then name classes that may
be missing.

**Exactness widened to "same block, no sweep".** The old rule excluded every mob not at the exact
bottom centre. The node's stand-ins differ from vanilla only in the sweep and in the rail check,
and the rail check reads the mob's block, so the wider rule is sound. Alternative: keep the old
rule, which leaves carpet and snow starts unchecked.

**Capture removes entities instead of failing when it finds one.** Entities that spawn with the
chunks would change vanilla's collision answers. Alternative: abort the capture.

**Deltas are mutations of surface blocks between batches.** Alternative: replay a real block edit
trace, which needs a recorder that does not exist.
