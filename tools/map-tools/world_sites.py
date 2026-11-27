#!/usr/bin/env python
"""Finds candidate build sites across a multi-map world from its compiled map-analysis files.

  python world_sites.py [--project path/to/game-project] --size 143x227 --avoid sea,river,peak,marsh,snow,dune,scree --coast-west 45 [--count 6]

Reads <project>/build/world-plan/map_NNN.json and <project>/map-recipes/map_*.json. Prints candidate top-left world
tiles ranked by how little of the footprint is unsuitable and, with --coast-west N, how much of the west side has sea
within N tiles.
"""
import glob
import json
import os
import sys


def load_world(root=None):
    root = root or os.getcwd()
    rec = {}
    for f in glob.glob(os.path.join(root, "map-recipes", "map_*.json")):
        r = json.load(open(f, encoding="utf-8"))
        rec[r["id"]] = (r["worldX"], r["worldY"], r["width"], r["height"])
    W = max(x + w for x, y, w, h in rec.values())
    H = max(y + h for x, y, w, h in rec.values())
    g = [[None] * W for _ in range(H)]
    for mid, (x0, y0, w, h) in rec.items():
        a = json.load(open(os.path.join(root, "build", "world-plan", f"map_{mid:03d}.json"), encoding="utf-8"))
        for y in range(h):
            row = a["terrain"][y]
            for x in range(w):
                g[y0 + y][x0 + x] = a["legend"][row[x]]
    return g, W, H


def main(argv):
    arg = lambda k, d=None: argv[argv.index(k) + 1] if k in argv else d
    root = arg("--project", os.getcwd())
    fw, fh = (int(v) for v in arg("--size", "143x227").split("x"))
    avoid = set(arg("--avoid", "sea,river,peak,marsh,snow,dune,scree").split(","))
    coast = int(arg("--coast-west", "0"))
    count = int(arg("--count", "6"))
    g, W, H = load_world(root)
    out = []
    for wy in range(0, H - fh + 1, 4):
        for wx in range(0, W - fw + 1, 4):
            n = bad = 0
            for y in range(wy, wy + fh, 4):
                for x in range(wx, wx + fw, 4):
                    n += 1
                    bad += g[y][x] in avoid
            rows = hit = 0
            if coast:
                for y in range(wy, wy + fh, 8):
                    rows += 1
                    hit += any(g[y][x] == "sea" for x in range(max(0, wx - coast), wx))
            out.append((bad / n, -(hit / rows if rows else 0), wx, wy))
    out.sort()
    print(f"world {W}x{H}; footprint {fw}x{fh}; avoiding {sorted(avoid)}")
    for bad, c, wx, wy in [o for o in out if o[1] <= -0.7 or not coast][:count]:
        print(f"  origin ({wx},{wy}): {bad * 100:.1f}% unsuitable" + (f", sea within {coast} tiles west on {-c * 100:.0f}% of rows" if coast else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
