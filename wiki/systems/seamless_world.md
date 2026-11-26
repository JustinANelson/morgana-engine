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

## The border is not a wall
Nothing in the simulation treats a map edge as a boundary where two maps abut (`sim/CrossBorder.kt`):
* **Bodies.** Players, dashes, dodges, bashes and leaps (action-combat `moves`) cross freely; a player carried beyond the edge by a skill is handed to the map that lies there at the end of the tick (`settleBorderCrossings`, aim points and leap targets move with them). Monsters stay *owned* by their map (spawns, respawn and saving are per map) but walk across: their position may lie beyond their map's edge, bounded by their leash, and every walkability, occupancy and crowding check looks through the border (`cellThrough`, `walkableThrough`, `bodyAtTileThrough`, ghosts of players).
* **Sight.** Players, monsters, damage text and skill effects within the seam view distance on the maps around you are streamed to you in real time (`s_player_pos`/`s_npc_pos` carry the map id of what is across), not refreshed a few times a second.
* **Data.** Only the world's outer edge needs a wall. Along a shared border open the whole span (a `frame` gap for the full side, the corner tiles painted open, nothing reserved against scatter on the strip); `validate` warns on any tile open on one side and blocked on the other.

Each map is still its own simulation, so a fight that spans a border *translates* the far side into this side's coordinates for the moment of a blow and never moves anyone (`sim/CrossBorder.kt`):
* **Skills and arrows reach over it.** The action-combat hit scan takes the hostile monsters of the maps within reach (`hostileNpcsInReach`) at where they stand in the attacker's coordinates; damage, knockback, pulls and chain lightning land on the monster on its own map. Free-flight arrows keep flying past the edge (their position stays in the shooter's coordinates), hit monsters on the other side and stop at a wall or the edge of the world. A blocked tile on the border (a frame wall, a prop) still stops an arrow, so keep the shared span open.
* **Monsters notice and strike across it.** A monster looks at the players of the maps around it within 12 tiles of its edge as *ghosts*: the same character with the position shifted into the monster's coordinates (`ghostOf`). Aggro range, chasing (to the edge of its own map: it never leaves it), melee, telegraphed slams and lunges, and spells then work unchanged. Anything written back is corrected at once (`restoreBorderGhosts`, `fixBorderGhostEvents`), so a player's real position and the updates sent to their client never carry the shifted one.
* **The client sees it.** A monster across a border that changes (`s_npc_sync`) or moves (`s_npc_pos` with its map id) is sent to the players within the seam view distance of it, and damage text and skill effects (telegraph lanes, bolts) from the other map are drawn at that map's offset. Monsters across a border can be auto-targeted, cycled and locked, and the target frame shows them.
Not covered: the older tile-based spell combat (`CombatSystem` spells), the PvP/party rules across a border, loot and projectiles of a neighbouring map shown to a viewer, and a monster's death handling relative to the *player's* map (a player who dies is handled on the map they stand on).

## What it does not do (yet)
Monsters stay on their own map (they chase to its edge but never cross), a party member across a border is seen but is "on another map" for range checks, area-of-interest buckets, chat radius and loot, and a map is loaded for a player only as a whole (a 240 x 240 neighbour is sent in full, but cached). Streaming part of a map is the next step if the world needs it; seams at chokepoints (a gate, a road) keep the gap small.

## Authoring
Place maps so their edges touch, line up the openings (a `frame` op with `gaps` at the same world rows on both maps; `validate` warns when they do not), and leave the rest of each edge blocked. In Trevena the Citadel (240 x 240 at 0,0), the Weald (240,106) and the Marches (288,106) meet this way; the boulevard walks into the Weald and its road on into the Marches.

Tests: `CrossBorderCombatTest` (skills, arrows, monsters across a border; nobody is moved), `WorldLayoutTest` (crossing, links, overlaps, border mismatch, grid and sub-tile walking, the edge of the world), `SeamViewsTest` (a corner map in view, a far map not), `SoftSeamsClientTest` (offsets, no jump, prediction across the border).
