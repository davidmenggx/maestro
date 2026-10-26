# Spiders offloaded, wider test coverage, and a coverage target

A spider chasing an entity searched on the server, because `WallClimberNavigation` overrides
`moveTo(Entity, double)` and the mixin on `PathNavigation` cannot wrap an override. A new mixin
wraps it, so spider searches are offloaded as CLAUDE.md says. The rest of the change is tests. The
code was measured with JaCoCo and llvm-cov, and tests were added where real behavior had none: the
gate that decides which searches are offloaded (`SearchCall.read`), shadow-mode comparisons, the
failover queue, a region cut while its chunks are still being read, stray frames on the
controller, the node's command line, Java's numeric rules on the node, and several search and
world-store edge cases. The coverage sweep now asserts that it reaches replica reclaims and
replicas of regions of several cells. `make coverage` writes HTML reports for all three code bases,
and a CI job uploads them.

## Changes

- **Spiders.** New `mixin/WallClimberNavigationMixin` wraps `WallClimberNavigation.moveTo(Entity,
  double)` with `Searches.enter`/`leave`, like the `PathNavigation` overloads. With no path the
  spider still heads straight for its target, as in vanilla.
- **`SearchCall.read` takes `minY`.** `PathFinderMixin` passes `mob.level().getMinY()`, so `read`
  no longer reads the level and can be tested with a mob built without one. No behavior change.
- **Node CLI.** `parseArgs`, `printUsage`, `Options` and `defaultWorkers` moved from `main.cpp` to
  `maestro/cli.hpp` and `src/cli.cpp` (namespace `maestro::cli`). Same flags, defaults, ranges and
  exit codes.
- **Coverage target.** `make coverage` runs the unit suites under JaCoCo through
  `tools/jacoco.init.gradle` (a `coverageReport` task per Gradle project), then builds the node
  with clang source-based coverage in `build/node-coverage` and runs its tests. Reports go to
  `build/coverage/java/{maestro,controller}` and `build/coverage/node`, and a summary is printed.
  `COVERAGE_CXX` picks the compiler. `make clean` removes both directories. CI has a new
  `coverage` job that runs it and uploads `build/coverage`. No thresholds.
- **Tests, mod (`mixinTest`).**
  - New `OffloadableSearchesTest`: each searching `moveTo` marks its search offloadable, the
    spider's included; a caller that only reads a path does not; the mark ends if the search
    throws; a path handed to the navigation reaches the interceptor. Without the new mixin, only
    the spider test fails.
  - `PathOffloadTest`: shadow mode asks once for a repeated call, compares reach and every node,
    matches "no path" only with "no path", counts mismatches past those it logs, and compares
    nothing for a refused call; answers and drops for a mob with no request are ignored; the
    summary format. Failover: a held or finished path is not kept as recent, a cheap search that
    finds nothing leaves no wait, a full search that finds nothing stops the navigation, a mob that
    left loses its turn, and a mob that asks again keeps its place in the queue (checked by
    breaking that rule, which fails only this test).
- **Tests, mod (plain).** `SearchCallTest` reads a zombie's call (shape, abilities, malus, start,
  target copy, search inputs, hunting), shares a profile between equal mobs, and returns null for
  inputs the wire cannot carry, a shape past the bound, nodes that may share vanilla's hash (which
  depends on `minY`), and exhausted profile ids. `OtelMetricsTest` covers counters and odd labels.
  `NettyTransportTest` covers a listener that throws.
- **Tests, controller.** `PlacementTest`: a region cut while its chunks are still being read
  carries the unread ones to the kept half (dropping that carry fails only this test).
  `WorldBoundaryTest`: frames and link events from a node outside the fleet, and frames of no known
  kind, are counted and ignored; a frame on the world channel is a self-check finding.
  `FuzzTest` coverage tier: asserts `region.replica.reclaimed > 0` and replicas of several cells.
- **Tests, node.** `test_cli.cpp` (9 tests) and `test_java_math.cpp` (3 tests: `toInt`
  saturation and NaN, `floor`/`ceil`, `Math.max` with NaN and signed zero). In existing files: a
  path length of 0 never expands the start; a sweep of no length; a sweep only collides with
  blocks it overlaps; sinking through water stops above the world floor; a costly type above a
  small mob; requests without positions; messages of an unknown type; more malformed world
  frames. `maestro_tests` gets `src` on its include path for `java_math.hpp`.

## Design decisions made without instruction

**The spider fix is its own mixin.** A mixin on `PathNavigation` only changes that class's method
bodies, so the override needs a mixin on `WallClimberNavigation`. A spider whose request is shed,
or whose answer is "no path", heads straight for its target instead of idling, since that is what
its navigation does with a null path.
- Alternative: hook `WallClimberNavigation.createPath`. That also catches callers that only read
  the path, which must stay vanilla.

**Navigation tests use mobs with no level.** A unit test cannot build a level, so the tests
subclass the navigation and replace its search with one that records `Searches.offloadable()`.
This checks that the wraps apply and are balanced, not the search itself, which the e2e run
covers.

**Two `SearchCall` tests use a mob subclass.** With a target, vanilla's `getMaxFallDistance`
reads the level's difficulty, and setting the scale attribute reads the level too. The hunting
test overrides `getTarget` and `getMaxFallDistance`, and the shape test overrides `maxUpStep`. The
footprint bound itself is covered by the existing `MobClass.Shape.fits` tests.

**Replica reclaims are asserted, not boosted.** The sweep already reaches them (7 in the 2000
coverage seeds). No sampler change was needed.
- Alternative: bias the sampler toward replicas and holder deaths. More reclaims per seed, but it
  changes what every other assertion sees.

**The cut-during-reads test uses 16 players.** The sampler draws at most 3, and their views are
read before a region can turn hot.

**JaCoCo runs from an init script, not the build files.** Normal test and fuzz runs stay
uninstrumented, and both Gradle projects share one definition. Mixin classes are excluded
(their code runs merged into the game's classes, where JaCoCo cannot attribute it), as are the
generated FlatBuffers classes. Only the unit suites are measured.
- Alternative: apply the `jacoco` plugin in both `build.gradle` files behind a property.

**llvm tools come from the coverage compiler's directory.** `llvm-profdata` must match the clang
that wrote the profiles, and unversioned names are not always on `PATH`.

**The "area origin" item from WHAT_CHANGED_38 needs no change.** CLAUDE.md already keeps a search
whose nodes may share vanilla's lossy hash on the server. Sending the origin would make those
searches exact, but changes the wire and the node's node table.

## Not done

- `Fleet`, `MaestroServer`, `Telemetry`, the `PathFinderMixin` decision and
  `Searches.searchAgain` need a live level, so they stay covered by `make test-e2e` only.
- `WorldStreams` chunk reads that fail or return nothing: the simulator's world never fails a
  read.
- Node: the error paths of `net/server.cpp` need fault injection, and an answer too large for a
  datagram needs a path of about 8k nodes.

## Verification

- Unit suites (`make test`): node 227 tests (was 207), controller 270 (was 266), mod 59 (was 49),
  `mixinTest` 66 (was 47). All pass. The node suite also passes under clang with ASan and UBSan,
  and under g++.
- Fuzz coverage tier, 2000 seeds: passes with the new assertions (7 replicas reclaimed, 168
  replicas of several cells). No controller code changed, so the other tiers were not rerun.
- Boot smoke test passes with the new mixin listed.
- Mutation checks: removing the new mixin fails only the spider test; dropping the kept half's
  unread chunks on a cut fails only the new `PlacementTest` case; forgetting when a failover mob
  first asked fails only the queue-order test.
- Coverage (`make coverage`), before and after:

  | | Lines | Branches |
  |---|---|---|
  | Node | 93.2% to 98.1% | 87.7% to 93.5% |
  | Controller, unit suite | 96.6% to 97.4% | 89.9% to 91.1% |
  | Mod, mixins excluded | 67.3% to 72.6% | 66.5% to 75.2% |

  Most of the mod's remaining gap is `Fleet`, `MaestroServer` and `Telemetry` (253 lines), which
  need a live level.
