# White River Online (`games/white-river`)

An original online RPG on Morgana's ORPG module. It borrows the *structure* of large sandbox MMOs such as Black Desert
Online (grinding zones, deep life skills, a player economy, contested territory) and none of their names, lore, art or
skill designs. The plan and what is built so far are in [`ROADMAP.md`](ROADMAP.md).

## The valley (5 maps, 64 x 48 each, joined by open seams: you walk, there are no warps)
| Map | Rules | Levels | What is there |
| :--- | :--- | :--- | :--- |
| 1 **Calder's Ford** | Safe | | The trade town on the river: six shops, the storehouse, the Marshal, and the anvil, workbench, alchemy table and cooking fire |
| 2 **Millrace Fields** | No PvP | 1 to 8 | Rats, reed hens, bramblecaps, aurochs; ash trees, riverleaf, whitefin |
| 3 **Heronreach Marsh** | No PvP | 9 to 16 | Vipers, mire bats, reedfolk, drowned wisps; pike, sunroot |
| 4 **Chalk Hollow** | Contested | 17 to 26 | Ratlings, cutters, golems, the reavers' redoubt and **Marrow Jack**; chalk and iron |
| 5 **Paleback Headwaters** | Contested | 28 to 40 | Yetis, shades, sentinels and **the Pale Warden**; iron, pike under the ice |

The road runs east from the Ford through the fields to the marsh, then north through the Hollow to the headwaters.

## Classes
- **Bladewarden** (sword and shield, stamina): Riverbreak Slash, Wardstance, Undertow Sweep, Shieldbreaker, Floodgate Fury.
- **Windrunner** (bow, stamina): Quickdraw, Reedstep, Scatterflight, Pinning Shot, Galeburst.
- **Duskweaver** (wand, mana): Duskbolt, Gloaming Mend, Mirewake, Veil of Dusk, Nightfall.

Each starts with its first skill (the Duskweaver with two) and buys manuals for the rest from Skillmaster Ilya.

## Life skills
Gather (hatchet, pick and rod from Outfitter Pim; herbs by hand), then process and craft at the stations on the square:
planks, bows and arrows at the **workbench**; ingots, blades and armour at the **anvil**; tonics and staves at the
**alchemy table**; fish, broth and stew at the **cooking fire**. Shops never sell raw materials, so everything crafted
starts with someone gathering. Sell through shops, trade, mail or the player market.

## Running it
```powershell
.\gradlew.bat :server-app:run --args="--game games/white-river"
.\gradlew.bat :client-desktop:run
```

## Editing and Validating
The generator scripts (`tools/build.py`, `tools/art.py`) were deleted on 2026-11-19. `data/` and `assets/` are the source of truth: edit them directly (or through the F1 editors) and run `validateContent`.
```powershell
.\gradlew.bat :server-app:validateContent "-PcontentArgs=--game games/white-river"
.\gradlew.bat :server-app:validateArt "-PcontentArgs=--game games/white-river"
```
The script needs Pillow and the Time Fantasy sheets (`WHITE_RIVER_ASSETS_DIR`, default
`../TilesetGen/assets/time_fantasy/conquer_online` beside the repository). It deletes and rewrites `data/` and
`assets/`, so an F1 or hand edit must also be made in the script.
