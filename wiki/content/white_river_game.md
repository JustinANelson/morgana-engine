---
type: Reference
title: "White River Online"
description: "games/white-river, an original sandbox ORPG: a town and four zones joined by open seams, 3 classes, life skills at four stations, and the first action-combat weapon set."
tags: [content, orpg, white-river, life-skills, assets]
code: ["games/white-river/game.json", "games/white-river/server.json", "games/white-river/README.md", "games/white-river/ROADMAP.md", "game-tests/src/test/kotlin/com/morgana/gametests/WhiteRiverGameTest.kt", "distribution/build.gradle.kts"]
symbols: ["WhiteRiverGameTest", "Zone"]
generated: { by: claude-code/claude-fable-5-2, at: 2026-11-15T20:00:00Z }
stale_after: 2027-05-14T00:00:00Z
---

# White River Online (`games/white-river`)

Added 2026-11-15 as phase A0 of [ADR 0017](/decisions/0017_white_river_online.md). An original game with the structure of a sandbox MMO (grinding zones, life skills, player economy, contested ground); the phases still to come, and the stand-ins used until then, are in `games/white-river/ROADMAP.md`. Player-facing tour: `games/white-river/README.md`. Phase A1 added action skills and combo graphs as content and phase A2 a Lua prototype that steps them (`scripts/action_combat.lua`, only under `lua_combat`): see [action-combat content](/domain/action_combat_content.md).

## Rules (`game.json`, `server.json`)
- Combat module `realtime`; attributes `str`, `agi`, `int`, `vit`; vitals `hp`, `mp` and `sp` (stamina: fast regeneration, pays for Bladewarden and Windrunner skills). Armour counts in the melee and ranged formulas.
- Level cap 60, `xpExpr` `90 * level * level`, `levelOffsetXpScaling` on; four enemy variants (Hardy, Savage, Elder, Gilded).
- A [clock](/domain/game_definition.md) with day and night (a day is 24,000 ticks, 20 minutes) and seasons Thaw, Highwater, Harvest, Rime; the script command `/season` reads it.
- [Loot](/systems/loot_and_affixes.md): rarities Plain, Fine, Rare, Heirloom, Riverforged and 32 affixes in four level bands matching the four gear tiers.
- Modules: the ORPG set with `softSeams` and `subTileMovement` on and `instancing` off (one shared world).

## World
Five 64 x 48 maps joined by [seamless borders](/systems/movement.md) (no warp tiles anywhere): Calder's Ford (safe) east to Millrace Fields (levels 1 to 8) east to Heronreach Marsh (9 to 16), then north to Chalk Hollow (17 to 26, `OPEN_PVP`, boss Marrow Jack in the reavers' redoubt) and Paleback Headwaters (28 to 40, `OPEN_PVP`, boss the Pale Warden). East-west seams are open on rows 22 to 25, north-south seams on columns 30 to 33; every other edge cell is blocked by trees.

## Content
- **3 classes**, 5 skills each (`classReqIds`), the rest learned from manuals (`SPELL_SCROLL`) that carry the skill's level and class.
- **17 creatures** from one formula by level and kind (`creature` in the build script); bosses have nine times the health. Every creature can drop Palestone (rare, broadcast), the future enhancement material.
- **7 resources**: ash trees, chalk outcrops, iron seams, whitefin shoals, pike runs, riverleaf and sunroot patches (herbs need no tool). Nodes are `resource_node` tiles; fishing nodes sit on water and are worked from the bank.
- **25 recipes** at all four stations (`WORKBENCH`, `ANVIL`, `ALCHEMY_TABLE`, `COOKING_FIRE`), each a map event on the square. Gear tiers 2 to 4 are crafted from the tier below plus that zone's materials.
- **Action combat (content only)**: the Bladewarden's *Ford Sword and Shield* [weapon set](/domain/action_combat_content.md): nine action skills (Ford Cut, Backhand, Riverbreak, Undertow, Shieldbreaker, Sidestep, Backstep, Wardstance, Riposte) and an eleven-edge combo graph. Nothing runs it until the action module exists; play still uses the five `realtime` skills.
- **12 quests**: Marshal Renna Vale's eight (one per zone step and boss) and Cook Hesper's four life-skill jobs.
- Shops never sell raw materials, and the best sell rate (75%) is below every buy rate.

## Build script
The generator scripts (`tools/build.py`, `tools/art.py`) that wrote `data/` and `assets/` were deleted on 2026-11-19, so those files are now edited directly; `game.json`, `server.json`, `scripts/` and the docs are hand-written. Maps are laid out with the `Zone` class (ground, mask, fringe, attributes, spawns) and the script refuses to write them unless seams match on both sides and every spawn, node and station is reachable from the class start. Gathering-node markers and four hand-drawn icons (fish, log, plank, stew) are generated in `art.py`. Given a folder argument it renders a preview PNG per map.

## Tests
`WhiteRiverGameTest` (server-app, on a temp copy): content, art and modules valid; class skill sets; monster levels rising zone by zone; seams reciprocal and open at the same cells; everything reachable on foot; every item obtainable and every recipe makeable; no recipe made from shop goods sells for more than it cost (this caught planks sawn from shop-bought logs); tools and manuals on sale; class starts; and a first hour: buy a rod, take the Marshal's quest, catch a whitefin, grill it at the fire (refused away from it), walk across the seam into the fields and kill a rat for experience and quest credit. It also checks the shipped weapon set: the left-click chain, the launcher branch, armor on the finisher and dodges, and that the running server holds the definitions.

Related: Trevana Realms (the structural template), [combat](/systems/combat.md), [economy](/systems/economy.md).
