---
type: Playbook
title: "AI-Assisted Mapping"
description: "How a person or an LLM builds an organic, varied map: noise for the land, a picture and a text summary to read it, then placement of camps and landmarks on what the land made."
tags: [playbook, maps, tooling, llm]
code: ["map-builder/src/main/kotlin/com/morgana/mapbuilder/MapCompiler.kt", "map-builder/src/main/kotlin/com/morgana/mapbuilder/NoiseField.kt", "map-builder/src/main/kotlin/com/morgana/mapbuilder/MapRecipe.kt", "map-builder/src/main/kotlin/com/morgana/mapbuilder/MapAnalysis.kt"]
symbols: ["NoiseOp", "TerraceOp", "PathOp", "MapAnalysis", "NoiseField"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-26T12:00:00Z }
stale_after: 2027-05-25T00:00:00Z
---

# AI-Assisted Mapping

Hand-placed shapes (`rect`, `ellipse`, `wall`) give boxes, and an LLM given only those writes boxes. The split that works:

1. **Noise makes the land** (`noise`, `terrace`, a `meander`ing `path`): seeded, so the same recipe always gives the same map.
2. **The tools let you look at it**: a PNG and a text summary of the *compiled* map.
3. **The LLM (or you) places what needs judgement** on the land that exists: the road between sites, camps by difficulty, a shrine on the plateau, the seam gaps. Those ops are `path`, `prop`, `scatter`, `camps`, `spawn`, `warp`, `reserve`.

## The ops
* `noise`: a field (`scale` tiles per feature, `octaves`, `warp` for winding shapes, `smooth` blur passes, `edge`/`edgeBias` to sink or raise the border) painted in `bands` (`below` 0..1 gives a terrain). `onlyOn` limits what it may overwrite, `minArea` undoes patches smaller than that, `store` keeps the field for `terrace`. Stack several (elevation for water, moisture for moss) with different `salt`s.
* `noise` with `tiltX`/`tiltY` tilts the field across its `region`, which makes a **coast**: paint the strip with the terrain you want to lose (sea), then band the tilted noise into sea, a rock fringe and land. Because land paints over sea, `minArea` also removes land specks (islands) that are too small. For **forests and meadows** paint a second terrain with the same tile (`wood`) in noise clumps and scatter trees on `wood` densely and on plain grass sparsely: the trees then gather in groves instead of an even sprinkle. Citadel (map 1) uses both (`scripts/gen_ring1_recipe.py`).
* `terrace`: levels from a stored field at `thresholds`; plateau tiles take that level's `terrains`; the south edge becomes a two-row cliff face from a wall set (`<set>_top_*` over `<set>_bot_*`, blocked); `ramps` open walkable gaps; slivers narrower than `minWidth` or shallower than `minDepth` are levelled off; `rowStep` snaps edges to bands of rows so faces run in long horizontal stretches. Wall sets are in the tile index (`weald_root_bank`, `marches_terrace_cliff`, `frost_cliff`...). The set's `void` tiles are a dark roof block: do not use them for ground, the plateau top is a normal terrain.
* `path` with `meander` (tiles) and `wavelength`: a road or river that wanders and still ends where it was told to.

Order matters: land first (noise, terrace), then clear the places that must stay open (a `rect` at the spawn), then roads, then props and camps, which see the terrain as painted so far.

## The loop
```
.\scripts\trevena.ps1 maps                          # compile; writes build/map-analysis/map_NNN.json; the running server hot-swaps the map
.\scripts\trevena.ps1 mapview -Map 3 -MapArgs '--grid 10 --blocked --spawns'   # build/map-view/map_003.png (red = blocked, dots = spawns, grid = tile ruler)
.\scripts\trevena.ps1 mapinfo -Map 3                # overview grid, lakes and hills with bounding boxes, walkable regions, spawn counts
python scripts/map_info.py sites build/map-analysis/map_003.json --size 7x7 --terrain litter,moss --near 20,24 --count 6
```
`sites` finds clear rectangles (walkable, nothing on them) nearest a point: where a camp or shrine fits without cutting trees or stepping into a lake. Tell the LLM to look at the picture and the `mapinfo` text after every change, to place things with `sites` rather than guessing coordinates, and to keep `anchor` on a tile the player can stand on (camps unreachable from it are dropped).

## What an LLM should be asked to do
Give it a brief ("a wet forest with a ridge to the north, an old shrine on the ridge, wolf camps in the open, a road from the south gate to the east seam"), the terrain and tile names, and these steps: write the noise and terrace ops, compile, look, then add the road, shrine and camps using `sites`, compile, look, and finish with `validate`. A map edited by hand in F1 (revision above 1) is not overwritten unless the build is forced.

Tests: `MapCompilerTest` (noise bands, determinism, `minArea`, `onlyOn`, meander, terrace faces never a single row, ramps, the analysis).
