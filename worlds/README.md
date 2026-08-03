# World fixtures

Committed Minecraft worlds used for benchmarking and fuzz testing. Each subdirectory is a complete
world (a `level.dat` plus `dimensions/`), loaded by name:

    make run-server WORLD=<name>

`runServer` copies `worlds/<name>` to `server/run/world-<name>` and points `level-name` at it. The
copy is refreshed on every run, because the server writes back to whichever world it loads and a
fixture is only useful while it stays pristine. Pass `KEEP_WORLD=1` to retain the working copy when
iterating on terrain. With no `WORLD=`, the server generates a plain superflat as before.

## Authoring a fixture

Build the terrain in a client (`./gradlew runClient` provides one without a launcher or account),
then copy the save directory here.

Terrain must keep one solid-to-air transition per column: hills, water, fences, cacti and lava pools
all preserve it, while trees, overhangs and any floor above ground level do not. Cacti and lava
next to walkable ground are worth including deliberately -- they exercise the neighbor-dependent
`PathType` rules, and hazards close enough to share a cell's scan box exercise the order-dependent
first-hazard-wins case.

## Gamerules

Set these before copying the world out. 26.1 stores them in `data/minecraft/game_rules.dat`, not in
`level.dat`, and they travel with the directory, so every later run inherits them. Note that 26.1
renamed every rule to a namespaced snake_case id; the pre-26.1 names are gone.

These two decide whether a benchmark measures anything at all:

    gamerule spawn_mobs false          # natural spawning adds uncontrolled pathfinding load
    gamerule random_tick_speed 0       # freezes grass spread, cactus growth, ice melt

`createLevels` wires PhantomSpawner, PatrolSpawner, CatSpawner, VillageSiege and
WanderingTraderSpawner into the overworld. They run on their own rules, so disable them separately:

    gamerule spawn_monsters false
    gamerule spawn_phantoms false
    gamerule spawn_patrols false
    gamerule spawn_wandering_traders false
    gamerule raids false

Removing time-varying inputs, and anything that can still rewrite terrain:

    gamerule advance_time false
    gamerule advance_weather false
    gamerule mob_griefing false
    gamerule fire_spread_radius_around_player 0
    gamerule water_source_conversion false
    gamerule lava_source_conversion false
    gamerule spread_vines false
    gamerule projectiles_can_break_blocks false
    time set day
    weather clear

Run `setworldspawn` at the center of the built area so the server's always-loaded spawn chunks are
the ones under test.

## Pruning

Delete `session.lock`, `level.dat_old`, `players/`, and -- if a vanilla client created them --
`dimensions/minecraft/the_nether/` and `dimensions/minecraft/the_end/`. Keep everything under
`data/`: it holds the gamerules and the generator settings.

Only the chunks visited while building exist. Anything outside is generated on demand as plain
superflat from the settings in `level.dat`.
