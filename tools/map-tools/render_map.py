#!/usr/bin/env python
"""Draws a compiled map (game/data/maps/map_NNN.json) as a PNG, from the game's own tileset sheets.

  python scripts/render_map.py game/data/maps/map_003.json out.png [--scale 1] [--blocked] [--spawns] [--grid 10] [--crop x,y,w,h]

--blocked tints blocked tiles red, --spawns marks NPC spawn slots, --grid N draws a tile ruler every N tiles (so coordinates can be
read off the picture), --crop shows only part of the map (tile coordinates). The compiler's output is what is drawn, so this is also
how an LLM (or a person) looks at a recipe's result.
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

LAYER_ORDER = ["GROUND", "MASK_1", "MASK_2", "MASK_3", "FRINGE_1", "FRINGE_2", "FRINGE_3"]


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    flags = {a.split("=")[0] for a in argv[1:] if a.startswith("--")}
    opt = {}
    it = iter(argv[1:])
    for a in it:
        if a in ("--scale", "--grid", "--crop"):
            opt[a] = next(it, None)
    src, out = Path(args[0]), Path(args[1])
    game = src.parent.parent.parent  # game/data/maps/map_NNN.json -> game
    m = json.loads(src.read_text(encoding="utf-8"))
    w, h, ts = m["width"], m["height"], m.get("tileSize", 32)
    crop = [int(v) for v in opt["--crop"].split(",")] if "--crop" in opt else [0, 0, w, h]
    scale = float(opt.get("--scale", 1))
    sheets = {}

    def sheet(i):
        if i not in sheets:
            sheets[i] = Image.open(game / "assets" / "tilesets" / f"{i}.png").convert("RGBA")
        return sheets[i]

    cx, cy, cw, ch = crop
    img = Image.new("RGBA", (cw * ts, ch * ts), (0, 0, 0, 255))
    for y in range(cy, min(cy + ch, h)):
        for x in range(cx, min(cx + cw, w)):
            cell = m["cells"][y * w + x]
            layers = cell["layers"]
            for name in LAYER_ORDER:
                p = layers.get(name)
                if not p:
                    continue
                s = sheet(p["tilesetId"])
                tile = s.crop((p["srcTileX"] * ts, p["srcTileY"] * ts, p["srcTileX"] * ts + ts, p["srcTileY"] * ts + ts))
                img.alpha_composite(tile, ((x - cx) * ts, (y - cy) * ts))
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay, "RGBA")
    if "--blocked" in flags:
        for y in range(cy, min(cy + ch, h)):
            for x in range(cx, min(cx + cw, w)):
                if m["cells"][y * w + x].get("attribute", {}).get("_type") == "blocked":
                    draw.rectangle([(x - cx) * ts, (y - cy) * ts, (x - cx + 1) * ts - 1, (y - cy + 1) * ts - 1], fill=(255, 0, 0, 70))
    if "--spawns" in flags:
        draw = ImageDraw.Draw(img, "RGBA")
        for sp in m.get("npcSpawns", []):
            x, y = sp["spawnX"], sp["spawnY"]
            if cx <= x < cx + cw and cy <= y < cy + ch:
                draw.ellipse([(x - cx) * ts + 8, (y - cy) * ts + 8, (x - cx) * ts + 24, (y - cy) * ts + 24], fill=(255, 220, 0, 230), outline=(0, 0, 0, 255))
    img.alpha_composite(overlay)
    draw = ImageDraw.Draw(img, "RGBA")
    if "--grid" in opt or "--grid" in flags:
        step = int(opt.get("--grid", 10))
        for x in range(cx - cx % step, cx + cw + 1, step):
            draw.line([(x - cx) * ts, 0, (x - cx) * ts, ch * ts], fill=(255, 255, 255, 90))
            draw.text(((x - cx) * ts + 2, 2), str(x), fill=(255, 255, 255, 255))
        for y in range(cy - cy % step, cy + ch + 1, step):
            draw.line([0, (y - cy) * ts, cw * ts, (y - cy) * ts], fill=(255, 255, 255, 90))
            draw.text((2, (y - cy) * ts + 2), str(y), fill=(255, 255, 255, 255))
    if scale != 1:
        img = img.resize((int(img.width * scale), int(img.height * scale)), Image.NEAREST)
    img.convert("RGB").save(out)
    print(f"wrote {out} ({img.width}x{img.height})")


if __name__ == "__main__":
    main(sys.argv)
