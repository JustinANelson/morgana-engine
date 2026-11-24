---
type: Simulation System
title: "Population scaling"
description: "How fully a map is supplied by the number of players on it: quiet maps refill only part of their monsters and regrow resource nodes more slowly. Opt-in, deterministic, configured in game.json."
tags: [systems, spawns, resources, performance]
code: ["core-domain/src/main/kotlin/com/morgana/domain/game/PopulationScaling.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/NpcAiSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/InventorySystem.kt", "core-domain/src/test/kotlin/com/morgana/domain/PopulationScalingTest.kt"]
symbols: ["PopulationScalingDefinition", "populationPercent", "slotIsSupplied", "scaledResourceRespawn", "densityPercent"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-24T23:55:00Z }
stale_after: 2027-05-23T00:00:00Z
---

# Population scaling

A busy map should stay supplied; an empty one should cost the server little. `populationScaling` in `game.json` makes a map's **density** depend on the players on it. It is part of the kernel, not an optional system, and without the section every map behaves as fully supplied, exactly as before.

## Configuration
```json
"populationScaling": { "playersForFull": 10, "floorPercent": 25, "scaleMonsters": true, "scaleResources": true, "exemptMapIds": [1], "exemptNpcIds": [40] }
```
**Density** = `floorPercent` with nobody on the map, rising in a straight line to 100 at `playersForFull` players (integer maths: 25 floor and 4 players for full gives 25, 43, 62, 81, 100). Validation points at each bad field (`/populationScaling/floorPercent`...). See [game definition](/domain/game_definition.md).

## What it changes
| What | Rule |
| :--- | :--- |
| Monster respawns | when a dead monster is due, it comes back only if its spawn slot is among the first `density` percent of the map's slots (by slot number, rounded up). The rest wait, and come back when more players arrive. Monsters that are alive are never removed. A monster that is not from a spawn slot, or whose id is in `exemptNpcIds`, is never thinned. |
| Resource nodes | a node gathered out regrows after `respawnTicks * 100 / density`, so an empty map at the 25 floor waits four times as long. |
| Exempt maps | `exemptMapIds` (and instance copies) are always 100. |

It reads only simulation state (the players on the map when the respawn is due or the node is gathered), so it is deterministic and replays agree.

## For plugins
`world.population_percent(map_id)` ([API reference](/../docs/API_REFERENCE.md)) returns the density, so a plugin can scale what the engine does not: a Hunt quest's kill target, a boss arena's adds, a reward.

## Not covered
- Alive monsters are not put to sleep or removed on a map that empties (only respawns are thinned), so the saving comes from fewer monsters over time rather than at once; a plugin can `world.despawn_npcs` a map that has been quiet.
- Density is by map. A large map with a busy corner is one density; sub-map regions are a later step.
