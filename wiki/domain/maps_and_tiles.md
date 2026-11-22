---
type: Concept
title: "Maps, Layers, Tile Attributes, and Morals"
description: "The map data model: size limits, six layers, neighbor links, tile attribute types, and map rulesets."
tags: [maps, tiles]
code: ["core-domain/src/main/kotlin/com/morgana/domain/map/MapModels.kt", "core-domain/src/main/kotlin/com/morgana/domain/map/TileAnimation.kt", "core-domain/src/main/kotlin/com/morgana/domain/spatial/SpatialTypes.kt"]
symbols: ["MapDefinition", "TileAnimation", "animationFrameStep", "TileAttribute", "MapMoral", "MapLayer", "MapNeighborLinks", "OPEN_PVP", "safeRespawnInPlace"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Maps, Layers, Tile Attributes, and Morals

## MapDefinition
- Width/height must be 5..256; `cells.size == width*height` (enforced in `init`). Default tile size 32 px. Fields include `revision`, `moral`, `weather`, `ambientLightHex`, `bgmTrackId`, `neighbors`, `npcSpawns`, `eventIds`.
- On the wire a map is sent as a palette plus run-length indices ([wire format](/protocol/wire_format.md)); in memory and on disk it is the plain cell list.
- `revision` is bumped on every live edit or hot reload so clients can refresh.
- `neighbors` holds N/S/E/W map ids; `0` means none. Linked maps should be reciprocal (the validator warns otherwise).

## Layers (render order)
`GROUND(0)`, `MASK_1(1)`, `MASK_2(2)` render below entities; `FRINGE_1(3)`, `FRINGE_2(4)`, `ROOF(5)` render above. The roof fades when the local player is underneath ([rendering](/client/rendering.md)).

## Generated floors
An instanced map with a `generator` recipe is a template for procedurally generated dungeon floors; see [procedural floors](/systems/procedural_floors.md).

## Animated tiles (`TileAnimation`)
Any tile on any layer can animate (added 2026-11-13, owner feedback). A `TilePlacement` with `animationFrames` above 1 cycles through that many frames, each shown for `animationSpeedMs`; the frames are tiles of the same sheet row starting at `srcTileX` and going right, `animationFrameStep` tiles apart (1 for a single tile, the object's width for a multi-tile one). The frame depends only on the placement and the elapsed time (`TileAnimation.frameAt`, `sourceAt`), so it is deterministic and every client shows the same frame. Validation (`invalid_tile_animation`, pointer `/cells/<i>/layers/<LAYER>/<field>`): frames 1..32, speed 16..60000 ms for an animated tile, step 1..64. The legacy `AutotileMode.ANIMATED_3_FRAME` water tile of the built-in tileset keeps its own fixed frames. Authoring in JSON: set the three fields on a placement; in the editor: the Tileset editor's **Anim** controls ([F1 editor](/client/f1_editor.md)). Tile attributes do not animate on their own; the `heal_trap` attribute's `animationId` plays an [animation](/client/rendering.md) when it fires.

## Tile attributes (`TileAttribute`, JSON `_type`)
`none`, `blocked`, `dir_block`, `warp`, `item_spawn`, `npc_avoid`, `key_door`, `resource_node`, `shop`, `bank`, `heal_trap`, `slide`, `light_source`, `sound_emitter`.
Movement semantics: [movement](/systems/movement.md). To add one: [playbook](/playbooks/add_tile_attribute.md).

## Morals (`MapMoral`)
| Moral | PvP | Drop items on death | Notes |
| :--- | :--- | :--- | :--- |
| SAFE_ZONE | no | no | |
| OPEN_PVP | yes | yes | non-bound items drop and despawn after 2400 ticks |
| ARENA | yes | no | respawn in place |
| INSTANCED_DUNGEON | no | no | |

`Direction` has 8 values (4 cardinal + 4 diagonal). `MovementMode` is `GRID_LOCKED` or `SUB_TILE`.
