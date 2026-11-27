#!/usr/bin/env python
"""One picture of a whole multi-map world from its map-builder analysis files (build/map-analysis).

  python world_overview.py [--project path/to/game-project] [--scale 2] [--out build/world-overview.png] [--grid]

Each tile is one pixel (times --scale), coloured by its terrain, occupied tiles darker; map borders and ids with --grid.
Coordinates printed on the image are world tiles.
"""
import glob
import json
import os
import sys

from PIL import Image, ImageDraw

COLORS = {
    "sea": (28, 70, 150), "beach": (150, 130, 100), "sand": (222, 205, 140), "river": (60, 120, 210), "pond": (60, 120, 210),
    "grass": (92, 150, 70), "litter": (70, 100, 50), "moss": (50, 120, 60), "mud": (90, 80, 60), "marsh": (40, 100, 100),
    "scrub": (130, 120, 80), "scree": (150, 150, 150), "peak": (230, 230, 240), "snow": (235, 240, 250), "dune": (225, 180, 90),
    "dirt": (160, 110, 60), "cobble": (130, 130, 130), "flag": (170, 170, 180), "soil": (100, 70, 40),
}


def main(argv):
    root = argv[argv.index("--project") + 1] if "--project" in argv else os.getcwd()
    scale = int(argv[argv.index("--scale") + 1]) if "--scale" in argv else 2
    out = argv[argv.index("--out") + 1] if "--out" in argv else os.path.join(root, "build", "world-overview.png")
    recipes = {}
    for f in glob.glob(os.path.join(root, "map-recipes", "map_*.json")):
        r = json.load(open(f, encoding="utf-8"))
        recipes[r["id"]] = (r.get("worldX", 0), r.get("worldY", 0), r["width"], r["height"], r["name"])
    if not recipes:
        print("no recipes")
        return 1
    W = max(x + w for x, y, w, h, _ in recipes.values())
    H = max(y + h for x, y, w, h, _ in recipes.values())
    img = Image.new("RGB", (W, H), (0, 0, 0))
    px = img.load()
    for mid, (x0, y0, w, h, _) in recipes.items():
        path = os.path.join(root, "build", "map-analysis", f"map_{mid:03d}.json")
        if not os.path.exists(path):
            continue
        a = json.load(open(path, encoding="utf-8"))
        legend = {k: COLORS.get(v, (255, 0, 255)) for k, v in a["legend"].items()}
        for y in range(h):
            row, blk, occ = a["terrain"][y], a["blocked"][y], a["occupied"][y]
            for x in range(w):
                c = legend[row[x]]
                if occ[x] == "o" and blk[x] != "#":
                    c = (max(0, c[0] - 45), max(0, c[1] - 45), max(0, c[2] - 45))  # something stands here (a prop, a wall)
                px[x0 + x, y0 + y] = c
    if scale != 1:
        img = img.resize((W * scale, H * scale), Image.NEAREST)
    if "--grid" in argv:
        d = ImageDraw.Draw(img)
        for mid, (x0, y0, w, h, name) in recipes.items():
            d.rectangle([x0 * scale, y0 * scale, (x0 + w) * scale - 1, (y0 + h) * scale - 1], outline=(255, 255, 0))
            d.text((x0 * scale + 4, y0 * scale + 4), f"{mid} {name}", fill=(255, 255, 0))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out)
    print(f"wrote {out} ({img.width}x{img.height})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
