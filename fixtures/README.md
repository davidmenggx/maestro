PARITY FIXTURES

`parity/seed-<n>.bin` holds searches that real vanilla ran on the terrain for seed `n`. Each file is a `ParityFixture` (`schema/fixture.fbs`): the world stream a node would receive for the area, then one case per search with the mob's `MobClass`, the `PathRequest`, and vanilla's answer as a `PathResponse`. Every frame is stored exactly as the wire carries it.

A case is exact when the node's stand-in for the mob's position, the bottom centre of the start node, gave the search the same inputs (the mob stood there, or stood in the start block and no sweep read its position), and every collision and floor query vanilla made got the same answer from the node's model of the cells. Block changes made between searches are stored as `WorldDelta` frames after the chunks, and each case says how many frames it follows. The node's `test_parity.cpp` replays every case and requires the same path, `reached` flag and cost for each exact one. The other cases may differ by design and are only counted. The controller's `ParityFixtureTest` checks that every frame keeps the wire contract and that the encoder rebuilds it byte for byte.

Regenerate after a change to the capture, the encoder or the schema:

    make capture SEED="1 2 3 4 5 6 7 8 9 10 11 12" N=100

This boots a dev server on a fresh copy of `worlds/perf` and runs `/maestro capture <seed> <cases>` (only a dev server has it) for each seed. A capture is deterministic: the same seed and case count give the same file.
