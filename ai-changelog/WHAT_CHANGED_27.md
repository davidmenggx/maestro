# Section-based chunk read

`Frames.readChunk` no longer reads every block of a chunk one by one. `WorldSource` has a new
`uniformCell(chunkX, chunkZ, section)` that returns the one cell of a 16-block section, or
`NOT_UNIFORM`. A uniform section becomes a 16-block run without any block reads. The default knows
nothing, so every other source still reads block by block. The wire bytes do not change.

## Changes

- `LevelWorldSource.uniformCell` reads the chunk section's palette. A section with only air, or one
  block state, is uniform. A chunk that is not loaded reads as air. The cell of a single state is
  read at one block.
- `Frames.readChunk` asks for the uniform cell of each section first, then reads only the others.
- `FramesTest` checks that a source with and without the shortcut gives the same bytes, and that a
  flat chunk costs 16 of 384 layers of reads.
- Capture checks every chunk both ways and fails if they differ. Re-capturing seeds 1, 3 and 6 gave
  byte-identical fixtures.

## Design decisions made without instruction

**The section is the unit, not a run.** Alternative: a method returning the length of the uniform
run from a position. It is more general but needs the source to know run boundaries across
sections, which the palette does not give.

**The default is `NOT_UNIFORM`, not an exception.** Sources that cannot tell cheaply, such as the
simulator's, keep working unchanged.
