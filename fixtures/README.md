PARITY FIXTURES

`parity/seed-<n>.bin` holds searches that real vanilla ran on the terrain for seed `n`. Each file is a `ParityFixture` (`schema/fixture.fbs`): the world stream a node would receive for the area, then one case per search with the mob's `MobClass`, the `PathRequest`, and vanilla's answer as a `PathResponse`. Every frame is stored exactly as the wire carries it.

A case is exact when the mob stood at the bottom centre of its start node, and every collision and floor query vanilla made got the same answer from the node's model of the cells. The node's `test_parity.cpp` replays every case and requires the same path, `reached` flag and cost for each exact one. The other cases may differ by design and are only counted. The controller's `ParityFixtureTest` checks that every frame keeps the wire contract and that the encoder rebuilds it byte for byte.

Regenerate after a change to the capture, the encoder or the schema:

    make capture SEED="1 2 3 4 5 6 7 8 9 10 11 12" N=100

This boots a dev server on a fresh copy of `worlds/perf` and runs `/maestro capture <seed> <cases>` (dev mode only) for each seed. A capture is deterministic: the same seed and case count give the same file.
