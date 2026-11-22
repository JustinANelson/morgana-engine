---
type: Module
title: "Module: tilegen"
description: "Creator tool: engine-free pixel-art generator (themed tiles, autotiles, props, structures, paper-doll characters and monsters, JSON tileset specs) with a command-line tool; only the starter tileset is used at runtime."
tags: [module, tooling, assets, tilesets, sprites, paperdolls]
code: ["tilegen/src/main/kotlin/com/morgana/tilegen", "tilegen/build.gradle.kts", "tilegen/specs", "client-core/src/main/kotlin/com/morgana/client/assets/TileImagePixmaps.kt"]
symbols: ["TileImage", "ProceduralTilesetGenerator", "ThemedTilesetGenerator", "TilesetTheme", "NamedTile", "toPixmap", "toTileImage"]
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-13T12:00:00Z }
stale_after: 2027-05-12T00:00:00Z
---

# Module: `:tilegen`

Pure Kotlin (stdlib only, no LibGDX), package `com.morgana.tilegen`. It generates 32 x 32 pixel-art map tiles by theme. It was split out of `:client-core` so the generators can grow, and be tested, as a tool of their own; `:client-core` depends on it (`api`) because the built-in tileset is drawn with it at startup.

| Class | Role |
| :--- | :--- |
| `TileImage` | Plain RGBA8888 image (`IntArray`, `0xRRGGBBAA`, top-left origin). `drawPixel` / `drawImage` blend source-over like `Pixmap`; `setPixel`, `crop`, `copy`. Out-of-bounds draws are ignored. |
| `ProceduralTilesetGenerator` | The classic overworld sheet (16 x 3 starter, 16 x 6 expanded): periodic value noise, fBm and periodic Voronoi so terrain tiles wrap seamlessly at 32 px; public `draw*Tile` primitives reused by the themes. |
| `ThemedTilesetGenerator` | `TilesetTheme` (`CLASSIC`, `FARM`, `DUNGEON`, `SPACESHIP`) to a list of `NamedTile` (filesystem-safe name + 32 x 32 image), `generateTileset(theme)`, and `bundleTiles` (any 32 x 32 images into a 16-column sheet). |

The engine side is a two-function bridge in `:client-core`, `TileImagePixmaps.kt`: `TileImage.toPixmap()` (copies without re-blending) and `Pixmap.toTileImage()`. `ProceduralRetroAssets.buildStarterTilesetPixmap` uses it for the default art, and `GenerateTilesetTool` (`:client-desktop:generateTileset`) uses it for PNG I/O before validating with `GameAssets.scan` ([game-owned art](/server/game_assets.md)).

Output is reproducible for a seed within one JVM, but exact cross-platform determinism is **not** a goal: the aim is tiles that clearly read as their theme. Tests (`TileImageTest`, `ProceduralTilesetGeneratorTest`, `ThemedTilesetGeneratorTest`) run without Gdx natives; they check sizes, seamless wrapping, non-empty uniquely named tiles per theme, and coarse mood checks (dungeon reads darker than farm; spaceship leans blue and cooler than farm). `TileImagePixmapsTest` in `:client-desktop` checks the Pixmap bridge.

The spaceship theme (deck plating, hull walls, viewports, open space and nebula, coolant, airlocks, consoles, reactor, cryo pods, cargo and more) uses a private `cloud()` noise on 8/4/2 px lattices for gas and liquid, since `wrappedFbm` starts at a 1 px lattice and reads as static.

Adding a theme: add a `TilesetTheme` entry and a `generate<Theme>Tiles()` list in `ThemedTilesetGenerator`, then extend `ThemedTilesetGeneratorTest`.

## The full tool (merged from the standalone TilesetGen project, 2026-11-21)
`:tilegen` used to hold only the three files above. The standalone `TilesetGen` project (a sibling folder of this repository) grew into a much larger generator and was moved in here as the same module; its three core files were byte-identical, so nothing in the engine changed. Source of truth is now `tilegen/`; the old folder is no longer used.

| Package | Role |
| :--- | :--- |
| `material` | `PaletteRamp` / `PalettePresets` (hue-shifted ramps, including the `DUNGEON_*` high-craft crypt ramps), `PixelStyle` (selective outline, Bayer dither, colour limits), `SpriteFinish` (the `MORGANA` house finish for sprites, below), `ReliefShading` (`Heightfield` relief shading, `PixelCanvas` contact shadows / wall AO / radial glow halos / layered flames, `PixelOutline` crisp ink outlining), `MaterialTileGenerator`, `TileNoise`, `TileDecals`. Resolution-independent (16, 32, 64 px). |
| `autotile` | `AutotileGenerator`: 47-tile blob and 16-tile cardinal terrain transitions (`grass_on_dirt`, `grass_on_water`, ...) from signed distance fields, so seams match. |
| `structure` | `StructureGenerator` (cliffs, walls, town facades), `PropGenerator` (trees, fences, bushes, stumps, ground clutter, multi-tile props), `BuildingGenerator` (seven large town buildings), `PavingGenerator` (cobble and flagstone), `PlazaGenerator`, `GroundDetailGenerator`, and `DungeonTilesetGenerator` (high-craft 2.5D top-down dungeon tilesets, sculpted multi-tile centerpieces, and room showcase). |
| `sprite` | `PaperDollSpriteGenerator` / `PaperDollUniversal` (layered, 8-direction, LPC-style actions), older `SpriteGenerator` / `PlayerSpriteGenerator`. |
| `spec` | `TilesetSpec` and `PaperDollSpec` JSON schemas, parser and renderer, so a sheet can be described as data. Sample specs live in `tilegen/specs/`. |
| `export` | Pure-JVM `PngEncoder`, `TilesetExporter`, `PaperDollExporter`, Tiled / Godot / RPG Maker layouts and the HTML preview pages. |
| `cli` | `TilesetCli` (generic generators only; per-game commands are in `:game-art`); run with `.\gradlew.bat :tilegen:run --args="<command> ..."` (from the repository root; `--out` is relative to it). Run it with no arguments for the command list. |

Only `cli` and `export` touch the file system. Tests that read `specs/` run with the module directory as the working directory.

## The Morgana sprite style (`PixelStyle.MORGANA`)
The map art is soft: muted hue-shifted ramps, an outline that is only a darker step of the fill, light from the upper left and translucent ground shadows. Characters drawn with flat fills and a near-black outline looked pasted on it. `SpriteFinish` gives a layer the same treatment, per layer so worn gear still lines up: **rim shading** (one pixel inside the lit top/left edge is lightened and warmed, inside the shadow edge darkened and cooled, thin parts left alone), **muted colour** (saturation eased and capped), a **soft outline** (darker, cooler version of the nearest fill, lighter on the lit side) and a **soft two-ring shadow** like the one under the trees. Doll layers use it when the theme style is `morgana` (`"style": "morgana"` in a spec); `TrevanaSpriteGenerator` uses it for its NPCs (each with their own hair and skin) and monsters. Monsters are drawn from spherical, Bayer-dithered blobs in natural `fromBaseColor` ramps, the same shading idea as the oak canopy. Other styles are unchanged.

## Putting sprites into a game
The generators here are game-neutral: no class, monster or item of a particular game lives in `:tilegen`. A game's own sprite kits and the exporters that write them into its `assets/` live in [`:game-art`](/architecture/game_art.md), which depends on this module (never the other way round), and the boundary test keeps game names out of this one.

## Buildings and paving
`BuildingGenerator` draws one multi-tile picture per `BuildingKind` (`COTTAGE_LARGE` 4x3, `INN` 5x4, `SMITHY` 4x3, `SHOP` 4x3, `CHAPEL` 3x4, `GUARDHOUSE` 3x3, `GREAT_HALL` 6x4) in the soft map style: scalloped shingle roofs lit from the upper left, plaster with timber frame or ashlar stone, lit windows with shutters, doors, chimneys, an awning, hanging sign, banners or forge glow by kind, a dark-step outline and a translucent ground shadow. A roof colour override (`BuildingRoof`: red, brown, slate, green, thatch) and a wall theme (`BuildingTheme`: default cream plaster and grey stone, desert sandstone, ember red stone, port blue-grey) make variants. **Placement rule** (same as the oak trees): only the bottom tile row is the footprint, so put it on `MASK_1` with a `blocked` attribute; every row above (roof, upper storey, chimney) goes on `FRINGE_1`, so characters walk behind it. `GeneratedBuilding.doorColumn` says which column of the footprint row holds the door. CLI: `building <kind|all> [--theme desert|ember|port] [--roof thatch] [--name 22] --out <dir>`.

`PavingGenerator` makes seamless, varied town paving (`PavingKind`: pale cobble, warm flagstone, road cobble, ember cobble; 4 variants each, a few mossy gaps) from a wrapped, jittered cell layout, shaded like the buildings. CLI: `paving [--variants 4] --out <dir>` writes one sheet with a row per kind. A map picks a variant per cell with a position hash so a long road does not visibly repeat. Tests: `BuildingAndPavingTest`.

## Plaza props and ground detail
`PlazaGenerator` draws a tiered fountain (4x4: stone basin, rippling water, two bowls, a crystal finial, falling streams and splash rings), a covered well (2x2), a lamp post (1x2), a bench (2x1) and a planter (1x1), all on one 7 x 4 sheet (`sheet()`; CLI `plaza`). The same placement rule as the buildings: tiles from `PlazaKind.blockedFromRow` down go on `MASK_1` with a blocked attribute, tiles above are `FRINGE_1`, and tiles with no solid pixel are skipped.

`GroundDetailGenerator` makes a 4 x 4 sheet of mostly transparent decals (CLI `grounddetail`): three pebble scatters, weeds in a crack, three worn-dirt patches, two puddles, cart ruts across and down, scattered hay, and four `SPILL_*` decals that put ragged dirt over one edge of a paved tile so a road does not end in a straight cut. They go on `MASK_1` (no blocking attribute) on cells with nothing else on them. Tests: `PlazaAndGroundDetailTest`.

## Relief-shading toolkit & 2.5D dungeon tilesets (`ReliefShading`, `DungeonTilesetGenerator`)
`ReliefShading.kt` adds reusable relief-shading and lighting primitives for higher-detail procedural pixel art: `Heightfield` (sculpted domes, beveled boxes, and carved grooves shaded with directional top-left normals, crevice ambient occlusion, and dithered `PaletteRamp` lookups), `PixelCanvas` (grounded contact shadows, directional wall-cast AO, screen-additive radial glow halos clamped to prevent bounding-box clipping, and multi-layered flickering flames), and `PixelOutline` (hue-shifted 4-connected ink outlines).

`DungeonTilesetGenerator` builds cohesive 32 x 32 2.5D top-down dungeon tilesets in three themes (`DungeonTheme.CRYPT`, `EMBER_FORGE`, `MOSSY_RUINS`): low-contrast flagstone floors with wall-AO variants, multi-course heightfield-shaded cobblestone walls with dark void caps, overhanging floor-to-water ledges with vertical stone drop-walls and shoreline foam, stone bridges, seamless multi-tile staircases, floor spikes, iron sewer grates, chests, gold piles, skulls, candles, six sculpted multi-tile props (`lich_statue`, `sarcophagus_effigy`, `knight_statue`, `spectral_brazier`, `open_tomb_skeleton`, `hanging_skeleton`), and a 16 x 11 room showcase PNG. CLI: `dungeon [crypt|ember_forge|mossy_ruins] [--seed 1337] [--out <dir>] [--split]`. Tests: `ReliefShadingTest`, `DungeonTilesetGeneratorTest`.
