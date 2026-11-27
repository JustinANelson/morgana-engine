#!/usr/bin/env python
"""Reads a compiled map the way a mapper would, for people and LLMs that cannot see the picture.

  python scripts/map_info.py describe build/map-analysis/map_003.json [--cell 2]
  python scripts/map_info.py sites    build/map-analysis/map_003.json --size 7x7 [--terrain litter,moss] [--near 20,24] [--count 6] [--spacing 10]

The analysis file is written by the map compiler (`trevena.ps1 maps` passes --analysis build/map-analysis). `describe` prints an
overview grid (one character per --cell x --cell tiles: `~` water or other blocked ground, `#` blocked, `+` a prop or cliff, otherwise the
terrain's first letter, upper case when a spawn is there), the terrain legend and the bodies of each terrain with their bounding boxes.
`sites` finds clear rectangles (walkable, nothing on them, only the named terrains) closest to a point: where a camp, a shrine or a
village can go without cutting trees or stepping into a lake.
"""
import json
import math
import sys
from collections import deque

WATER_WORDS = ("stream", "sea", "water", "pond", "river", "lake", "lava", "fen", "marsh")


def load(path):
    return json.load(open(path, encoding="utf-8"))


def grid(a):
    w, h = a["width"], a["height"]
    terr = [[a["legend"][ch] for ch in row] for row in a["terrain"]]
    blocked = [[ch == "#" for ch in row] for row in a["blocked"]]
    occ = [[ch == "o" for ch in row] for row in a["occupied"]]
    return w, h, terr, blocked, occ


def components(w, h, member):
    seen = [[False] * w for _ in range(h)]
    out = []
    for y in range(h):
        for x in range(w):
            if seen[y][x] or not member(x, y):
                continue
            q = deque([(x, y)])
            seen[y][x] = True
            cells = []
            while q:
                cx, cy = q.popleft()
                cells.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = cx + dx, cy + dy
                    if 0 <= nx < w and 0 <= ny < h and not seen[ny][nx] and member(nx, ny):
                        seen[ny][nx] = True
                        q.append((nx, ny))
            out.append(cells)
    return out


def describe(a, cell):
    w, h, terr, blocked, occ = grid(a)
    spawn_at = {(x, y) for _, x, y in a["spawns"]}
    print(f"Map {a['id']} \"{a['name']}\" {w}x{h} tiles; overview 1 char = {cell}x{cell} tiles (columns are x, rows are y)")
    header = "    " + "".join(str((x // 10) % 10) if x % 10 == 0 else " " for x in range(0, w, cell))
    print(header)
    for y0 in range(0, h, cell):
        row = []
        for x0 in range(0, w, cell):
            counts, nb, no, ns, n = {}, 0, 0, 0, 0
            for y in range(y0, min(y0 + cell, h)):
                for x in range(x0, min(x0 + cell, w)):
                    n += 1
                    t = terr[y][x]
                    counts[t] = counts.get(t, 0) + 1
                    nb += blocked[y][x]
                    no += occ[y][x] and not blocked[y][x]
                    ns += (x, y) in spawn_at
            top = max(counts, key=counts.get)
            if nb * 2 >= n:
                ch = "~" if any(k in top for k in WATER_WORDS) else "#"
            elif no * 2 >= n:
                ch = "+"
            else:
                ch = top[0]
            row.append(ch.upper() if ns and ch not in "~#+" else ch)
        print(f"{y0:3d} " + "".join(row))
    print("\nTerrain (letter in the overview = first letter): " + ", ".join(f"{n}" for n in sorted(set(a['legend'].values()))))
    print("\nBodies (4-connected), largest first:")
    for name in sorted(set(a["legend"].values())):
        comps = [c for c in components(w, h, lambda x, y, n=name: terr[y][x] == n) if len(c) >= 12]
        comps.sort(key=len, reverse=True)
        total = sum(1 for y in range(h) for x in range(w) if terr[y][x] == name)
        print(f"  {name}: {total} tiles" + ("" if comps else " (no body of 12+ tiles)"))
        for c in comps[:6]:
            xs = [p[0] for p in c]
            ys = [p[1] for p in c]
            print(f"     {len(c):5d} tiles  x {min(xs)}..{max(xs)}  y {min(ys)}..{max(ys)}  centre ({sum(xs) // len(c)},{sum(ys) // len(c)})")
    walk = components(w, h, lambda x, y: not blocked[y][x])
    walk.sort(key=len, reverse=True)
    print(f"\nWalkable regions: {len(walk)}; the largest has {len(walk[0]) if walk else 0} tiles" + ("; others: " + ", ".join(str(len(c)) for c in walk[1:6]) if len(walk) > 1 else ""))
    by_npc = {}
    for npc, x, y in a["spawns"]:
        by_npc.setdefault(npc, []).append((x, y))
    print("Spawns: " + (", ".join(f"npc {n} x{len(p)}" for n, p in sorted(by_npc.items())) or "none"))


def sites(a, size, terrains, near, count, spacing):
    w, h, terr, blocked, occ = grid(a)
    sw, sh = size
    ok = [[(not blocked[y][x]) and (not occ[y][x]) and (not terrains or terr[y][x] in terrains) for x in range(w)] for y in range(h)]
    # Prefix sums so each rectangle test is O(1).
    ps = [[0] * (w + 1) for _ in range(h + 1)]
    for y in range(h):
        for x in range(w):
            ps[y + 1][x + 1] = ps[y][x + 1] + ps[y + 1][x] - ps[y][x] + (1 if ok[y][x] else 0)
    cands = []
    for y in range(h - sh + 1):
        for x in range(w - sw + 1):
            if ps[y + sh][x + sw] - ps[y][x + sw] - ps[y + sh][x] + ps[y][x] == sw * sh:
                cands.append((x + sw // 2, y + sh // 2))
    nx, ny = near if near else (w // 2, h // 2)
    cands.sort(key=lambda p: math.hypot(p[0] - nx, p[1] - ny))
    chosen = []
    for p in cands:
        if all(math.hypot(p[0] - q[0], p[1] - q[1]) >= spacing for q in chosen):
            chosen.append(p)
        if len(chosen) >= count:
            break
    print(f"{len(cands)} clear {sw}x{sh} spots" + (f" on {','.join(terrains)}" if terrains else "") + f"; the {len(chosen)} nearest ({nx},{ny}), at least {spacing} apart (centre tile):")
    for p in chosen:
        print(f"  ({p[0]},{p[1]})  top-left ({p[0] - sw // 2},{p[1] - sh // 2})  distance {math.hypot(p[0] - nx, p[1] - ny):.0f}")


def main(argv):
    if len(argv) < 3 or argv[1] not in ("describe", "sites"):
        print(__doc__)
        return 1
    a = load(argv[2])
    opt = {argv[i]: argv[i + 1] for i in range(3, len(argv) - 1, 2) if argv[i].startswith("--")}
    if argv[1] == "describe":
        describe(a, int(opt.get("--cell", max(1, a["width"] // 60))))
    else:
        sz = opt.get("--size", "6x6").split("x")
        near = tuple(int(v) for v in opt["--near"].split(",")) if "--near" in opt else None
        terrains = set(opt["--terrain"].split(",")) if "--terrain" in opt else set()
        sites(a, (int(sz[0]), int(sz[1])), terrains, near, int(opt.get("--count", 6)), int(opt.get("--spacing", 8)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
