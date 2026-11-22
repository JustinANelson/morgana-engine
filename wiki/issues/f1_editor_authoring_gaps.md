---
type: Known Issue
title: "F1 Editors Cannot Author Everything a Game Needs"
description: "Parts of a game that exist only as JSON today: game.json, classes, floor recipes and scaling, action skills and weapon sets, server.json modules. A full roguelike (or any game) still needs a text editor for these."
tags: [f1-editor, content, roguelike, gap]
code: ["client-core/src/main/kotlin/com/morgana/client/editor/forms/NpcForm.kt", "client-core/src/main/kotlin/com/morgana/client/ui/editor/SettingsPanel.kt", "client-core/src/main/kotlin/com/morgana/client/editor/MapPropertiesForm.kt", "core-domain/src/main/kotlin/com/morgana/domain/validation/ContentValidation.kt"]
symbols: ["ContentCollection", "NpcForm", "MapPropertiesForm", "GameDefinition"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-21T12:00:00Z }
stale_after: 2027-05-20T00:00:00Z
---

# F1 Editors Cannot Author Everything a Game Needs

**Updated 2026-11-21: most of this is closed.** The editors now have a **Game rules** panel (`game.json`), a **Classes** panel, a **Dungeon** panel (floor recipe, scaling, tiers) and boss, revive and unique-item fields; the guide is `docs/BUILD_A_ROGUELIKE_IN_F1.md`. Later the same day the **Systems** (`server.json` module switches, live), **Action skills** and **Weapon sets** panels closed the rest. Still open: live-applying game rules (they apply after a restart) and the operator-only parts of `server.json` and `game.json` (network, TLS, limits, weather zones). The table below is the original finding.

Found 2026-11-21 while checking whether the co-op roguelike (Lanternfall) can be made entirely in the F1 editors ("data first, GUI optional" means every piece of content must be reachable both ways). Items, NPCs (now including boss encounters), spells, shops, resources, recipes, quests, events, common events, animations, maps and tiles all have forms. These do **not**:

| Gap | Today | Why it matters for a roguelike |
| :--- | :--- | :--- |
| **`game.json`** (attributes, vitals, formulas, loot rarities and affixes, `ui`, `sprint`, `jump`, `ghost`, `paragon`, `bestiary`, `onboarding`, ...) | Text file only; the server reads it at start and `/reload` does not re-read it | Loot rarities, the death rule and the stamina vital are the heart of the genre. Needs a typed editor, a save packet and a restart notice (or a live swap of `GameDefinition`). |
| **Classes** (`ContentCollection.CLASSES`) | No form; the catalog carries them but only JSON edits them | A new game's first job is its classes (starting gear, spells, stats). |
| **Floor recipe** (`MapDefinition.generator`) | The map editor keeps it on save but has no form | Room counts, monster and loot tables, the boss, stairs and exit are the dungeon. |
| **Dungeon scaling and difficulty tiers** (`MapDefinition.scaling`) | JSON only | Party-size scaling and tiers. |
| **Action skills and weapon sets** (`ACTION_SKILLS`, `WEAPON_SETS`) | No forms | Action-combat games. |
| **`server.json` modules** (`parties`, `instancing`, ...) | Text file; the admin panel only reads them | Operators toggle co-op systems by hand. |

Done 2026-11-21: the NPC form has a **Boss encounter** section (phases, adds, telegraphed attacks and lockout as text lines, round-trip tested in `ContentFormsTest`).

## Suggested order
1. Classes form (small, same shape as the item form).
2. Floor recipe and scaling groups in the map properties panel.
3. A `game.json` editor: a section per optional system with the generic field widgets, a `c_admin_save_game` packet, validation by `GameDefinitionValidator`, and "applies after restart" until the definition can be swapped live.
4. Action skill and weapon set forms; `server.json` module toggles in the admin panel.
