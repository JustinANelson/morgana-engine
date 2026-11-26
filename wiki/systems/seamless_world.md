---
type: System
title: "Seamless World (worldLayout)"
description: "An option that places maps in one shared world grid, so walking off an edge lands on the map that lies there, at the same place, with no warp or reload."
tags: [system, maps, world, client, server]
code: ["core-domain/src/main/kotlin/com/morgana/domain/map/WorldLayout.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/MovementSystem.kt", "server-app/src/main/kotlin/com/morgana/server/SeamViews.kt", "client-core/src/main/kotlin/com/morgana/client/state/ClientWorldState.kt"]
symbols: ["WorldLayout", "WorldLayoutDefinition", "MapDefinition.worldX", "TileOffset", "Crossing", "MapStateSnapshot.neighborOffsets"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-26T18:00:00Z }
stale_after: 2027-05-25T00:00:00Z
---

# Seamless World

A map is still the unit of content, simulation and hot reload. The option adds **one shared grid of tiles** that maps are placed in, so a tile is "map, x, y" *and* a world tile, and the borders between maps stop being places where the game stops and loads.

## Turning it on
`game.json`:
```json
"worldLayout": { "enabled": true, "neighborMarginTiles": 40, "seamViewTiles": 28 }
```
and each map that takes part names where its top left tile sits (`MapDefinition.worldX` / `worldY`; the map-builder recipe takes the same two fields). Maps without a position, instanced maps, and every game without `worldLayout` behave exactly as before (the per-map `neighbors` links and warps). `softSeams` should stay on: it is what carries the sprites and the camera across.

## What it does
* **Walking across.** Stepping off a placed map looks up the map at that world tile (`WorldLayout.crossing`). The player arrives on it with the same sub-tile pixel position (grid steps land on the tile), in one tick, with no warp and no loss of facing. Maps of any size can meet at any offset, along any edge or at a corner; the world's edge, a blocked far tile or a monster on it stops the step.
* **Links are derived.** On registration each placed map's N/S/E/W `neighbors` are replaced by what the geometry says (the map sharing most of that edge), so quest guides, town gates and the wiki keep working. Hand-written links on placed maps are ignored.
* **The client** gets `MapStateSnapshot.neighborOffsets` (each nearby map's top left from the active map's, in tiles), draws every map within `neighborMarginTiles` at its offset (not only four), shifts sprites and camera by the offset when the active map changes (nothing jumps), and **predicts the step across the border** (`openAcrossBorder`), so the hero does not stop at the edge waiting for the server.
* **Seam views** (`SeamViews.computeInWorld`): monsters, players and loot on the maps around you, within `seamViewTiles` of you in world tiles, are shown across the border, corners included.
* **Validation** (`ContentValidation`): `worldX`/`worldY` go together; overlapping placements are an error; a border that is open on one side and blocked on the other is a warning (`world_border_mismatch`) with the first world tile.

## What it does not do (yet)
Each map is still its own simulation. Monsters stay on their map (they do not chase across a border), a player cannot hit or target what is on the other side until they cross, a party member across a border is seen but is "on another map" for range checks, area-of-interest buckets, chat radius and loot, and a map is loaded for a player only as a whole (a 240 x 240 neighbour is sent in full, but cached). Cross-border combat and streaming a part of a map are the next steps if the world needs them; seams at chokepoints (a gate, a road) keep the gap small.

## Authoring
Place maps so their edges touch, line up the openings (a `frame` op with `gaps` at the same world rows on both maps; `validate` warns when they do not), and leave the rest of each edge blocked. In Trevena the Citadel (240 x 240 at 0,0), the Weald (240,106) and the Marches (288,106) meet this way; the boulevard walks into the Weald and its road on into the Marches.

Tests: `WorldLayoutTest` (crossing, links, overlaps, border mismatch, grid and sub-tile walking, the edge of the world), `SeamViewsTest` (a corner map in view, a far map not), `SoftSeamsClientTest` (offsets, no jump, prediction across the border).
