---
type: Playbook
title: "Write a Server Plugin"
description: "How to build, install, check and manage a Kotlin plugin jar for the dedicated server: manifest, entry class, hooks, modules, lifecycle and limits."
tags: [playbook, plugins, hooks, modules]
code: ["plugin-example/src/main/kotlin/com/morgana/example/HelloPlugin.kt", "server-app/src/main/kotlin/com/morgana/server/plugins/PluginStorage.kt", "server-app/src/main/kotlin/com/morgana/server/plugins/PluginApi.kt", "server-app/src/main/kotlin/com/morgana/server/plugins/PluginManifest.kt", "server-app/src/main/kotlin/com/morgana/server/plugins/PluginManager.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/OptionalModules.kt"]
symbols: ["PluginStorage", "MorganaPlugin", "PluginContext", "PluginManifest", "PluginManager", "ModuleRegistry", "ModuleFlags"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-12T18:00:00Z }
stale_after: 2027-05-11T00:00:00Z
---

# Write a Server Plugin

In the project's vocabulary ([ADR 0018](/decisions/0018_core_systems_plugins_templates_terminology.md), accepted) a jar like this is an **add-on system**: it has an id, a switch, hooks, storage and windows like a built-in system, but it runs in the server layer after the simulation has acted, so it can react and refuse but not change rules. "Plugin" is moving to mean sandboxed Lua.

A plugin is a jar of Kotlin or Java code the dedicated server loads at start. Use it when data files, event pages and Lua scripts are not enough. **Plugins are trusted code with full access to the server process**: install only jars you trust. They load only from the operator's own folder, never from a world pack.

## 1. The entry class
```kotlin
class StockMarket : MorganaPlugin {
    override fun onEnable(context: PluginContext) {
        val module = context.registerModule("market", "Stock market", "Buy and sell shares")   // "stock-market:market"
        context.registerHook(object : MorganaServerHook {
            override val pluginId = "stock-market"
            override fun onNpcKilled(npcInstanceId: String, npcId: Int, mapId: Int, killer: CharacterState?, sim: WorldSimulation) {
                if (context.isModuleEnabled(module)) context.logger.info("a kill on map {}", mapId)
            }
        })
    }
    override fun onDisable() {}
}
```
It needs a public no-argument constructor. Hooks are the [hook API](/server/hook_api.md): notifications after the simulation acted, plus `onBeforeAction`, which can refuse a player's request (`HookDecision.Deny("reason")`). `context.messagePlayer(characterId, text)` shows a player a message. Everything registered through the context is removed when the plugin is disabled. `context.dataDir` is the plugin's own folder (`plugins/<id>/`) for files. `context.storage` is a small saved key-value store private to the plugin (`get`, `set`, `remove`, `keys(prefix)`; text only, written to `storage.json` after every change; keys up to 128 characters, values up to 64 KB, 4 MB in all). Build per-character keys yourself, such as `"gold:" + character.id`. Event pages reach a plugin through the `kotlin_hook` command, which arrives as `onCustomEventHook`.

## 2. The manifest
Put `morgana-plugin.json` at the root of the jar:
```json
{ "id": "stock-market", "name": "Stock Market", "version": "1.0.0", "apiVersion": 1,
  "main": "com.example.StockMarket", "depends": [] }
```
`id` is 2-32 characters (lowercase letters, digits, `-`, `_`, starting with a letter). `apiVersion` must equal the server's plugin API (`PLUGIN_API_VERSION`, now 1); a jar built for another version is refused with a message. `depends` lists plugin ids that must start first.

## 2b. Show the player something
A plugin can show a window it describes with data, drawn by the standard client, and exchange messages with it (needs a client with the `plugin_ui` capability; all current clients have it, older ones simply do not see these and the calls return false):
```kotlin
context.openWindow(character.id, PluginWindowDto(
    id = "stock-market:board", title = "Stock board",
    lines = listOf("Prices change every minute."),
    rows = listOf(PluginWindowRow("ACME", "12 gold")),
    inputs = listOf(PluginWindowInput("qty", "How many", hint = "1", maxLength = 6)),
    buttons = listOf(PluginWindowButton("buy-acme", "Buy ACME"))))
// the press arrives as: onPluginAction(character, "stock-market:board", "buy-acme", mapOf("qty" to "3"), sim)
```
Opening a window with an id that is already open replaces it, so re-send it after a change. Window ids and message channels must start with `<pluginId>:`. The table of `rows` scrolls inside the window once it has more than 8 rows. `inputs` are text fields (hint, starting text, a length limit); their text comes with every button press as `values`, with unknown inputs dropped and each text cut to its limit, but it is still player-typed text, so validate it. A window the player has typed into is not rebuilt unless its description changes, so re-sending an identical window keeps what they typed. Limits: 30 lines of 300 characters, 60 rows, 6 inputs, 12 buttons, short titles and labels (a bigger window throws `IllegalArgumentException`). The server only accepts a button press for a window it opened for that player and an enabled button, and tells hooks when the player closes the window (`onPluginWindowClosed`). `context.sendMessage` and the hook `onPluginMessage` carry free-form text messages in either direction (the client keeps `ClientWorldState.onPluginMessage` for them). Windows close when the player logs out or the plugin is disabled. `plugin-example/` is a complete working example (`:plugin-example:jar` builds `hello-plugin.jar`); the browser smoke test installs it and checks the window, the button and the server closing it.

## 3. Install and check
Copy the jar into `<data dir>/plugins/` (default `server-data/plugins/`) and run
```
.\gradlew.bat :server-app:listPlugins "-PcontentArgs=--dir server-data"
```
to see each jar, its id, version and whether the manifest is usable (nothing is started; exit 1 if any is not). Start the server; its log says which plugins were enabled and why any failed. A plugin that throws while starting is undone and the others carry on.

## 4. Modules
A module a plugin registers is switched in `server.json` like the built-in ones, using the namespaced id:
```json
{ "modules": { "stock-market:market": false } }
```
A key of the form `plugin:module` is accepted even before the plugin is installed. The plugin reads the switch with `isModuleEnabled`; the engine does not enforce it for you.

## 5. Lifecycle and limits
Maintainers (permission `SERVER_MAINTENANCE`) manage plugins from chat: `/plugins` lists them with their state, and `/plugin enable|disable|reload <id>` switches one (disabling takes dependants down first; reload re-reads the jar). Each jar has its own class loader, parent-first. The desktop client's embedded server loads no plugins. Not available yet: new event command types (use `kotlin_hook`), per-character storage that travels with the character, permissions. See the [extensibility map](/reference/extensibility_map.md). Tests: `PluginManagerTest`, `ModuleRegistryTest`.
