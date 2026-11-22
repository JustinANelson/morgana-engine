---
type: Simulation System
title: "Script Host and Sandbox"
description: "Sandboxed Lua scripting runtime powered by Luak with deterministic instruction budgets, memory ceilings, world queries, and declarative effects."
tags: [scripting, lua, luak, sandbox, simulation]
status: stable
code: ["core-domain/src/main/kotlin/com/morgana/domain/script/ScriptSystemHooks.kt", "core-domain/src/main/kotlin/com/morgana/domain/systems/ScriptedSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/script/ScriptRuntime.kt", "core-domain/src/main/kotlin/com/morgana/domain/script/ScriptContentTree.kt", "script-luak/src/main/kotlin/com/morgana/script/luak/LuakScriptRuntime.kt", "script-luak/src/main/kotlin/com/morgana/script/luak/LuakSandbox.kt"]
symbols: ["ScriptRuntime", "LuakScriptRuntime", "LuakSandbox", "ScriptEffect", "ScriptBudget", "ScriptedSystem", "ScriptMonsterHook", "ScriptRequirementHook", "ScriptFightHook", "ScriptPluginManifest", "ScriptTickHook", "ScriptItemUseHook", "ScriptActionGateHook", "ScriptInterruptHook", "ScriptCombatObserverHook", "ScriptKillHook", "ScriptedTicks", "ScriptWarpHook", "ScriptedWarps"]
generated: { by: antigravity/agent, at: 2026-11-08T15:33:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Script Host and Sandbox

The scripting host allows game rules, custom slash commands, custom event commands, conditions, and formulas to be authored in sandboxed Lua scripts ([ADR 0013](/decisions/0013_engine_game_split_and_lua_scripting.md)).

## Architecture and Boundaries

```
[ :core-domain ]           ScriptRuntime port, ScriptWorldView, ScriptEffect, ScriptRegistry
       ^
       | implements
[ :script-luak ]           LuakScriptRuntime, LuakSandbox, LuakScriptValidator (Luak vendored)
       ^
       | wires into
[ :server-app ]            MorganaWorldCoordinator, ContentTool (validateScripts)
```

1. **Strict Type Isolation**: Zero Luak classes (`Globals`, `LuaValue`, etc.) leak outside of `:script-luak`. The simulation and coordinator interact with scripting strictly through the engine-owned `ScriptRuntime` interface.
2. **Determinism**: Scripts have no access to system clocks or native unseeded `math.random`. Time is read as `world.tick`; pseudo-randomness is accessed via `world.random(min, max)` backed by the simulation's seeded `RandomSource`.
3. **Immutable Queries**: `world.get_player(id)`, `world.get_npc(mapId, instanceId)`, `world.get_map(mapId)`, and `world.get_entities_in_range(...)` return immutable snapshots of entity and world state.
4. **Declarative Effects**: Scripts cannot directly mutate simulation memory. Mutations (damage, heal, give/remove items, warp, message, floating text, animation) are returned as declarative `ScriptEffect` items and applied via `SimInput.ExecuteScriptEffect`, preserving full deterministic replayability.

## Plugins and the system seams

A Lua **plugin** is a script module that takes part in the kernel's [system seams](/systems/system_seams.md) ([ADR 0018](/decisions/0018_core_systems_plugins_templates_terminology.md)). These registrations feed it (`docs/API_REFERENCE.md` has the Lua side):

| Lua | Port type (`core-domain/.../script/ScriptSystemHooks.kt`) | Seam it reaches |
| :--- | :--- | :--- |
| `register.plugin{ id, requires, replaces, ... }` | `ScriptPluginManifest` | gates every hook of the module on the systems it requires, and takes the engine systems in `replaces` out of play while it runs (`systemOn`) |
| `register.requirement(fn)` | `ScriptRequirementHook` | `RequirementGate` |
| `register.may_fight(fn)` | `ScriptFightHook` | `CombatArbiter.mayFight` |
| `register.monster{ ... }` | `ScriptMonsterHook` | `MonsterBehavior` (`holds_still`, `variant`, `retarget`, `step`, `may_be_elite`, `kill_lockout`) |
| `register.tick(fn)` / `register.tick{ stage, every, run }` | `ScriptTickHook` | `TickParticipant` (two `ScriptedTicks` objects, one per stage) |
| `register.item_use{ items, use }` | `ScriptItemUseHook` | `ItemUseHandler` |
| `register.action_gate{ activities, blocks }` | `ScriptActionGateHook` | `ActionGate` |
| `register.on_interrupt(fn)` | `ScriptInterruptHook` | `ActivityInterrupter` |
| `register.combat_observer{ after_attack, after_struck }` | `ScriptCombatObserverHook` | `CombatObserver` |
| `register.on_kill(fn)` | `ScriptKillHook` | `KillObserver` |
| `register.warp(fn)` | `ScriptWarpHook` | `WarpRouter` (`ScriptedWarps`, installed before instancing) |

`LuakScriptRuntime` fills `ScriptRegistry` and knows nothing about seams. `ScriptedSystem` (`core-domain/.../systems/ScriptedSystem.kt`) is the adapter: it implements `RequirementGate`, `CombatArbiter` and `MonsterBehavior`, is installed **last** in every world (so the engine's own systems give their opinion first) and does nothing when no hook is registered. A hook runs only while the systems its plugin `requires` are on (`pluginOf(moduleId).requires` against `sim.modules`). Query hooks run under the formula budget and cannot cause effects; `step` runs under the action budget and its effects go through `applyScriptEffect`, so the game's `capabilities` still limit them. Because a tick hook is re-run on replay and Lua is deterministic, `step` effects replay identically. A failing hook is "no opinion" and counts toward the module's circuit breaker (`MAX_CONSECUTIVE_FAILURES`, 5).

Where the engine is part-way through changing a character (the one just struck, the one being interrupted) `ScriptedSystem` drops effects that would change that character, because the engine stores its own copy a moment later and would overwrite them; every other effect, and effects on the attacker, the killer or the item user, go through `applyScriptEffect` as usual. `ScriptedTicks` runs a tick hook on the world ticks that are a multiple of its period.

Cost to keep in mind: every Lua call builds fresh sandbox globals, so a monster hook with no `npcs` list runs once per monster per tick. Tests: `LuaPluginSeamsTest` (`:script-luak`, real Lua in a real world) and `ScriptedSystemTest` (`:core-domain`, hooks registered in Kotlin).

## Sandbox Security Profile

Configured in `LuakSandbox`:
* **Stripped Globals**: `io`, `os`, `package`, `require`, `debug`, `dofile`, `loadfile`, `load`, `loadstring`, `collectgarbage` are set to `nil`.
* **Text-Only Execution**: Precompiled binary bytecode chunks are rejected (`textonly = true`).
* **Deterministic Instruction Budgets**: Handlers are bounded by instruction counts on the running thread (e.g. 100k for formulas, 500k for commands, 1M for events). Budget exhaustion throws a catchable `LuaError` and cannot be bypassed via `pcall`.
* **Memory Ceilings**: Capped per lane (default 8 MB); runaway allocations trip `not enough memory`.
* **ReDoS Protection**: Pattern-matching functions (`string.find`, `match`, `gmatch`, `gsub`) enforce subject length (<= 4096), pattern length (<= 256), and quantifier caps (<= 3), stopping catastrophic backtracking in microseconds.
* **Address Sanitization**: `tostring` and `error` normalize memory addresses (`table: 0x...` -> `table`) to ensure reproducible logs and error strings.
* **Circuit Breaker**: A module that experiences 5 consecutive failures is automatically disabled to prevent persistent load on the server tick.

## Registries

Modules register hooks during initialization via the global `register` table:
* `register.command(name, description, minAccessLevel, handler)`: slash commands.
* `register.formula(name, handler)`: damage, progression, or stat formulas.
* `register.event_command(commandId, handler)`: custom page event action hooks.
* `register.event_condition(conditionId, predicate)`: custom page event condition predicates.
* `register.action(actionId, handler)`: generic custom player actions routed from `c_action`.
* `register.combat(combatTable)`: hooks for `LuaCombatModule` (`on_attack`, `on_cast`, `on_tick_projectiles`, `on_tick_players`, `handle_action`).

## World API
Exposed on the sandboxed `world` table:
* **Calendar**: `world.clock` is nil unless the game's `game.json` has a [`clock`](/domain/game_definition.md); otherwise a table with `day`, `day_of_season`, `season`, `season_index` (1-based), `year`, `time_of_day` (0..999 permille), `phase` (`night`, `dawn`, `day`, `dusk`), `light` (150..1000) and `text` ("08:24").
* **State Queries**: `world.tick`, `world.random(min, max)`, `world.get_player(id)`, `world.get_npc(mapId, instanceId)`, `world.get_map(mapId)`, `world.get_entities_in_range(mapId, x, y, radius)`.
* **Side-Effect Emission**: `world.damage_player`, `world.damage_npc`, `world.heal_player`, `world.heal_npc`, `world.give_item`, `world.remove_item`, `world.warp_player`, `world.broadcast`, `world.send_message`, `world.floating_text`, `world.animation`, `world.spawn_projectile`, `world.award_xp`, `world.consume_vital`, `world.set_state`.

## Content, State and Pixels (action combat)
Script hooks run in a fresh Lua state every call, so a script cannot keep anything in a variable. Phase A2 of [ADR 0017](/decisions/0017_white_river_online.md) added the pieces a stateful rule such as a combo needs:
* **Script state**: `world.set_state(key, table)` emits `ScriptEffect.SetState` (capability `state`), kept in `WorldSimulation.scriptState` (a sorted map of string maps), read back with `world.get_state(key)` / `world.state_keys(prefix)`. It is part of the replay state hash (only when non-empty, so other games keep their hashes) and is **not saved**: it is rebuilt from play.
* **Content as tables**: `world.get_action_skill(id)` and `world.get_weapon_set(id)` give the [action-combat content](/domain/action_combat_content.md) as Lua tables. `ScriptContentTree` builds them with the content type's own serializer (no JSON library in `core-domain`), so a new field appears in scripts without more code.
* **Pixels**: player, NPC and entity tables have `px` / `py`, the character's centre in pixels, for hitbox math.

## Verification & Tooling

* **Offline Script Validation**: `.\gradlew.bat :server-app:validateScripts -PcontentArgs="--dir <path>"` verifies syntax and forbidden symbols ahead of runtime. Wired into `.\gradlew.bat check`.
* **Hot Reload**: In-game `/reload` or coordinator `reloadContent()` resets script runtimes and re-reads all scripts live from `server-data/scripts`.

