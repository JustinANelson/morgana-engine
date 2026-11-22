---
type: Reference
title: "Kotlin Hook API"
description: "Typed server plugin callbacks (login, items, spells, kills, deaths, chat, custom events) and where game rules now live."
tags: [hooks, scripting, plugins]
code: ["server-app/src/main/kotlin/com/morgana/server/KotlinHookApi.kt"]
symbols: ["MorganaServerHook", "PlayerAction", "HookDecision", "EventHookRegistry", "onCustomEventHook", "onItemUsed", "onSpellCast", "onNpcKilled", "onPlayerDeath", "onPlayerChat"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Kotlin Hook API

```kotlin
interface MorganaServerHook {
    val pluginId: String
    fun onPlayerLogin(character, sim) {}
    fun onPlayerLogout(character, sim) {}
    fun onCustomEventHook(character, hookId, payload, sim) {}
    fun onItemUsed(character, itemId, sim) {}
    fun onSpellCast(character, spellId, sim) {}
    fun onNpcKilled(npcInstanceId, npcId, mapId, killer: CharacterState?, sim) {}
    fun onPlayerDeath(character, sim) {}
    fun onPlayerChat(character, channel, message, sim) {}
}
```
`EventHookRegistry.register(hook)` replaces any hook with the same `pluginId`; `unregister(hook)` removes one. The coordinator owns the registry (`hookRegistry`). Jars in `<data dir>/plugins/` register their hooks through `PluginContext.registerHook` ([plugin guide](/playbooks/write_a_server_plugin.md)).

## Vetoing an action: `onBeforeAction`
```kotlin
override fun onBeforeAction(character: CharacterState, action: PlayerAction, sim: WorldSimulation): HookDecision =
    if (action is PlayerAction.Chat && "spoiler" in action.message) HookDecision.Deny("No spoilers here.") else HookDecision.Allow
```
Asked **before** the server acts on a player's request, in `handleAuthenticatedPacket`, after the built-in checks (input limits, switched-off modules). `Deny(reason)` drops the request: nothing runs, nothing is recorded for [replay](/systems/deterministic_replay.md), and the reason (if not blank) is shown to the player as a server message. The first denial wins; a hook that throws is logged and skipped, so the action is allowed. Hooks decide only; do not change the world from here. `PlayerAction` covers `Chat` (slash commands are not offered), `UseItem`, `DropItem`, `CastSpell`, `Attack`, `ShopBuy`, `ShopSell`, `Craft` and `Custom`; movement and UI plumbing are not offered. A plugin can answer a player with `PluginContext.messagePlayer(characterId, text)` (coordinator `messagePlayer`). Test: `PluginVetoHooksTest`.

## Plugin windows and messages
`onPluginAction(character, windowId, buttonId, values, sim)`, `onPluginWindowClosed(character, windowId, sim)` and `onPluginMessage(character, channel, payload, sim)` carry what a player does in a window a plugin opened or sends on a plugin channel; the plugin shows windows with `PluginContext.openWindow` ([plugin guide](/playbooks/write_a_server_plugin.md)). The server only reports presses of enabled buttons of windows it opened for that player. Every hook sees every message, so check the `pluginId:` prefix of the id or channel.

## Callbacks
All the callbacks below are **notifications fired after the simulation applied the action**; only `onBeforeAction` above can refuse. Game rules are configured through `GameDefinition`, content, or event pages.

| Callback | Fired when | Source |
| :--- | :--- | :--- |
| `onPlayerLogin` / `onPlayerLogout` | after login / before removal | coordinator |
| `onCustomEventHook` | event page runs `kotlin_hook(hookId, payload)` | `CustomHookRequested` |
| `onItemUsed` | equip, consume, or learn a spell from a scroll succeeded | `ItemUsed` |
| `onSpellCast` | a cast succeeded (resources spent, cooldown started) | `SpellCast` |
| `onNpcKilled` | an NPC died; `killer` is null if no player (or they logged out) | `NpcKilled` |
| `onPlayerDeath` | a player died (before they appear at the respawn point) | `PlayerDied` |
| `onPlayerChat` | a non-slash chat message (`/...` commands are not chat) | coordinator `handleChat` |

The simulation emits the four new events (`ItemUsed`, `SpellCast`, `NpcKilled`, `PlayerDied`) from `InventorySystem` and `CombatSystem`; `dispatchEvents` maps them to hooks ([simulation events](/domain/simulation_events.md)). Failed attempts (cooldown, missing item) emit nothing.

## Formulas and Game Rules
Combat, stats, vitals, and progression formulas are configured via `GameDefinition` (read from `game.json` or the engine default `BlankGame`; see [stats and formulas](/domain/stats_and_formulas.md)). Plugins no longer need ad-hoc code formula overrides.

## Isolation and caveats
Every dispatch is wrapped; an exception in one plugin is logged with the plugin id and never stops other plugins or the game tick.
The live `WorldSimulation` is passed to hooks: use its public methods. Changes a plugin makes that way are **not recorded** by [replay](/systems/deterministic_replay.md), and a replay of a session recorded with formula overrides is only valid with the same overrides installed.
Plugins cannot yet register new event commands. Tests: `Phase14ScriptingTest` (events, formulas), `Phase14HookApiTest` (dispatch, isolation, override composition), `SecurityHardeningTest` (custom hooks).

For what plugins can and cannot change today, and which game rules are still hard-coded, see the [extensibility map](/reference/extensibility_map.md).
