---
type: Reference
title: "Ashen Keep: the Reference Co-op Dungeon Crawler & Roguelike"
description: "games/roguelike, the reference dungeon crawler and roguelike realm: Ashen Keep multiplayer hub and Floor 1 (The Sunken Crypt) built with the Halcyon Reach tileset and paperdoll workflow."
tags: [content, roguelike, reference, instancing, assets]
code: ["games/roguelike/game.json", "games/roguelike/server.json", "games/roguelike/data/maps", "games/roguelike/README.md", "tilegen/src/main/kotlin/com/morgana/tilegen/export/RoguelikeGameExporter.kt", "server-app/src/test/kotlin/com/morgana/server/RoguelikeReferenceGameTest.kt", "distribution/build.gradle.kts"]
symbols: ["RoguelikeReferenceGameTest", "RoguelikeGameExporter", "RoguelikeIconArt"]
generated: { by: antigravity/gemini-4-neon, at: 2026-11-21T20:00:00Z }
stale_after: 2027-05-21T00:00:00Z
---

# Ashen Keep: the Reference Co-op Dungeon Crawler & Roguelike (`games/roguelike`)

Replaced the earlier *Lanternfall* template on 2026-11-21. **Ashen Keep** (`games/roguelike`, id `roguelike`) is the reference co-op dungeon crawler and roguelike shipped with Morgana ORPG and the SDK. It starts cleanly with a **multiplayer fortress hub** and **1 instanced procedural dungeon floor**, built using the `:tilegen` Halcyon Reach art pipeline (`PavingGenerator`, `BuildingGenerator`, `PlazaGenerator`, `GroundDetailGenerator`, `PropGenerator`, and `PixelStyle.MORGANA` 32×48 paperdoll sprites).

## World & Content

| Kind | What |
| :--- | :--- |
| Map 1 **Ashen Keep** | 36×26 safe multiplayer hub built with varied cobblestone/flagstone paving (`tilesets/1.png`), Forgemaster's Smithy (`2.png`), Apothecary Shop (`3.png`), Keep Hall (`4.png`), tiered healing fountain, well, lamps, benches, planters (`5.png`), ground details, trees, and northern stone archway dungeon portal (`6.png`). Six hub NPCs: Commander Vael (quest giver, event 1), Delve Warden Orin (difficulty tiers, event 2), Apothecary Lyra (shop 1), Forgemaster Torin (shop 2), Relic Broker Vex (shop 3), and Vaultkeeper Maren (shared stash banker). |
| Map 2 **The Sunken Crypt (Floor 1)** | Instanced 44×34 `BSP_DUNGEON` procedural floor (`corridorWidth: 2`) using the `MORGANA` crypt tileset (`tilesets/7.png`) with front wall faces (`wallFaceTile`), crypt decorations, room features (**Wayside Shrine** & **Wandering Peddler**), 5 crypt monster types, elite variants (Frenzied, Champion, Ashen), Normal/Hard/Nightmare scaling tiers, and **Malakor, the Ashen Warden** (3-phase scripted boss) guarding the exit portal back to Ashen Keep. |
| Classes (3) | **Vanguard** (melee/shield), **Ranger** (bow/arrows), **Pyromancer** (staff/spells). Each starts with visible class-tinted `paperdollingV2` gear, potions, a 40-slot bag, **Rekindle** (revive), and an action-combat `weapon_set`. |
| Items (41) | Gold (item 1), weapons across 3 classes, shields, helms, class armor, boots (all with `paperdollSpriteId` & tints), rings, amulets, arrows, potions, spell scrolls, crafting materials (Crypt Bone, Ashen Ember, Hollow Shard), quest signet, and 2 fixed boss uniques (`Warden's Ashen Crown`, `Warden's Emberheart`). Every item has a 32×32 `MORGANA` icon (`assets/icons/1..41.png`). |
| Action Combat & Spells | `combat.module = "action"` with 15 `action_skills` (3-hit combos, heavy skills, invulnerable dodges, Vanguard Guard + Riposte) plus 7 spells (Fireball, Mending Light, Frost Nova, Rekindle, Shadow Bolt, Ashen Slam, Crypt Nova) backed by 4 projectiles and 6 five-frame animation strips. |
| Quests, Shops & Crafting | 3 chained Floor 1 quests via Commander Vael (`event_001.json`), 4 economy-safe shops, and 6 anywhere crafting recipes. |

## Art Pipeline
`.\gradlew.bat :tilegen:run "--args=roguelike export --game games/roguelike"` runs `RoguelikeGameExporter` (`:tilegen`) to write the 91 owned art files (`tilesets/1..7`, `sprites/1..3, 10..17, 101..106`, `paperdolls/1..16`, `icons/1..41`, `projectiles/1..4`, `animations/1..6`). Content JSON in `data/` and `game.json` are edited directly or in the F1 editors.

## Tests
`RoguelikeReferenceGameTest` (`:server-app`, on a temp copy) and `RoguelikeGameExporterTest` (`:tilegen`, on a temp dir). `.\gradlew.bat check` validates content, scripts, and art.
