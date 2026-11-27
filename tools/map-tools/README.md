# Morgana Map & Tileset Tools

Generic Python inspection and validation utilities for any game built on Morgana Engine (`map-builder` + `tilegen`).

- `render_map.py` — Renders a compiled `game/data/maps/map_NNN.json` to a PNG using the game's own tileset sheets (`--scale`, `--blocked`, `--spawns`, `--grid`, `--crop`).
- `map_info.py` — Reads a `map-builder --analysis` JSON (`describe` ASCII overview + terrain bodies, `sites` clear-rectangle finder).
- `world_overview.py` — Stitches multi-map `map-recipes/` + `build/map-analysis/` into a single world overview PNG (`--project`, `--scale`, `--out`, `--grid`).
- `world_sites.py` — Finds candidate build footprints across a multi-map world plan (`--project`, `--size`, `--avoid`, `--coast-west`, `--count`).
- `check_tilesets.py` — Verifies that every zone tileset spec listed in `zones.json` / `order.txt` is self-contained and has unique prefixes (`--specs`).
