"""Checks that every zone's tileset spec is self-contained.

Reads <specs>/zones.json (zone -> spec file and the maps it serves) and the spec files, then verifies each spec has
what a map of that zone needs: ground, water, path, cliffs, trees, rocks, ruins, camp, bridge, sign, landmark, log or
stump, flora and containers. Also checks name prefixes are unique to a zone and the spec order file matches.

    python check_tilesets.py [--specs path/to/art-specs]   # exit code 1 when anything is missing
"""
import json
import pathlib
import sys

CHECKS = {
    'ground': lambda s: sum(1 for m in s['materials'] if m['pattern'] == 'TUFTED_GROUND') >= 2,
    'water': lambda s: any(m['pattern'] == 'FLAT_WATER' for m in s['materials']) and
    any(a['foregroundMaterial'] in {m['name'] for m in s['materials'] if m['pattern'] == 'FLAT_WATER'} for a in s['autotiles']),
    'path': lambda s: any(any(k in a['foregroundMaterial'] for k in ('dirt', 'earth', 'path', 'flag', 'cobble', 'scree', 'peat', 'packed', 'plank', 'paver'))
                          for a in s['autotiles']),
    'cliffs': lambda s: len(s['cliffs']) >= 1,
    'trees': lambda s: sum(1 for p in s['props'] if p['kind'] in ('LEAFY_TREE', 'FRUIT_TREE', 'EVERGREEN_TREE', 'DEAD_TREE')) >= 3,
    'rocks': lambda s: any(p['kind'] == 'BOULDER' for p in s['props']),
    'ruins': lambda s: sum(1 for p in s['props'] if p['kind'] in ('RUIN_WALL', 'RUIN_TOWER', 'RUIN_PILLAR')) >= 2,
    'camp': lambda s: any(p['kind'] in ('CAMPFIRE', 'MEDIEVAL_TENT') for p in s['props']),
    'bridge': lambda s: any(p['kind'] in ('WOOD_BRIDGE', 'ROPE_BRIDGE') for p in s['props']),
    'sign': lambda s: any(p['kind'] == 'SIGNPOST' for p in s['props']),
    'landmark': lambda s: any(p['kind'] in ('OBELISK', 'WAYPOINT_PILLAR', 'SHRINE', 'STONE_ARCHWAY', 'GATEHOUSE', 'WATCH_TOWER', 'SEALED_GATE') for p in s['props']),
    'log_or_stump': lambda s: any(p['kind'] in ('OLD_STUMP', 'FALLEN_LOG') for p in s['props']),
    'flora': lambda s: any(p['kind'] in ('FLOWER_BUSH', 'CRYSTAL_BLOOM', 'MOSS_MOUND', 'REED_CLUMP', 'WHEAT_SHEAF') for p in s['props']),
    'containers': lambda s: any(p['kind'] in ('BARREL', 'CRATE', 'CHEST_CLOSED') for p in s['props']),
}

# What each kind of zone must have. Shared kits and reserves are exempt from the full list.
FULL = list(CHECKS)
RELAXED = {
    'village-kit': [],
    'reserve': [],
}


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    specs_dir = pathlib.Path(argv[argv.index('--specs') + 1]) if '--specs' in argv else pathlib.Path.cwd() / 'art-specs'
    zones = json.load(open(specs_dir / 'zones.json', encoding='utf-8'))['zones']
    order = [l.strip() for l in open(specs_dir / 'order.txt', encoding='utf-8') if l.strip()]
    problems = 0
    prefixes = {}
    listed = []
    for z in zones:
        spec_file = z['spec']
        listed.append(spec_file)
        spec = json.load(open(specs_dir / spec_file, encoding='utf-8'))
        needs = RELAXED.get(z.get('kind', 'zone'), FULL)
        missing = [k for k in needs if k not in z.get('waive', []) and not CHECKS[k](spec)]
        names = [m['name'] for m in spec['materials']] + [p['name'] for p in spec['props']] + \
            [a['name'] for a in spec['autotiles']] + [c['name'] for c in spec['cliffs']]
        bad = [n for n in names if not n.startswith(z['prefix'])]
        for pre in (z['prefix'],):
            if pre in prefixes:
                print(f"  prefix {pre} used by {prefixes[pre]} and {z['id']}")
                problems += 1
            prefixes[pre] = z['id']
        status = 'ok' if not missing and not bad else 'PROBLEM'
        print(f"{z['id']:<18} {spec_file:<34} {status}")
        if missing:
            print('   missing:', ', '.join(missing))
            problems += 1
        if bad:
            print('   names without the prefix', z['prefix'], ':', ', '.join(bad[:6]), '...' if len(bad) > 6 else '')
            problems += 1
    if listed != order:
        print('order.txt does not match zones.json order')
        print('  zones.json:', listed)
        print('  order.txt :', order)
        problems += 1
    print('OK' if problems == 0 else f'{problems} problem(s)')
    return 0 if problems == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
