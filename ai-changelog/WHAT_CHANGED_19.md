# Mob profiles interned by content and named per request

The wire already carried a `class_id` per request, but the mod was going to assign it per mob type,
which the source does not support. The boundary now treats a profile as content that a mob has at
a moment rather than a property of its type. The unit suites and a 500-seed run of every fuzz tier
pass.

## Why

`WalkNodeEvaluator` reads live per-instance state. `Mob.getPathfindingMalus` reads a mutable map
and delegates to a ridden `Strider`; `FollowMobGoal` and `FollowOwnerGoal` zero the `WATER` malus on
`start` and restore it on `stop`; `Sniffer` changes it inside `onPathfindingStart` and
`onPathfindingDone`, which the evaluator itself calls; `MoveThroughVillageGoal`, `Vindicator`,
`AbstractPiglin` and `Zombie` change door abilities at runtime; and `getBbWidth` varies with age.
One profile per mob type would have sent the wrong malus table for any of these.

## Boundary

`classId` moved off `WorldEvent.MobSpawned` and onto `WorldEvent.PathRequested`, so a request names
the profile the mob has at that moment. `WorldSource.mobClasses()` is replaced by
`WorldSource.mobClass(short)`, a lookup that can grow during a run. The controller keeps mobs as a
liveness set rather than a mob-to-class map.

## Registration

A node is sent a profile the first time a request names one it does not hold, and that request is
given up with the new `DropReason.CLASS_UNREGISTERED`. The server falls back to vanilla for it and
the mob asks again a few ticks later.

Giving up the request is what makes the two channels safe to use together. Profiles travel on the
ordered control stream and requests on the unordered one, with no ordering between them, so a
request sent in the same tick as the profile it needs can overtake it. Dropping puts a whole tick
between the two, which no sampled latency can close. The alternative, carrying profiles inside the
request batch, removes the ordering problem entirely but either repeats about 130 bytes in every
batch that uses a profile, which is the hot path, or sends it once and loses it for good when that
datagram drops.

The cost is one path per profile per node per connection. `ProbeController` no longer sends a bulk
table on connect, because a bulk send at greeting time has the same race and the lazy path already
covers it.

## Harness

`SyntheticWorkload` draws a class per request rather than per mob, which is what the live-state
finding implies. Scripted tests gained a `prelude` that loads the world and registers the class,
since a script that goes straight to a request now spends it on registration.
`SimulationTest.everyNodeIsToldAProfileBeforeItIsAskedToUseIt` asserts the node never sees an
unregistered profile and that registrations happened, rather than counting a fixed table.
`WorldBoundaryTest` gained a case for registration costing one request per profile, and the old
"node catches an unknown class" case became "the controller refuses a profile the world cannot
produce", which is the only way that can still happen.

## Two questions answered, no action taken

Unloaded chunks read as air in `PathNavigationRegion` and as `BLOCKED` in `WorldSource`. Both
produce the same result: an unloaded column has no floor, so vanilla finds no walkable node there
either, and `BLOCKED` is not a hazard type so `checkNeighbourBlocks` agrees. The real issue nearby
is that a region is only snapshottable once every one of its chunks is resident, which at the
256-block region size is 256 chunks and will rarely hold on a live server.

`maxVisitedNodesMultiplier` and `Strider` were both confirmations rather than problems. The
multiplier is only changed by `Bee`, which flies, so it is 1.0 for every land mob and folding it
into `max_visited_nodes` is safe. `Strider` passes the evaluator and pathfinder class check and its
only navigation overrides are `hasValidPathType`, which has no caller in 26.1, and
`isStableDestination`, which `findPath` does not use, so offloading it would be correct; it is a
Nether mob, so it does not arise.

## Design decisions made without instruction

**Give up the registering request rather than delay it.** Holding the request for a tick would
answer it, but it adds a queue and a timer to the controller for a case that happens a handful of
times per node. A drop is one branch and the server already has a vanilla fallback for a dropped
path.

**No bulk profile table on connect.** It would save the first drop per profile after a reconnect,
but a bulk send at greeting time races the first request in the same way, so it would reintroduce
the problem the drop exists to remove.

**`CLAUDE.md` corrected on wall climbing.** It said wall-climbing evaluators stay local. There is no
wall-climbing evaluator: `WallClimberNavigation` extends `GroundPathNavigation` without overriding
`createPathFinder`, so a spider searches with a plain `WalkNodeEvaluator` and climbs in `tick`. Its
search is correct to offload.
