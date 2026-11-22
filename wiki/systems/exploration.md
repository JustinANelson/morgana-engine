---
type: Simulation System
title: "Exploration: Fog of War and the World Map"
description: "Per-character explored chunks, fog on the minimap, the World Map window and its layout: Exploration, WorldMapLayout, the exploration module and packets."
tags: [exploration, map, minimap]
code: ["core-domain/src/main/kotlin/com/morgana/domain/explore/Exploration.kt", "core-domain/src/main/kotlin/com/morgana/domain/explore/WorldMapTerrain.kt", "client-core/src/main/kotlin/com/morgana/client/ui/WorldMapCard.kt", "client-core/src/main/kotlin/com/morgana/client/ui/WorldMapMarkers.kt", "core-domain/src/main/kotlin/com/morgana/domain/explore/WorldMapLayout.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "client-core/src/main/kotlin/com/morgana/client/ui/PlayerHudAndWindows.kt"]
symbols: ["WorldMapTerrain", "WorldMapMarkers", "WorldMapDetailEntry", "sendWorldMapDetail", "Exploration", "ExploredMap", "ExplorationDocument", "WorldMapLayout", "MapNode", "updateExploration", "announceExplorationOnLogin", "ExploredMapInfo", "WorldMapCard", "toggleWorldMapWindow", "MinimapCanvas"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-14T23:00:00Z }
stale_after: 2027-05-13T00:00:00Z
---

# Exploration: Fog of War and the World Map

The `exploration` [optional module](/server/optional_modules.md) (on by default). Walking reveals the map; the minimap dims what a character has not seen and a **World Map** window shows every area they have found.

**Rules** (`Exploration`, pure, `ExplorationTest`): a map is split into 4 x 4-tile **chunks** (`CHUNK`); a character sees every chunk with a tile within 7 tiles (`VISION_RADIUS`) of them. An `ExploredMap` is one bit per chunk packed into `Long` words (a 50 x 50 map is 169 chunks, three words). `reveal` returns the new set, or null when nothing was added, so unchanged ground is neither saved nor sent. What was seen stays seen.

**Storage**: an `ExplorationDocument` (id is the character id, a map id to `ExploredMap` map) in `social/exploration` (JSON file or SQLite like the other player data; included in the storage migration; deleted with the character). It is kept apart from `CharacterState`, so walking never changes the character document, its sync packets or the replay state hash.

**Server** (`MorganaWorldCoordinator`, `ExplorationServerTest`): `announceExplorationOnLogin` loads the document and sends `s_explored` (every explored map that still exists, as `ExploredMapInfo`: name, size, the ids of the four neighbouring maps and the explored chunks). Every 10 ticks `updateExploration` reveals the ground around each online character and, for a map that changed, saves the document and sends `s_explored_map`. Instanced map copies are not tracked (they are private and temporary). Neighbours are sent as ids only, so a map is named on the world map only once it has been explored.

**Client**: `ClientWorldState.explored`. `MinimapCanvas` draws unexplored chunks as fog and hides NPC and player blips inside them (until the first update arrives it shows everything, so it never flashes dark). The **World Map** window (`N`, or Menu > World map) lays the explored maps out by their border links (`WorldMapLayout`: a road running east across three maps is three boxes in a row; maps joined only by warps form their own groups side by side) with a dark "?" box for each neighbour not yet explored. Each `WorldMapCard` shows the map's name, explored percentage and level range, then **the map itself in miniature** (water, walls, warps and open ground, classified from the real tiles exactly as the minimap does), a gold border and a pulsing blip for the current map.

**Terrain and enemies** (added 2026-11-17): the client only holds the maps it has visited, so opening the window sends `c_world_map` and the server answers `s_world_map` with, for each *explored* map, a `WorldMapDetailEntry`: a thumbnail (`WorldMapTerrain.thumbnail`, pure, `WorldMapTerrainTest`: one character per block of tiles, at most 64 a side, so a 140 x 140 map is about 4,900 characters) and the map's NPC spawn points. The client knows what each NPC is from its catalog. `WorldMapMarkers` (pure, `WorldMapMarkersTest`) keeps the spawns of creatures fought for experience (passive or attack-on-sight with an XP reward; shopkeepers and harmless animals get no marker), groups one creature's spawns within a 12-tile cell into one marker (`Wolf Lv.12 x4`), and colours it by danger against your level (green, white, red; the near-black tier is shown grey so it stays visible). Markers are labelled `Lv.N` (a label that would cover a stronger creature's is skipped), bosses are larger and gold, and hovering a marker names the creature. Ground you have not seen is dark and shows no enemies. Until the answer arrives the card falls back to the explored-chunk blocks. With the module off the server answers with an empty list.

**Not built yet**: warp links between maps, quest markers on the world map, a per-realm vision radius in `game.json`, and shared exploration for parties.
