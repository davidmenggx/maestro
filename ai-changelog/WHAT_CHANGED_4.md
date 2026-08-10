# Mixin pipeline, dev server, overworld-only policy, config and telemetry

The mod can now hook Minecraft internals and boot. `server/src/main/resources/maestro.mixins.json`
declares a server-side mixin list under `io.github.davidmenggx.maestro.mixin` and is referenced from
`fabric.mod.json`. Four mixins land: `MinecraftServerMixin` logs once at the tail of `loadLevel` to
prove the pipeline applies, and redirects the `Registry.entrySet()` call inside `createLevels` to an
empty set so no dimension but the overworld is ever constructed; `EntityMixin` refuses any
`teleport` whose `TeleportTransition.newLevel()` is not the overworld; `PortalShapeMixin` and
`EnderEyeItemMixin` stop nether and end portals from being built at all. `MaestroConfig` is a Gson
record read from `<config dir>/maestro.json`, written with defaults when absent and rejected loudly
when malformed. `Telemetry` stands up an `OpenTelemetrySdk` with tracer and meter providers over
OTLP/gRPC and is closed on `SERVER_STOPPED`; the OpenTelemetry dependency graph is now Jar-in-Jar'd
by resolving a dedicated `otelBundle` configuration and feeding each artifact to Loom's `include`,
which closes the gap noted in the previous entry. `./gradlew runServer` (also `make run-server`)
writes `run/eula.txt` and a superflat, fixed-seed `server.properties` before launching.
`tools/boot-smoke-test.sh` (`make test-boot`) boots that server, stops it, and asserts the proof
line appeared with no mixin errors; `MaestroConfigTest` covers config parsing and rejection.

## Verified environment facts

Two risks flagged for this step turned out to be non-issues, but only after checking.

Loom 1.18.0-alpha.9 **disables the legacy mixin annotation processor by default**, so no refmap is
generated and none is needed -- 26.1 is unobfuscated. `maestro.mixins.json` therefore omits the
`refmap` key entirely and no `loom.mixin` configuration exists.

The Mixin bundled with fabric-loader 0.18.4 (`sponge-mixin 0.17.0+mixin.0.8.7`, ASM 9.9) does
support `JAVA_25`, which matters because both Minecraft 26.1 and our own classes are class-file
major version 69. At startup Mixin nonetheless warns that Knot "supports JAVA_22". That number is a
hardcoded literal in `MixinServiceKnot.getMaxCompatibilityLevel()`, and 58 of Fabric API's own mixin
configs already request `JAVA_25`, so the warning fires on any 26.1 Fabric server and the
environment ends up at `JAVA_25` regardless. Setting `JAVA_22` was tested and changes nothing except
making our declaration inconsistent with the code we compile.

## Design decisions made without explicit instruction

- **`Entity.teleport` returns `null` when travel is refused.** Vanilla already returns `null` from
  that method when the entity is removed or not in a `ServerLevel`, so `null` is its established
  "the teleport did not happen" contract. Returning `this` was the alternative, but that reads as a
  successful teleport and risks confusing portal cooldown handling.
- **`EnderEyeItem.useOn` returns `PASS`, not `FAIL`.** Only seating an eye in a frame is blocked;
  throwing eyes to locate a stronghold still works. `FAIL` would have suppressed the interaction
  more aggressively than the policy requires.
- **The dimension filter redirects `Registry.entrySet()` rather than cancelling `createLevels`.**
  Cancelling the whole method would also skip the overworld, which `createLevels` builds directly
  before the loop. A datapack was considered and rejected: datapacks can override a dimension entry
  but cannot remove one, so the levels would still exist and tick.
- **Non-overworld guards rely on vanilla's own null handling.** `NetherPortalBlock` and
  `EndPortalBlock` already null-check `getLevel(...)` and return no destination, and
  `DimensionArgument.getDimension` throws a clean command error, so `/execute in the_nether`
  degrades gracefully. No mixin was added for those paths.
- **The proof injection and the dimension filter share `MinecraftServerMixin`.** Loom's
  `ValidateMixinNameTask` expects one `<Target>Mixin` class per target, so splitting them would mean
  two classes competing for the same name.
- **`MaestroConfig.load` catches `RuntimeException` broadly and unwraps the root cause.** Gson's
  record adapter wraps a canonical-constructor rejection in a plain `RuntimeException`; catching
  only `IllegalArgumentException` let an opaque error escape instead of the intended fail-fast
  message naming the file. The unit tests caught this.
- **Exporter log suppression is scoped to the `io.opentelemetry.exporter` JUL logger**, held in a
  static field so the level survives garbage collection. Suppressing all of `io.opentelemetry` would
  have hidden SDK problems unrelated to a missing collector.
- **The dev server sets `max-tick-time=-1`.** Not requested, but the watchdog otherwise kills a
  server paused in a debugger.
