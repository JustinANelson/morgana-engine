---
type: Concept
title: "Tiled Map Conversion"
description: "Round-tripping maps through Tiled .tmj with full fidelity for attributes, NPC spawns, and event ids."
tags: [content, tiled]
code: ["server-persistence/src/main/kotlin/com/morgana/persistence/TiledMapConverter.kt"]
symbols: ["TiledMapConverter", "exportToTiled", "morganaAttributes", "attributeCode", "morganaNpcSpawns", "simpleAttribute"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Tiled Map Conversion (`TiledMapConverter`)

`exportToTiled(map)` / `importFromTiled(doc)` produce and read standard Tiled JSON: one tile layer per used `MapLayer`, a hidden `COLLISION` layer, and map properties.

## What travels where
| Data | Tiled representation |
| :--- | :--- |
| Ground/mask/fringe/roof tiles | One tile layer each (GIDs from the tileset grid; autotile detail is simplified on import) |
| Tile attributes | `COLLISION` layer numeric **codes** (a visual/collision aid) **and** the `morganaAttributes` string property: a JSON list of `{x, y, attribute}` with full parameters (`_type` discriminator, same as content files) |
| NPC spawns / event ids | `morganaNpcSpawns` / `morganaEventIds` string properties (JSON) |
| Metadata | `morganaMapId`, `morganaMapName`, `morganaMoral`, `morganaRevision`, `bgmTrackId`, `weather`, `ambientLightHex`, `isIndoors`, `neighbor*` |

Codes: `0 None (also item_spawn, npc_avoid, sound_emitter)`, `1 Blocked`, `2 DirectionalBlock`, `3 Warp`, `4 ResourceNode`, `5 ShopTile`, `6 BankTile`, `7 HealTrap`, `8 Slide`, `9 LightSource`, `10 KeyDoor`.

## Import rules
- For each cell, if the `COLLISION` code still equals the code of the attribute stored in `morganaAttributes`, the **stored attribute is kept with all its parameters** (warp targets, item ids, directions).
- If a user changed the cell in Tiled, the new code wins, but only parameter-free codes can be created by hand: `1` -> Blocked, `6` -> BankTile, `0` -> None. Other codes without stored parameters import as None (the importer never invents parameters).
- Maps authored purely in Tiled (no `morganaAttributes`) therefore get blocked tiles and banks; add warps, shops, and the like by editing the `morganaAttributes` JSON text property, or finish the map in the [F1 editor](/client/f1_editor.md) or as a file.
- Old exports with `fade*` property names do not carry metadata ([rename ADR](/decisions/0010_morgana_rename.md)).

Tests: `Phase3TiledAndInMemoryTest`, `HardeningPersistenceTest` (every attribute type round-trips). Imported maps still pass the [content pipeline](/content/content_pipeline.md).
