---
type: Reference
title: "Extensibility Map: What Can Be Changed Without Editing the Engine"
description: "Verified inventory of today's extension points (content, events, hooks, formulas, modules) and of the game rules still hard-coded in the engine, with coupling counts."
tags: [plugins, scripting, extensibility, architecture]
code: ["server-app/src/main/kotlin/com/morgana/server/KotlinHookApi.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/GameDefinition.kt", "core-domain/src/main/kotlin/com/morgana/domain/replay/ReplayModels.kt", "core-domain/src/main/kotlin/com/morgana/domain/event/QuestAndEventModels.kt", "core-domain/src/main/kotlin/com/morgana/domain/map/MapModels.kt", "core-domain/src/main/kotlin/com/morgana/domain/stats/StatsAndVitals.kt", "core-domain/src/main/kotlin/com/morgana/domain/validation/ContentValidation.kt", "shared-protocol/src/main/kotlin/com/morgana/protocol/Packets.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/OptionalModules.kt", "server-app/src/main/kotlin/com/morgana/server/plugins/PluginManager.kt"]
symbols: ["MorganaServerHook", "EventHookRegistry", "GameDefinition", "SimInput", "EventCommand", "TileAttribute", "StatusEffectType", "ContentCollection", "ServerUiWindowType", "OptionalModule", "VitalState"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T21:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Extensibility Map

Written for the plugin and scripting investigation (design: `docs/PLUGIN_ARCHITECTURE.md`; direction decided in [ADR 0013](/decisions/0013_engine_game_split_and_lua_scripting.md)). Everything here describes the code as it is. Counts are matching lines in `src/main` (a rough measure of how widely a concept is baked in, not an exact usage count).

## What can be changed today

| Mechanism | What it covers | Limits |
| :--- | :--- | :--- |
| **JSON content** ([content pipeline](/content/content_pipeline.md)) | 11 collections: maps, classes, items, NPCs, spells, shops, resources, recipes, quests, events, animations. Validated, hot-reloaded, broadcast. | The set of collections is the `ContentCollection` enum; a new kind of content needs engine changes. |
| **Event pages** ([events and quests](/systems/events_quests.md)) | Page conditions (8 fields) and 15 commands (`dialogue`, `give_item`, `take_item`, `give_xp`, `set_switch`, `add_variable`, `start_quest`, `complete_quest`, `warp_player`, `heal_player`, `open_shop`, `open_bank`, `open_crafting`, `play_animation`, `kotlin_hook`), 3 trigger types. | `EventCommand` is a sealed interface: no new commands or conditions without engine changes. `kotlin_hook` is the escape hatch (one way, no return value). |
| **Kotlin hooks** ([hook API](/server/hook_api.md)) | 8 notification callbacks (login, logout, custom event, item used, spell cast, NPC killed, player death, chat). | The 8 callbacks are notifications that run after the simulation applied the action. One more, `onBeforeAction`, can refuse a player's request (chat, item use or drop, spell, attack, shop, craft, custom action) before it runs, and `PluginContext.messagePlayer` shows a player a message; there is still no way to open UI or change the outcome of an action. A hook can still mutate the live `WorldSimulation` through its public mutable maps (`players`, `items`, ...), and those changes bypass the replay input log. |
| **Game rules in `game.json`** ([game definition](/domain/game_definition.md)) | Attributes, vitals and their formulas, the XP curve, damage, crit and dodge formulas, combat constants and the combat module; Lua can replace combat. | Status effects, item types, tile attributes and the other enums below are still engine vocabulary. |
| **Optional modules** ([optional modules](/server/optional_modules.md)) | 7 built-in switches in `server.json` plus any module a plugin registers (`ModuleRegistry`, ids written `plugin:module`). | The built-ins are still the `OptionalModule` enum, which the engine's own code checks; a plugin module is only a switch plugin code reads with `isModuleEnabled`. |
| **Kotlin plugins** ([plugin guide](/playbooks/write_a_server_plugin.md)) | Jars in `<data dir>/plugins/` with a manifest, a lifecycle (enable, disable, reload), dependencies, an API version check; they register hooks and modules. | Trusted code, no sandbox and no permission model; hooks are still notifications only. |

**Plugin loading exists for the dedicated server** (`PluginManager`, see the [plugin guide](/playbooks/write_a_server_plugin.md)). Plugins have their own saved key-value store (`PluginStorage`) and maintainers manage them with `/plugins` and `/plugin enable|disable|reload`. Plugins can show data-described windows and exchange messages with the client ([plugin guide](/playbooks/write_a_server_plugin.md)). Windows have text lines, a scrolling label/value table, text inputs and buttons. Not covered yet: images and item slots, new event command types (`kotlin_hook` is the route), per-character plugin data on `CharacterState`, and any permission model. The desktop client's embedded server loads no plugins.

## What is hard-coded

| Rule or vocabulary | Where | Coupling (core-domain / server / client / persistence) |
| :--- | :--- | :--- |
| Status effects (11 types: poison, burn, bleed, regen, stun, root, slow, haste, shield, buff, debuff) and what each does | `StatusEffectType` enum + tick code | 7 / 0 / 2 / 3 |
| Item types (15), weapon styles | enums | `ItemType` 16 / 0 / 1 / 16 |
| Equipment slots (default 9, a game lists its own) | `equipmentSlots` in `game.json`; `EquipmentSlot` id value class | data-defined since 2026-11-16 (`WEAPON` is required; `SHIELD` and `AMMO` keep their rules) |
| Spell target types (6), NPC behaviors, quest objective types (6), map morals | enums with behavior in the systems | 10 / 9 / 12 / 2 core-domain lines respectively |
| Tile attributes (blocked, directional block, warp, item spawn, resource, shop, heal trap, ...) | sealed `TileAttribute` | 29 / 0 / 32 / 42 |
| Map layers (6) | `MapLayer` enum, renderer | fixed |
| Combat constants still written inline: the fallback respawn tile `(12, 9)` when a class is missing, death drops on `OPEN_PVP` | `CombatSystem.kt`, `InstanceSystem.kt` | not configurable. *Moved to `game.json` `combat` on 2026-11-14:* default attack speed, default NPC respawn, loot owner protection, ground item lifetime, party XP bonus (natural regeneration is a per-vital `regen`) |
| Tick order (doors, resources, ground items, projectiles, players, NPCs) | `WorldSimulation.stepTick` | fixed |
| Slash commands (`warpto`, `item`, `kick`, `heal`, `setaccess`, ...) and 14 permission flags | `AdminSystem`, `PermissionFlag` | fixed |
| Content collections | `ContentCollection` enum | 39 / 11 / 0 / 26 |
| Protocol: 36 client packets, 19 server packets, all sealed; 5 server-driven UI windows (`ServerUiWindowType`: shop, bank, crafting, dialogue, F1 panel) | `Packets.kt` | plugins can send a namespaced message and describe a window (`PluginWindowDto`, capability `plugin_ui`); the five fixed windows remain |
| Client art and sound are generated in client code (`ProceduralRetroAssets`, `RetroSoundEngine`) | `:client-core` | the game's look is part of the client |

## Structural facts that constrain any design

* **One lock.** Packet handling, the tick, and hook dispatch all run inside `MorganaWorldCoordinator`'s `@Synchronized` methods ([ADR 0004](/decisions/0004_single_synchronized_coordinator.md)). A slow hook or script stalls the 50 ms tick for everyone.
* **Replay records inputs, not code.** Every simulation mutation goes through a sealed `SimInput` (spawn, move, attack, cast, use slot, shop, trade, admin command, ...) that `MorganaWorldCoordinator.runInput` records ([deterministic replay](/systems/deterministic_replay.md)). Anything a plugin changes outside that pipeline is invisible to replay.
* **The domain is pure.** `:core-domain` has no I/O and no threads; randomness comes from a seeded `RandomSource` ([determinism](/domain/determinism_and_rng.md)). `ScriptRuntime` shows the accepted pattern: an interface (port) in the domain, an adapter in another module.
* **Per-character extension space is tiny.** `CharacterState` carries `switches` and `variables` maps for event pages and nothing else that a plugin can own. There is no namespaced plugin storage.
* **Timers are absolute ticks of one run** (`lastMoveTick`, `lastAttackTick`, `spellCooldowns`); the simulation filters them on spawn ([movement](/systems/movement.md)). Plugin data that stores ticks has the same hazard.
