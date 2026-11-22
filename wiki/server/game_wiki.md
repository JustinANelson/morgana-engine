---
type: Reference
title: "Game Wiki: a Browsable Wiki Generated from the Data Files"
description: "A cross-linked wiki of every item, monster, spell, quest, recipe, shop, resource, map and class, with a Balance report, built from game.json and the content JSON; static files or served live at /wiki/."
tags: [wiki, balance, tooling, server, content]
code: ["server-app/src/main/kotlin/com/morgana/server/tools/wiki/GameWiki.kt", "server-app/src/main/kotlin/com/morgana/server/tools/wiki/WikiHtml.kt", "server-app/src/main/kotlin/com/morgana/server/tools/ContentTool.kt", "server-app/src/main/kotlin/com/morgana/server/web/WebClientHttpHandler.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt", "core-domain/src/main/kotlin/com/morgana/domain/explore/WorldMapTerrain.kt"]
symbols: ["GameWiki", "WikiHtml", "gameWikiCommand", "gameWikiPages", "WikiConfig", "monsterStats", "flagsFor", "WebClientHttpHandler"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-17T18:00:00Z }
stale_after: 2027-05-16T00:00:00Z
---

# Game Wiki: a Browsable Wiki Generated from the Data Files

Not to be confused with this repository's own `wiki/` (the developer knowledge base). The **game wiki** is for a game's content: what every item does, what every monster is worth, where everything is. It is built from `game.json` and the content JSON by `GameWiki`, so nothing is typed by hand and it cannot drift from the game. Developers use it to balance and fine tune; players can read it too.

## Two ways to get it
- **Static files**: `.\gradlew.bat :server-app:gameWiki "-PcontentArgs=--game games/trevana-realms"` writes `build/game-wiki/<game id>/` (about 300 files for Trevana Realms; add `--out <folder>` to choose). Open `index.html`, or put the folder on any web host. Links are relative, so it works from disk.
- **Served live**: the server answers `http://<server>:<web port>/wiki/` on the same port as the browser client (the WebSocket port, so it needs `--web` or a `webSocketPort`). It is built from the content in memory, so F1 edits and file reloads show up (cached for 10 s, `gameWikiPages`). Switch it off with `"wiki": { "enabled": false }` in `server.json` (`WikiConfig`); the pages are then 404.

## What is in it
Home (counts, search), Items, Monsters, NPCs, Spells, Quests, Recipes, Shops, Resources, Maps, Classes and Balance. Every table sorts on any column (numbers as numbers) and filters; the search box on every page finds anything by name. Detail pages cross-link: an **item** lists who drops it (with chance and amount), who sells it and at what price, what recipes make or use it, what gathers it, which quests reward it, and says plainly when nothing in the data gives it to a player; a **monster** shows its stats, its drop table with expected value, the maps it spawns on (or generated dungeon floors) and the quests that need it; a **map** has a picture of its real terrain (from `WorldMapTerrain`, water, walls, warps) with monster spawns as coloured dots keyed to a table, its borders and warps; a **quest** shows objectives as links and its reward.

## The Balance page
Everything is computed from the data (`monsterStats`, `flagsFor`):
- **Experience curve**: XP to the next level and cumulative XP for every level, against the median experience of monsters within 2 levels, giving *typical kills per level*; a jump marks a level that will feel much slower or faster than its neighbours.
- **Monster table** (also on the Monsters page): HP, damage, DPS (damage over attack interval), XP, **XP per HP**, **loot value** (chance x average amount x base price, summed over drops), kills to level, spawn count. **Flags** compare each monster with the others within 5 levels (needs 3 peers): XP per HP, DPS, HP or loot below half or above double the median, plus "no HP", "no drops" and "never spawns" (a monster on no map and on no dungeon generator).
- **Crafting that loses money**: recipes whose output is worth less than the ingredients (not necessarily wrong, crafting gives experience).
- **Shop loops**: a shop that pays more for an item than it sells it for in the same currency, which makes money without limit.
- **Data problems**: items nothing gives a player, monsters that never spawn, materials no recipe or quest uses.

The numbers compare content with other content; they do not predict play. Item "value" is the base `price`, not what a shop pays.

## Notes
- Pure and tested (`GameWikiTest`): `GameWiki(game, catalog).pages()` returns file name to bytes, so the tool and the server share one code path; a test builds the real Trevana Realms wiki and checks every link and image resolves. All text from the data is HTML-escaped, and the search index escapes angle brackets.
- No external files or scripts: one `wiki.css`, one `wiki.js`, and a generated `search-index.js`; it follows the browser's light or dark setting.
- Not built yet: per-level character stat tables (vitals from the max formulas), damage-versus-armour calculators, diffing two versions of a game, and a quest giver and hand-in column (the data does not say who offers a quest).
- Related: [world map](/systems/exploration.md) (same terrain reading), [web client serving](/server/server_runtime.md), [content validation](/server/persistence_layout.md).
