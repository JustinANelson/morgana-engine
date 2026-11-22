---
type: Simulation System
title: "Procedural Dungeon Floors"
description: "Seeded rooms-and-corridors floors for roguelike runs: the FloorGenerator recipe on an instanced map, how each party copy is generated, stairs, the boss and exit, validation."
tags: [roguelike, maps, instancing, generation]
code: ["core-domain/src/main/kotlin/com/morgana/domain/map/FloorGenerator.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/InstanceSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/systems/InstancingSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/validation/ContentValidation.kt"]
symbols: ["FloorGenerator", "FloorGeneration", "GeneratedFloor", "WeightedSpawn", "WeightedLoot", "WeightedTile", "openGeneratedFloor", "invalid_floor_generator", "next_floor_not_generated"]
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-13T12:00:00Z }
stale_after: 2027-05-12T00:00:00Z
---

# Procedural Dungeon Floors

Step 2 of the co-op roguelike plan ([ADR 0016](/decisions/0016_map_instancing_and_coop_roguelikes.md), [roadmap](/reference/roadmap_status.md)), built on [instanced maps](/systems/instancing.md). Added 2026-11-13.

## The recipe
An **instanced** map with a `"generator": { ... }` (`MapDefinition.generator`, a `FloorGenerator`) is a **floor template**. Each private copy it opens is generated instead of copied; the template keeps only its name, rules, music and lighting (its own tiles and spawns are ignored, so a 5 x 5 placeholder is enough). Fields, all with defaults:

| Field | Meaning |
| :--- | :--- |
| `width`, `height` | Floor size, 10..256. |
| `algorithm` | Layout style (2026-11-21): `ROOMS_AND_CORRIDORS` (default, exact legacy RNG), `CELLULAR_CAVES`, `BSP_DUNGEON`, or `OPEN_ARENA`. |
| `corridorWidth` | Corridor width in tiles (1..3, default 1; wider corridors prevent co-op body-blocking). |
| `caveFillPercent` | Initial wall density % (25..65, default 45) for `CELLULAR_CAVES`. |
| `roomsMin`, `roomsMax`, `roomSizeMin`, `roomSizeMax` | How many rooms (1..40) and their side length (3..30, walls not included). |
| `floorTile`, `wallTile` | GROUND tiles; wall cells are also `blocked`. |
| `wallFaceTile` | Optional GROUND tile for a wall cell with floor directly below it (the face seen from the front); other wall cells keep `wallTile`. Added 2026-11-14 for Emberdeep (deleted 2026-11-19); draws no random numbers, so seeds give the same layout with or without it. |
| `decorations`, `decorationChanceBp` | Weighted MASK_1 tiles scattered on the floor (passable), chance per tile in basis points. |
| `stairsTile` | MASK_1 tile on the stairs or exit. |
| `monsters`, `monstersPerRoomMin/Max` | Weighted NPC ids; how many in each room except the arrival room. |
| `loot`, `lootMin/Max` | Weighted `itemId` with `minAmount..maxAmount`; how many piles lie on the floor. |
| `features`, `featuresMin`, `featuresMax` | Room features (2026-11-21): weighted NPCs with a dialogue event (a healing shrine, a peddler) placed once each in rooms after the arrival room, `featuresMin..featuresMax` per floor, never respawning. Random numbers are drawn only when `features` is not empty, so older recipes generate the same floors. |
| `itemLevel` | 1..1000 (default 1): the item level gear on this floor rolls at, in a game with `loot` rules. Each piece of gear in a pile lies as its own rolled item ([loot rarities and affixes](/systems/loot_and_affixes.md)). |
| `nextMapId` | The next floor's template (instanced, with a generator). `0` = this is the last floor. |
| `exitMapId`, `exitX`, `exitY` | Where the last floor's exit leads (usually the hub). |
| `bossNpcId` | An NPC placed beside the stairs (or beside the exit on the last floor): a mini-boss or the final boss (0 = none). |
| `fixedSeed` | `0`: a new layout each time. Otherwise always the same layout (tests, a shared "daily" floor). |

Tile placements may animate ([maps and tiles](/domain/maps_and_tiles.md)). The JSON schema is `docs/schemas/maps.schema.json`. The recipe is edited in the F1 **Dungeon** panel ([F1 editor](/client/f1_editor.md)).

## Generation (`FloorGeneration.generate`)
Pure and seeded: its own `SeededRandomSource`, never the world's random numbers, so the same template, instance id and seed always give the same floor.
1. Rooms: random rectangles with at least one wall tile between them and the edge.
2. Corridors: each room joined to the previous one with an L-shaped corridor, so every room is reachable from the first.
3. The party arrives in the centre of the first room; the **stairs** go in the room farthest from it by walking distance (a warp to `nextMapId`, or on the last floor the exit warp to `exitMapId`).
4. Monsters in every other room (spawn slots with `respawnTicks = 0`: they never come back), the boss beside the stairs or exit (any floor, since 2026-11-14).
5. Loot piles on free floor tiles; decorations on the rest.

`GeneratedFloor` carries the map, the entry and stairs tiles, the rooms and the loot.

## In a run (`InstancingSystem.kt` and `InstanceSystem.kt`)
- Opening a copy of a floor template calls `openGeneratedFloor` (in `systems/InstancingSystem.kt`, the instancing system the warp seam routes to): the seed mixes the template id, the instance id and the tick (`FloorGeneration.mix`), the floor is registered as the instance, its loot is laid down as ground items, and the instance remembers its `entryX`/`entryY` and `seed`.
- A player arriving from another map is put at the entry (`warpedPlayer`), whatever tile the warp named; stairs therefore warp to `(0, 0)`.
- The stairs lead to the party's copy of the next floor (opened fresh for them), and the exit back to the hub clears `instanceReturn`. A party's members each take the stairs and meet on the same floor.
- Walking into a floor template while the party's earlier copy of it stands **empty** closes that copy and generates a fresh one (a new run). While anyone is still on it, others join them. A dropped player who logs in again is put back by `positionAfterLogin` as for any instance.
- Death follows the normal rules for now: the player respawns at their class start (the hub). Co-op death rules are step 5 of the plan.

## Validation
`invalid_floor_generator` (sizes and counts out of range, weights below 1, a last floor without an exit), `generator_not_instanced`, `missing_reference` for monster, boss and loot ids, `next_floor_not_generated` / `floor_loops_to_itself` for `nextMapId`, a missing next or exit map as a map-link warning (maps may be saved in any order), and `out_of_bounds` for the exit tile.

## Tests
`FloorGeneratorTest`: same seed same floor, the wall face on walls above floor and nowhere else, fixed seeds, reachability of every room, the stairs, monsters and loot over 60 seeds, walls and the stairs and exit warps, the boss beside the exit, validation, a party descending two floors and returning, monsters staying dead, a fresh floor after leaving, and deterministic runs.
