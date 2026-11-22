---
type: Reference
title: "Optional Modules and server.json"
description: "Which systems an operator can switch off, how server.json configures them, and where each switch is enforced."
tags: [modules, config, server]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/OptionalModules.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-app/src/main/kotlin/com/morgana/server/tools/ContentTool.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/CombatSystem.kt", "client-core/src/main/kotlin/com/morgana/client/state/ClientWorldState.kt"]
symbols: ["OptionalModule", "ModuleFlags", "ServerConfig", "moduleOf", "moduleDisabledNotice", "modulesCommand", "disabledModules", "isModuleEnabled"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Optional Modules and `server.json`

In the project's vocabulary ([ADR 0018](/decisions/0018_core_systems_plugins_templates_terminology.md), accepted) an optional module is a **system**: a switchable engine feature with genre tags and a list of systems it requires. The code type and the `server.json` key stay `OptionalModule` and `modules`. **This is the one list of systems**: the 14 feature switches in the first table and the 13 engine rule systems in the second share the enum, the `server.json` map, the F1 Systems panel, the New Realm Wizard, template switches and the names a Lua plugin uses in `requires` and `replaces`.

The Optional Module tier from the feature tiers (`docs/DESIGN.md`) can be switched off per server so a small or focused world is not cluttered with systems it does not want. Core systems (maps, melee/spell combat, items, NPCs, shops, bank, events/quests, chat, `F1` editors) are never switchable.

| Key | Module | What switching it off does | Enforced in |
| :--- | :--- | :--- | :--- |
| `parties` | Parties | Party packets and party chat refused; no shared XP | coordinator, `awardPartyOrSoloExperience` |
| `guilds` | Guilds | Guild packets and guild chat refused | coordinator |
| `trading` | Player trading | Trade packets refused; trade methods return nothing | coordinator, `WorldSimulation` facade |
| `crafting` | Crafting | Craft packets refused; `craftRecipe` returns nothing | coordinator, `WorldSimulation` facade |
| `projectiles` | Projectiles | Bows and directional bolt spells refused (no ammo or mana spent) with a "disabled" floating notice | `CombatSystem` |
| `subTileMovement` | Free pixel movement | **On: every player moves by pixels, diagonals included (a blocked diagonal slides along the open axis). Off: every move is a grid step.** A realm-wide rule, not a per-player choice (there is no HUD toggle) | `WorldSimulation.movePlayer`, `ClientWorldState.movementMode` |
| `softSeams` | Soft map seams | Server skips `SeamView` packets; client ignores seam views and snaps across shared borders instead of gliding | coordinator, `ClientWorldState` |
| `instancing` | Instanced maps | Maps marked `instanced` are shared by everyone, as before ([instanced maps](/systems/instancing.md)) | `InstancingSystem`, through the `WarpRouter` seam |
| `exploration` | World map and fog of war | nothing is tracked, stored or sent; the minimap shows the whole map and the World Map entry and `N` key do nothing | coordinator `updateExploration`, `ClientWorldState.isModuleEnabled` ([exploration](/systems/exploration.md)) |
| `ignore` | Ignore list | `c_ignore` and the `/ignore`, `/unignore`, `/ignorelist` commands refused with a "switched off" message; nothing is filtered | coordinator, `isIgnoredBy` ([ignore list](/systems/ignore.md)) |
| `market` | Player market | `c_market` refused with a "switched off" message; listings are kept in storage but not served, and no expiry mail is sent; the HUD hides the Market menu entry. Listing also needs `mail` on, because sellers are paid by mail | coordinator, `ClientWorldState.isModuleEnabled` ([market](/systems/market.md)) |
| `friends` | Friends list | `c_friend` refused with a "switched off" message; no online or offline notices; the HUD hides the Friends menu entry | coordinator, `ClientWorldState.isModuleEnabled` ([friends](/systems/friends.md)) |
| `mail` | Mail | `c_mail_send` and `c_mail` refused with a "switched off" message; nothing is stored or sent; the HUD hides the Mail menu entry | coordinator, `ClientWorldState.isModuleEnabled` ([mail](/systems/mail.md)) |
| `rolePlayChat` | Role-play chat | `/me`, `/ooc`, `/shout`, `/quiet`, `/narrate` are not recognised; Map and Emote chat go map-wide with no style ([role-play chat](/systems/roleplay_chat.md)) | coordinator |

### Engine rule systems
These are installed through the [kernel seams](/systems/system_seams.md) (each is a `GameSystem` whose `key` is the config key below). All are on by default, and most are inert until the game's rules use them (a `promotion` section in `game.json`, pet monsters in the content). Switching one off removes it from play for the whole realm; a running Lua plugin can also replace it (`register.plugin{ replaces = ... }`), and `WorldSimulation.systemStatus()` shows each system's switch, replacing plugin and whether an object is installed.

| Key | System | What switching it off does |
| :--- | :--- | :--- |
| `rebirth` | Rebirth | spells that need a rebirth are no longer refused; the `Rebirth` event command is skipped |
| `promotion` | Promotion ranks | nothing asks for a rank; the `Promote` event command is skipped |
| `arena` | Arena | no queue, no matches, no team rules; `/arena` and the `ArenaQueue` command are skipped |
| `guildWar` | Guild War | wars never start; poles and gates act like ordinary monsters; `/guildwar` is skipped (needs `guilds`) |
| `guildBank` | Guild bank | deposits, withdrawals, upgrades and access changes do nothing (needs `guilds`) |
| `pets` | Pets | pets are not moved, adopted or levelled; `/pet` is skipped |
| `stalls` | Player stalls | an open stall holds nobody; `/stall` is skipped |
| `durability` | Gear wear | gear stops wearing; the `RepairGear` command is skipped |
| `gearUpgrades` | Gear upgrades | the `UpgradeGear` command is skipped |
| `respec` | Attribute respec | the `RespecAttributes` command is skipped |
| `threat` | Threat table | monsters turn on whoever hit them last; taunts only force the target |
| `instancing` | Instanced maps | *(listed in the first table; it is also an installed system now)* an instanced map is one shared map; no private copies, generated floors, tiers or scaling; `warpedPlayer` follows the map the warp named |
| `bossEncounters` | Boss encounters | bosses act like ordinary monsters: no phases, ground attacks, adds or lockout, and they may roll elite variants |
| `luaPlugins` | Lua plugins | every plugin hook is silent (a kill switch for all of a game's Lua extensions) |

Each row also carries genre `tags` and `requires` (`guildWar` and `guildBank` need `guilds`; the F1 Systems panel warns when a required system is off).

Auction data and weather/lighting have no gameplay yet, so they have no switch. The Android touch UI is a client concern and is not server-switchable.

Each module also carries a one-line `description` (shown by the [New Realm Wizard](/server/new_realm_wizard.md), which can switch modules off when it creates a realm).

## Configuration
`<data dir>/server.json` (also holds `storage.playerData`, `tls`, `limits`, `connections`, and `session`, see [persistence layout](/server/persistence_layout.md); per world, so [library worlds](/server/world_library.md) can differ):
```json
{
  "schemaVersion": 1,
  "modules": { "trading": false, "guilds": false, "softSeams": false },
  "session": { "resume": true, "graceMinutes": 30, "idleSeconds": 75, "combatLogSeconds": 15, "maxCharacters": 3 }
}
```
- A missing file or an unlisted module means **on**, so existing installs are unchanged.
- `session` (`SessionConfig`) configures reconnect token issuance (`resume`, default `true`), token expiry after a disconnect (`graceMinutes`, 1..1440, default `30`), the Netty socket read-idle timeout (`idleSeconds`, 10..3600, default `75`), and the combat-log guard (`combatLogSeconds`, 0..300, default `15`, `0` = off): a player who disconnects within that many seconds of PvP damage stays in the world as a defenceless ghost until the time is up, and re-attaches to it if they return ([session lifecycle](/protocol/session_lifecycle.md)). It only matters where PvP damage can happen. `maxCharacters` (1..10, default `3`) is how many characters one account may hold; with `1` a login goes straight into the account's only character ([characters per account](/server/characters.md)).
- Parsing is strict: unknown module keys, unknown fields, out-of-range `session` values, or another `schemaVersion` make the server refuse to start with a message naming the problem (`ServerConfigException`), so a typo cannot silently leave a module on.
- Loaded once at startup (`MorganaWorldCoordinator` sets `WorldSimulation.modules` and `sessionConfig`); changes need a restart. Not hot-reloaded.
- `admin` (`AdminConfig`: `enabled`, `port`, `bindAddress`, `allowRemote`) turns on the [admin web panel](/server/admin_panel.md); off when absent, and an address other than this machine is refused unless `allowRemote` is true.
- `instances` (`InstancesConfig`: `emptySeconds`, `max`) limits instanced maps; see [instanced maps](/systems/instancing.md).
- `roleplay` (`RolePlayConfig`: `sayRadius`, `quietRadius`, `shoutReachesNeighbors`) sets how far role-play speech carries; see [role-play chat](/systems/roleplay_chat.md).
- `areaOfInterest` (`AreaOfInterestConfig`: `enabled` (default false), `cellTiles` 8..200 (default 30), `radiusCells` 1..5 (default 1)) cuts each map into square cells so a player is sent only the changes in their own cell and the cells around it. `AreaOfInterest` (`server-app`, pure, `AreaOfInterestTest`) holds the arithmetic; `MorganaWorldCoordinator.broadcastNear` applies it to player, NPC, resource, floating-text and animation packets. An entity that moves is announced to the viewers of both its old and new tile, and when a player's own cell changes `syncViewerArea` sends the current state of everything in the cells they now cover, so nothing stays stale. Ground items, projectiles, chat, sounds and the full snapshot at map entry still go to the whole map. Halcyon Reach turns it on.
- Tool: `.\gradlew.bat :server-app:modules` shows the state; `-PcontentArgs="--disable trading,guilds"` / `"--enable trading"` edits the file.

## Genres and grouping
The F1 Systems panel and the [New Realm Wizard](/server/new_realm_wizard.md) list the systems grouped by genre, not alphabetically. `SystemGroups.grouped()` puts each system once, under the first genre it lists (`OptionalModule.tags`); a system tagged for every genre (`luaPlugins`) goes under **Works with every genre**; headings follow `SystemTags.TITLES`. Today that gives **Online RPG**, **Roguelike and dungeon crawler**, **Role-play** and the general group (no system lists farming or action first, so those headings appear when one does).

A **genre starter** keeps the systems tagged for a genre on and switches every other system off (`SystemGroups.disabledKeysFor(genre)`): in the wizard a button per genre, in the F1 Systems panel a button per genre that ticks and unticks the boxes (nothing is sent until **Apply systems**), and `world-new --genre <tag>` on the command line. Because tags are suggestions, a starter is where you begin, not a rule: "Roguelike" is parties, instancing, projectiles, crafting, exploration, free movement, gear wear, the threat table, boss encounters and Lua plugins on, and guilds, trading, the market, mail, friends, arena, Guild War, stalls, ranks and rebirth off, and any of it can be switched back. Retag a system in `OptionalModules.kt` to change what a genre keeps.

## Genre tags and requirements
Each system carries `tags` (a [`SystemTags`](/decisions/0018_core_systems_plugins_templates_terminology.md) value: `rpg`, `roguelike`, `farming`, `roleplay`, `action`) and `requires` (config keys of systems that must also be on). A genre is a tag, so one system can serve several; `OptionalModule.withTag(tag)` lists a genre's systems. `OptionalModule.unmetRequirements(disabledKeys)` is the single check behind the F1 Systems panel warning ("Player market needs Mail switched on."); softer dependencies that only degrade (instancing without parties) stay hand-written warnings in `ModulesForm`. `ModuleRegistry` descriptors carry the same metadata; plugin-registered modules have none. Tags are suggestions for grouping in the editors and docs, not rules the engine enforces. Verified by `ModuleRegistryTest`.

## New-System Checklist
Every new gameplay system that changes how a realm plays (plots and crops, owned homes, a market...) must be born toggleable and must reach the kernel only through the [seams](/systems/system_seams.md). A system is an object in `core-domain/.../systems/`:
1. **Implement the seams it needs** (`RequirementGate`, `EventCommandHandler`, `TickParticipant`, `CombatArbiter`, `CombatObserver`, `ActionGate`, `ActivityInterrupter`, `ChatCommandHandler`, `ItemUseHandler`, `KillObserver`, `MonsterBehavior`, `WarpRouter`) and `GameSystem` with its `key`. If no seam fits, add a small one (an interface in `sim/`, a list and an `install` branch in `WorldSimulation`, a helper that skips systems that are off) rather than naming the system in the kernel.
2. **`OptionalModule` entry**: key, display name, one-line description, genre `tags` (the first is its home heading in the wizard and the F1 panel) and, if it cannot work without another system, `requires`. `SystemListTest` fails until the system is installed or listed as kernel-checked.
3. **Install it** in the default list in `WorldSimulation` (order matters within a seam; the Lua plugins come last).
4. **State** a system keeps goes in a kernel field (so replay, saving and the state hash see it), never in the object.
5. **Configuration**: wire its rules into `game.json` (a rule section) or `server.json` with strict validation and defaults that preserve existing realms.
6. **Lua**: nothing to do if it uses an existing seam; a new seam gets a `register.*` call (hook type in `ScriptSystemHooks.kt`, registry list, `ScriptedSystem` adapter, runtime registration) and a row in `docs/API_REFERENCE.md`.
7. **Wizard and client**: the wizard and the F1 panel list `OptionalModule.entries` automatically; hide HUD buttons when it is off (`ClientWorldState.isModuleEnabled`).
8. **Tests**: it does what it should; it does nothing when switched off or replaced; the boundary test (only `WorldSimulation` imports `systems/`) still passes.
