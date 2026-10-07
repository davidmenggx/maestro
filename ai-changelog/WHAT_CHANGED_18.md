# Search selector moved to the node, and the Minecraft assumptions checked against the source

The pathfinder selector is now a node property rather than a per-request field, and every claim
this project makes about Minecraft 26.1 was checked against the decompiled 26.1 jar. One claim in
the code was wrong, one claim in `CLAUDE.md` was wrong, and the rest hold. The unit suites and a
500-seed run of every fuzz tier pass.

## Search selector

`Pathfinder` is off `PathRequest` and on `Hello`, so a node reports which search it runs when it
connects. A fleet is therefore uniform by construction, and `ProbeController.selfCheck` reports a
violation if two greeted nodes disagree, which is the design decision made executable.

## Source verification

Read from the decompiled 26.1 sources that Loom already produced, not from a summary.

Wrong, and fixed. `SyntheticWorkload` said follow ranges run from 16 to about 48 blocks. The
`FOLLOW_RANGE` attribute defaults to 32 as a `RangedAttribute` but `Mob.createMobAttributes` sets
16, and per-mob values run 12 (Evoker, Vindicator, PiglinBrute) to 100 (Ghast), with 64 (Enderman)
the highest for a land mob. `maxPathLength` is `max(FOLLOW_RANGE, requiredPathLength)` where
`requiredPathLength` defaults to 16, so the bound lands between 16 and 64; one caller
(`LongJumpToRandomPos`) passes an explicit 8. The sampler now draws that range, and draws the visit
budget from the follow range rather than from the length bound, because
`updatePathfinderMaxVisitedNodes` only runs on an attribute change or `setRequiredPathLength`, so a
caller passing a shorter bound leaves the budget where it was. `reachRange` is 0 or 1 at nearly
every call site and 6 for the one POI type whose `validRange` is not 1; the sampler draws that set
rather than 0 or 1.

Wrong, and recorded. `CLAUDE.md` said mob profiles are registered once per mob type. The
`WalkNodeEvaluator` inputs are live per-instance state. `Mob.getPathfindingMalus` reads a mutable
per-instance map and delegates to a ridden `Strider`; `FollowMobGoal` and `FollowOwnerGoal` set the
`WATER` malus to 0 on `start` and restore it on `stop`; `Sniffer` flips the `WATER` malus inside
`onPathfindingStart` and `onPathfindingDone`, which `WalkNodeEvaluator.prepare` and `done` call, so
it changes during the pathfind itself; `Creaking.setTransient`, `MoveThroughVillageGoal.canUse`,
`Vindicator.customServerAiStep`, `AbstractPiglin.applyOpenDoorsAbility` and `Zombie
.setCanBreakDoors` change malus or door abilities at runtime; and `getBbWidth` varies with age.
`CLAUDE.md` now says a profile is interned by content and registered before the request that names
it. The boundary change this implies is not made here, because it moves `classId` off
`MobSpawned`.

Confirmed. `checkNeighbourBlocks` iterates dx then dy then dz over -1..1, skips the centre column
with `dx != 0 || dz != 0`, and returns on the first hazard. `getPathTypeStatic` promotes `OPEN`
over the cell below and only reaches `checkNeighbourBlocks` in the default branch.
`getPathTypeFromState` returns exactly the seventeen types `PathTypes.PER_BLOCK_ORDINALS` lists.
`AcquirePoi` is the only caller passing more than one target. `getFloorLevel` reads the collision
shape of the block below.

Confirmed, with the reasoning written down. Collision heights land on a sixteenth grid, so the
`floor` byte is exact rather than a quantisation. `Block.box` divides integer sixteenths by 16, and
every `maxY` argument in the jar is integral. The three `Shapes.box` call sites that are not on the
grid cannot reach `getFloorLevel`: the fluid shape belongs to the fluid rather than the block state,
`PowderSnowBlock`'s 0.9 shape needs a falling entity in the context, and the third is client
rendering. `getFloorLevel` calls the two-argument `getCollisionShape`, which uses the state's baked
shape or `CollisionContext.empty()`, and under that context `LiquidBlock` is empty and
`PowderSnowBlock` takes its default branch. The tallest reachable shape is a fence or wall at 24
sixteenths, which fits the byte.

Found, not yet acted on. `PathNavigationRegion` returns air for a position outside its chunk array
or in an unloaded chunk, so vanilla pathfinds through unloaded chunks as though they were empty.
`WorldSource.pathTypeAt` reads them as `BLOCKED`. Regions are only snapshotted once resident, so
this can only differ in the halo.

Also confirmed: `maxVisitedNodesMultiplier` is only changed by `Bee`, which flies, so it is 1.0 for
every land mob; `GroundPathNavigation.hasValidPathType` has no caller in 26.1, so `Strider`'s
override of it does nothing and `Strider` is in scope for offload; `Frog` and `Creaking` use
`WalkNodeEvaluator` subclasses and `Warden` a `PathFinder` subclass, so all three fall out of scope
on the class check already described.

## Design decisions made without instruction

**`Pathfinder` on `Hello` rather than on `RegionAssignment`.** The node knows which search it was
built with, so having the controller tell it would invert the direction the information flows. The
controller only needs to record it and refuse a mixed fleet.

**Fleet uniformity asserted rather than enforced.** The controller reports a mixed fleet as a
violation instead of refusing the connection. Refusing is a production policy question, and a
violation is what the harness can act on today.
