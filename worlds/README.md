WORLD PRESETS

Sample worlds for testing purposes (fuzz and performance).

New worlds must have only one "vertical band" (cannot have floating floors or overhangs, use superflat with structures disabled) and need the following gamerules:

    gamerule spawn_mobs false
    gamerule random_tick_speed 0
    gamerule spawn_monsters false
    gamerule spawn_phantoms false
    gamerule spawn_patrols false
    gamerule spawn_wandering_traders false
    gamerule raids false
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

New worlds should delete the files/folders `session.lock`, `level.dat_old`, `players/`, `dimensions/minecraft/the_nether/`, and `dimensions/minecraft/the_end/`.
