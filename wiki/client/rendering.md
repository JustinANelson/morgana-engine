---
type: Concept
title: "World Rendering"
description: "How MorganaWorldRenderer draws maps, neighbors, entities, lighting, and overlays."
tags: [client, rendering]
code: ["client-core/src/main/kotlin/com/morgana/client/render/MorganaWorldRenderer.kt", "client-core/src/main/kotlin/com/morgana/client/render/PaperdollCompositeCache.kt"]
symbols: ["MorganaWorldRenderer", "renderMapLayers", "fadeRoof", "aboveEntities"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-08T18:55:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# World Rendering (`MorganaWorldRenderer`)

- Draw order: `GROUND`, `MASK_1`, `MASK_2`, animations with `renderAboveEntities = false`, then Y-sorted entities (players, NPCs, ground items, projectiles, resource nodes) and animations with `renderAboveEntities = true` (sliced and frame-timed via `AnimationStripLayout`), then `FRINGE_1`, `FRINGE_2`, `ROOF` ([layers](/domain/maps_and_tiles.md)). The roof draws at alpha 0.25 when the local player stands under it (`fadeRoof`).
- **Animated tiles**: every layer's placements go through `TileAnimation.sourceAt(placement, animationClockMs())` (the renderer's own clock), so a tile with `animationFrames` above 1 cycles through the tiles to its right; the editor's hover preview of the brush animates the same way. The built-in water tile (`ANIMATED_3_FRAME`) keeps its fixed three frames. See [maps and tiles](/domain/maps_and_tiles.md).
- **Neighbor stitching**: adjacent N/S/E/W maps are drawn at pixel offsets (`+/-width*tileSize`, `+/-height*tileSize`) for seamless borders.
- **Soft seams** (`renderSeamEntities`): the NPCs, players and ground items just across a shared border are drawn too (slightly muted), so the edge of a town shows what lies beyond. The server computes them per player ([`SeamViews`](/protocol/server_packets.md), packet `s_seam_view`) and the client keeps their smoothed positions already offset into the active map's pixel space (`ClientWorldState.seamNpcVisuals` / `seamPlayerVisuals`, `seamOffset`). They are visible only: attacking, chatting and events still belong to each entity's own map.
- **Seamless handover**: when the player walks into the neighbouring map, `ClientWorldState.crossingShift` moves every on-screen sprite (and the camera target) into the new map's coordinates, so nothing jumps; NPCs seen across the border are promoted to normal entities and the old map's NPCs become seam entities. A warp (non-neighbour) snaps instead.
- **Jump leaps**: a same-map position change over 3 tiles (a jump, up to 20 tiles) is eased over ~0.2-0.4 s (`EntityVisualSmoothing.leap*`) instead of snapped; warps and map handovers still snap. The sprite also hops on a sine arc (`hopHeight`, 10-28 px, passed to `SpritePlacement.place` as `lift` so name tag and bars rise with it); the camera stays on the ground.
- **Ground shadows**: every character, NPC, and monster draws a soft elliptical ground shadow (`drawGroundShadow`) at its feet (scaling and fading when jumping over `hopHeight`).
- **Eased atmosphere**: ambient darkness and weather ease between maps over about a second (`shownDarkness`, `shownWeather`/`weatherAlpha`; darkness and fog cover the neighbour maps too), and music crossfades ([assets and audio](/client/assets_and_audio.md)).
- Entities use smooth pixel interpolation, 4-direction walk frames, layered paperdolls (or opt-in Paperdolling v2 via `PaperdollComposer` + `PaperdollCompositeCache` for direction-sorted, tinted, single-draw composited equipment & body/hair layers with hide rules; draw order is per direction: back items behind the body, and facing left or right the far hand behind it; **top-rarity glow**: worn gear of the game's highest rarity (`LootRules.topRarityId`, the last `loot.rarities` tier, none when there are fewer than two) is flagged `PaperdollLayer.glow` by the server and drawn with a 1 pixel yellow outline, `PaperdollCompositeCache.paintGlowOutline`: the four-neighbour ring around that layer's own opaque pixels, painted over the layers below it and under the item, so it shows on the body as well as on empty ground; only the composited path draws it), overhead nameplates (colored by [access level](/domain/access_levels.md)), HP bars and nameplates (NPC/monster HP bars and non-service monster nameplates only show when damaged, a boss or a rare spawn variant, with red-filled tracks for monsters/passive mobs; rare variants are also tinted toward `EnemyVariantDefinition.colorHex` and stand in a pulsing coloured ground glow, `drawVariantAura`), chat bubbles, and rising floating text.
- Dynamic 2D lighting (radial light texture, `light_source` attributes) and weather particles (rain, snow, fog, ash). Weather comes from `ClientWorldState.weatherNow(map)` (the map's weather zone and the estimated server tick, or the map's own fixed weather in games with no zones; interiors are clear), and its strength scales the particle count and fog density ([game definition](/domain/game_definition.md)).
- Editor overlays: hover preview, attribute badges, NPC spawn markers, and event markers (`E#<id>`, highlighting `ClientWorldState.editingEventDraft` at its live draft position) while the F1 map editor is active ([F1 editor](/client/f1_editor.md)).
- Coordinate helpers convert between tile/screen/world space (note the flipped Y).

Art comes from [procedural assets](/client/assets_and_audio.md).


Tile passes draw only the tiles inside the camera view (`VisibleTiles`, one tile of margin), not the whole map, so large maps cost the same per frame as small ones.

**Spell shots (2026-11-17)**: a ranged spell cast by anyone on the map (the animation packet names `casterEntityId` and `spellId`; the spell names a `projectileSpriteId`) spawns a `SpellShot` per impact animation, flying from the caster's body height to the target tile in 0.14-0.5 s on a slight bow; the impact animation starts when it lands (`ActiveSpriteAnimation.elapsedSeconds` starts negative and is skipped while negative). The server still resolves hits instantly; shots are client-only.

**Seam alignment (2026-11-17)**: east/west neighbours are offset vertically by `(activeHeight - neighbourHeight) * tileSize` (`sideNeighborOffsetY`) so tile row N meets tile row N; without it maps of different heights shared a bottom edge and the border's openings looked blocked by the neighbour's tree band.
