---
type: Module
title: "Module: map-builder"
description: "Tooling, not engine: compiles map recipes (JSON terrain, paths, walls, props, warps and enemy camps) into map documents using the tile index a tileset export wrote."
tags: [module, tooling, maps, content]
code: ["map-builder/src/main/kotlin/com/morgana/mapbuilder", "map-builder/build.gradle.kts"]
symbols: ["MapRecipe", "MapCompiler", "TileIndex", "MapBuilderCli", "RecipeException"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-22T18:00:00Z }
stale_after: 2027-05-21T00:00:00Z
---

# Module: `:map-builder`

A recipe is one way to make a map; the `F1` editors and hand-written JSON are the others, and all of them go through the same validation. `:map-builder` depends on `:core-domain`, `:server-persistence` and `:tilegen`; no engine module depends on it.

## Recipe format
A recipe (`MapRecipe`) names the map (`id`, size up to 256 x 256, `moral`, `bgm`, `neighbors`, `seed`, and `instanced` with `instanceOwner` `PARTY` or `GUILD` for a dungeon, raid or guild hall), a `base` terrain, the `terrains` and an ordered list of `ops` (`"op": "..."`):

| Op | Does |
| :--- | :--- |
| `rect`, `ellipse`, `path`, `blob`, `frame` | Paint a terrain: rectangle, ellipse, thick line through points, noisy organic blob, or an edge band with `gaps` (how a seam stays open at the same place on both maps). |
| `wall` | A block from a 9-tile wall set (`<set>_void_*`, `_top_*`, `_bot_*`): flat top rows, then cornice and plinth facing south; blocked except `passableCols`. |
| `prop` | A multi-tile prop from the index: bottom row blocked on `MASK_1`, upper rows on `FRINGE_1` (drawn over characters); `passableCols` leaves a gate open, `walkable` makes it decoration. |
| `scatter` | Props over cells of a terrain with a `density`, keeping a `gap` and staying off blocked cells and other props. |
| `reserve` | Keeps a rectangle clear of later scatter and camps (a road mouth at a seam, a landing); paints nothing. |
| `noise` | Paints bands of a seeded fractal noise field (`scale`, `octaves`, `warp`, `smooth`, `edge`/`edgeBias`): hills, lakes, glades with natural outlines. `onlyOn`, `minArea` (undo specks), `store` (keep the field). |
| `terrace` | Plateaus where a stored field passes `thresholds`: plateau terrain, two-row south cliff faces from a wall set, walkable ramps, sliver levelling. |
| `tile`, `warp`, `spawn` | One tile on a layer, a warp area, one NPC spawn. |
| `camps` | Enemy camps on open cells of chosen terrains: `sizeMin`..`sizeMax` per camp, `spacing` apart, count from `camps` or from one enemy per `tilesPerEnemy` tiles. |

A recipe may name an `anchor` tile; enemy camp spawns that cannot be walked to from it are dropped (an island, a pocket behind a lake), while a service NPC that cannot be reached is an error. A `terrain` names a tile from the index (variants `name_v1...` are picked per cell by a position hash), whether it blocks, and optionally an `autotile` that rounds its edges by an 8-neighbour mask against terrains it does not `join`, with `animatedFrames` for the fully surrounded cells (water).

`path` also takes `meander` (tiles of sideways wander) and `wavelength`, so roads and rivers bend. `MapCompiler.build` returns the map plus a `MapAnalysis` (terrain, blocked and occupied rows, spawns), which the CLI writes with `--analysis <folder>`; see [AI-assisted mapping](/playbooks/ai_assisted_mapping.md) for the loop that reads it.

A recipe may also name `worldX`/`worldY`, its place in a seamless world ([seamless world](/systems/seamless_world.md)); the compiler copies them to the map.

## Running it
`.\gradlew.bat :map-builder:installDist`, then run `com.morgana.mapbuilder.MapBuilderCliKt build --game games/<id> --recipes <folder|a.json,b.json> [--index <file>] [--only <id,id>] [--force true]` with the install's `lib/*` on the classpath (`:map-builder:run` does the same through Gradle). It writes `data/maps/map_<id>.json` for the ids its recipes name and nothing else. A map that has been edited since (revision above 1; every `F1` edit raises it) is not overwritten unless `--force true`, so hand edits survive a re-run. The compiler rejects an unknown name, a prop that leaves the map, and an NPC spawn on a blocked tile.

Tests: `MapCompilerTest` (pure; a tiny in-memory index). A private game keeps its own map checks beside it, outside the repository (gitignored).
