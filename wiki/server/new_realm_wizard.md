---
type: Concept
title: "New Realm Wizard"
description: "The guided flow (and matching CLI) for creating a realm: pick a starting template and which optional systems are on."
tags: [server, client, content]
code: ["server-app/src/main/kotlin/com/morgana/server/worlds/RealmBlueprint.kt", "server-app/src/main/kotlin/com/morgana/server/worlds/WorldLibrary.kt", "client-core/src/main/kotlin/com/morgana/client/realms/RealmWizardModel.kt", "client-core/src/main/kotlin/com/morgana/client/ui/NewRealmWizardWindow.kt", "client-core/src/main/kotlin/com/morgana/client/LocalWorldHost.kt", "client-desktop/src/main/kotlin/com/morgana/client/desktop/DesktopLauncher.kt"]
symbols: ["RealmBlueprint", "RealmTemplate", "WorldLibrary", "RealmWizardModel", "NewRealmWizardWindow", "createRealm", "FeaturePreset"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-08T12:00:00Z }
stale_after: 2027-05-07T00:00:00Z
---

# New Realm Wizard

Creating a realm used to mean typing a name and getting the starter town. The wizard lets someone choose what the realm starts from and which optional systems it has, then drops them into a ready-to-play world.

## Scope: nothing new in the engine
The wizard is a **front end over what the engine already does**. It chooses among existing content templates and the existing [optional module](/server/optional_modules.md) switches. It adds no gameplay, no new world layout and no new rules; options such as seamless-scrolling worlds are deliberately not offered until the engine supports them (the intended next step is a realm profile, `realm.json`, which the wizard would then write; see the log for the plan).

## What it offers
| Step | Choices |
| :--- | :--- |
| 1. Name and starting point | The realm name (1 to 40 characters), and a template (built-in or auto-discovered from `games/<folder>/game.json`). |
| 2. Features | A **genre starter** (one per genre: Online RPG, Roguelike and dungeon crawler, Farming and life sim, Role-play, Action), or a preset (**Full online world**, **Co-op adventure**, **Solo story**, **No combat**, **Modern sprites**), a **Peaceful realm (no combat or spells)** toggle (`noCombat`), and the individual systems, listed under their genre heading with **All on** and **All off** buttons per heading and a one-line description each (`OptionalModule.description`). The list starts from what the chosen template switches off by itself. |
| 3. Review | A plain-language summary, then **Create Realm**. |

Templates (`RealmTemplate`, a data class with built-in constants and auto-discovery via `WorldLibrary.findGameDirs`):
- Any folder under `games/<folder>/` containing both `game.json` and `data/` is **auto-discovered** (`id`, `name`, and `description` read from its `game.json`) and **copied** (`game.json`, `data/`, `scripts/`, `assets/`) into the realm, so the owner can edit it without touching the shared folder. A game whose id equals a built-in template's (`games/blank` is the Blank template's scaffold) is not listed twice. (The Morgana Classic and Hearthvale templates were removed with those games on 2026-11-13.)
- `blank`: **Blank Game**, a minimal custom game project from `GameScaffolder` (own `game.json`, one map, one class, one script).
- `pack`: **Import a World Pack**. CLI only for now (the in-game wizard has no file chooser).

## How it works
- `RealmBlueprint(name, template, disabledModules, pack, ..., enabledModules)` holds the choices; `problems()` is the cheap, I/O-free validation the UI and CLI both use.
- **A template declares its systems.** (That now covers the engine's rule systems too: a template's `server.json` can switch off `arena`, `guildWar`, `threat` and the rest of the [system list](/server/optional_modules.md).) A game folder under `games/` may hold a `server.json`; the built-in systems it switches off (`"modules": { "guilds": false }`) are written to the new realm's `server.json`, and the wizard's own switched-off systems are added on top (`WorldLibrary.templateDisabledModules`). Nothing else in that file is copied (limits, TLS and admin settings belong to the operator), and nothing is written when no system is off. The wizard's checkboxes do not yet show a template's defaults; switch systems back on in the F1 Systems panel after creating the realm.
- **Genre starters** are `RealmWizardModel.GENRES`, one per genre in `SystemTags.TITLES`, built from the system tags: the systems tagged for the genre stay on and every other system is switched off (`SystemGroups.disabledKeysFor(genre)`), and the description lists what stays on. They are starting points: adjusting one system leaves the starter behind (`activePreset()` becomes null). The same choice from the command line is `world-new --genre roguelike`.
- **The feature list follows the template.** `RealmTemplateInfo.disabledModuleKeys` carries the systems a template switches off (read by `WorldLibrary.templateDefaults`); choosing a template in step 1 resets the switches to those defaults. The creator can switch any of them back on: `RealmSpec.enabledModuleKeys` (`templateDefaults - what stays off`) becomes `RealmBlueprint.enabledModules`, and `WorldLibrary.create` writes `(template switches - enabled) + the creator's switches`. On the command line that is `world-new --enable arena,guilds` (and `--disable`, applied last, so a system named in both stays off).
- `WorldLibrary.create(blueprint)` builds the realm folder under `worlds/<slug>/`: the template's content, `server.json` with the switched-off modules (written only when something is off), then a full `ContentLoader` validation. Any error, including a validation error, removes the half-made folder. `create(name, pack)` still works and delegates here.
- `RealmWizardModel` (`:client-core`, no GUI code) holds the steps, rules, presets and review text and is unit-tested; `NewRealmWizardWindow` only draws it. The desktop hub's [Local Worlds tab](/client/local_worlds.md) shows **New Realm Wizard...** when `LocalWorldHost.realmTemplates` is non-empty (the desktop `EmbeddedWorldHost`); the old one-line "New World" box remains the fallback for hosts without templates. The web and Android clients have no local hosting and so no wizard.
- Data first, GUI optional: everything the window does is also available as `.\gradlew.bat :server-app:newWorld -PcontentArgs="--name Camp --template blank --disable trading,guilds"` (`--template starter|blank|pack|<discovered game id>`, `--pack`, `--genre rpg|roguelike|farming|roleplay|action`, `--enable a,b`, `--disable a,b`).

## Tests
`NewRealmWizardTest` (every template boots; modules written and enforced; failures leave nothing behind; CLI flags), `RealmWizardModelTest` (steps, presets, genre starters, groups, template defaults, spec, summary), `EmbeddedWorldHostTest` (wizard through the desktop host).

Related: [world library](/server/world_library.md), [optional modules](/server/optional_modules.md), [blank game](/content/blank_game.md), [content pipeline](/content/content_pipeline.md).

See also [ADR 0018](/decisions/0018_core_systems_plugins_templates_terminology.md) (accepted) for how templates, systems, scripts and plugins are proposed to be named.
