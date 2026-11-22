# Supported Game Genres, Systems & Templates

Morgana Engine is a genre-neutral, deterministic 2D multiplayer engine, described in four layers ([ADR 0018](../wiki/decisions/0018_core_systems_plugins_templates_terminology.md), accepted):

| Layer | Meaning |
| :--- | :--- |
| **Core** | The base engine every game needs: simulation, maps, movement, persistence, networking, permissions, replay, content pipeline, clients and the `F1` editors. |
| **Systems** | Switchable features built into the engine (`OptionalModule` flags in `server.json`, plus the rule sections of `game.json`). Each has genre tags and lists the systems it requires. A genre is a *tag*, not a layer. |
| **Templates** | Starting games (`games/<name>/`) that declare which systems are on; a creator can toggle any of them. A **genre starter** in the wizard (or `world-new --genre`) switches on the systems tagged for a genre and off the rest. |
| **Plugins** | Sandboxed Lua (`scripts/`) that overrides how a system works in one game (combat, stats, enemy skills). Trusted Kotlin jars (`plugins/`) are *server extensions*, operator-installed and never part of a template. |

In this document "module" keeps its two code meanings: a `CombatModule` (the combat engine) and an `OptionalModule` (a **system** switch). Below, "pluggable modules to create" means **systems or plugins still to build** for a genre.

This document catalogs:
1. **Current systems** and their **mutual-exclusivity / interaction rules**.
2. **Well-suited game genres**, how they map to existing systems, and the systems or plugins needed to support each genre cleanly.

---

## 1. Current Modules & Mutual Exclusivity Review

Morgana currently separates **replaceable combat engines** (`game.json` → `combat.module`) from **toggleable server systems** (`server.json` → `modules`).

### 1.1 Pluggable `CombatModule` (`game.json`)

Implemented in `core-domain/.../combat/CombatModule.kt`:

| ID | Implementation | Purpose |
| :--- | :--- | :--- |
| `realtime` *(aliases: `morgana_classic`, `classic`)* | `RealtimeCombatModule` | Default 20 TPS real-time melee, ranged projectiles, cast-time spells, status effects, and PvP/loot rules. |
| `noop` *(alias: `none`)* | `NoOpCombatModule` | Disables all combat ticks, attacks, and spell casts for peaceful/narrative/social worlds. |
| `action` *(alias: `action_combat`)* | `ActionCombatModule` | Action combat for the player: timed skills chained into combos from `weapon_sets`, aimed hitboxes, dodge frames, guard and super armour. Wraps `realtime`, so monsters, bosses and spells are unchanged. See `wiki/systems/action_combat.md`. |
| `lua_combat` *(alias: `lua`)* | `LuaCombatModule` | Delegates `on_attack`, `on_cast`, `on_tick_projectiles`, `on_tick_players`, and `handle_action` (`c_action`) to sandboxed Lua scripts. |

**Mutual Exclusivity:**
* **`realtime`, `action`, `noop`, and `lua_combat` are strictly mutually exclusive.** A game selects exactly one `CombatModule` in `game.json`.

### 1.2 Built-In `OptionalModule` Flags (`server.json`)

Implemented in `core-domain/.../sim/OptionalModules.kt`:

| Key | Module | Default | Notes & Interactions |
| :--- | :--- | :--- | :--- |
| `parties` | Parties | On | Required for party XP sharing and multi-player party instances. |
| `guilds` | Guilds | On | Orthogonal to all other modules. |
| `trading` | Player trading | On | Orthogonal to all other modules. |
| `crafting` | Crafting | On | Orthogonal to all other modules. |
| `projectiles` | Projectiles | On | **Inert / superseded** when `combat.module` is `noop`. When `lua_combat` is active, native `CombatSystem` projectile guards do not apply automatically unless Lua scripts check `projectiles`. |
| `subTileMovement` | Free pixel movement | On | **Mutually exclusive realm-wide** with grid-locked 4-direction step movement (`off` = grid steps for all players; `on` = smooth pixel movement with diagonals and wall sliding for all players). |
| `softSeams` | Soft map seams | On | **Ignored on instanced maps** when `instancing` is on (instanced maps are discrete copies and do not stitch across borders to shared overworld maps). |
| `instancing` | Instanced maps | On | Compatible with `parties`; if `parties` is off, `instancing` degrades to solo-only private copies. |
| `rolePlayChat` | Role-play chat | On | Orthogonal to all other modules. |

The engine's own rule systems are in the same list and the same `modules` map, all on by default and mostly inert until a game's rules use them: `rebirth`, `promotion`, `arena`, `guildWar` (needs `guilds`), `guildBank` (needs `guilds`), `pets`, `stalls`, `durability`, `gearUpgrades`, `respec`, `threat`, `bossEncounters` and `luaPlugins`. A switch removes that system from play for the realm; a Lua plugin can instead replace one (`register.plugin{ replaces = ... }`). See `wiki/server/optional_modules.md`.

*(Plugin-registered modules use namespaced `<pluginId>:<name>` keys in the same `server.json` `modules` map.)*

---

## 2. Genres & Required Pluggable Modules

### 2.1 Classic 2D Online RPGs (ORPG / Small-Scale MMORPG)
* **Fit:** Out of the box.
* **Built-in stack:** `realtime` combat + `parties`, `guilds`, `trading`, `crafting`, `projectiles`, `softSeams`, `MapMoral` (`SAFE_ZONE`, `OPEN_PVP`, `ARENA`), paperdoll equipment, shops, bank vault, quests, and `F1` live world editing.
* **Pluggable modules to create:**
  * (`mail`, `friends` and the player `market` are built as optional modules: see `wiki/systems/mail.md`, `friends.md` and `market.md`. A bidding auction is not.)
  * `mounts` / `pets`: Summonable speed modifiers or non-combat/combat follower entities.
  * `guildProgression`: Guild banks, structured ranks/permissions, and territory/guild halls.

### 2.2 Co-op Roguelikes & Dungeon Crawlers
* **Fit:** Out of the box (demonstrated by `games/roguelike` / *Lanternfall*).
* **Built-in stack:** `realtime` (or `lua_combat`), `instancing`, `parties`, `projectiles`, `FloorGenerator` (seeded rooms-and-corridors generation), boss encounters (phases, adds, telegraphed ground attacks), dungeon `scaling` with difficulty tiers, the `ghost` death rule with revive spells, stamina `sprint` and `jump`, `paragon`, the bestiary and loot rarities with affixes. `games/roguelike` uses all of them from data.
* **Pluggable modules to create:**
  * `metaProgression`: Account/character persistent unlocks, hub upgrades, and carry-over currencies between runs.
  * `relicDrafting`: Mid-run perk/boon selection UI and effect stacking (via `c_action` + Lua or a dedicated port).
  * `bossEncounters` *(built: see `wiki/systems/boss_encounters.md`; room-lock triggers are not)*.

### 2.3 Top-Down Action-Adventures (Zelda-like Multiplayer)
* **Fit:** Strong out-of-the-box foundation.
* **Built-in stack:** `realtime`, `subTileMovement`, `projectiles`, `Key / Door` tiles, `Slide` tiles, `Heal / Trap` tiles, page-based events.
* **Pluggable modules to create:**
  * `puzzleState`: Map-scoped / instance-scoped switches, pressure plates, and pushable blocks (currently events only have per-character switches/variables, not shared map state).
  * `dodgeAction`: Invulnerability-frame roll/dash with stamina cost via `c_action` and `CombatModule`.
  * `hookshot` / `toolMovement`: Forced-pull or gap-crossing movement abilities integrated with `MovementRules`.

### 2.4 PvP Arena Brawlers & Full-Loot Extraction
* **Fit:** Strong out-of-the-box foundation.
* **Built-in stack:** `realtime`, `projectiles`, `instancing`, `MapMoral.ARENA` (respawn in place, no drop) and `MapMoral.OPEN_PVP` (full non-bound inventory drop, PK/Rogue tracking), cast-time spells with move-interrupt, status effects (`Stun`, `Root`, `Slow`, `Haste`, `Shield`, DoTs).
* **Pluggable modules to create:**
  * `arenaMatchmaking`: Queueing, balanced team assignment, round timers, and match scoreboards.
  * `extraction`: Timed extraction zones, raid-only inventory vs. persistent safe stash, and instance timer/collapse rules.
  * `loadoutSelect`: Pre-match kit/class selection overriding persistent gear.

### 2.5 Cozy Gathering, Farming & Life-Sim Worlds
* **Fit:** Partial today (gathering/crafting/social work; farming/housing are planned).
* **Built-in stack:** `noop` combat, `crafting`, `trading`, `rolePlayChat`, resource nodes (trees, ore, fishing, herbs), weather & 2D lighting, `WorldClock`.
* **Pluggable modules to create:**
  * `farming` *(planned in ADR 0015 / `docs/FARMING_AND_HOUSING.md`)*: Plantable `plot` tiles, growth stages driven by `worldEpochTicks`, watering, withering, and seasonal rules.
  * `housing` *(planned in ADR 0015)*: Claimable `HomeLot` tiles or instanced player homes with persistent `PlacedObject` furniture and visitor permissions.
  * `npcSchedules`: Time-of-day NPC movement/shop availability driven by `WorldClock`.
  * `relationships`: NPC friendship/gifting affinity tracking.

### 2.6 Persistent Role-Play (RP) Sandboxes
* **Fit:** Out of the box.
* **Built-in stack:** `noop` or `realtime` combat, `rolePlayChat` (`/me`, `/ooc`, `/shout`, `/quiet`, `/narrate`), `softSeams`, 5-tier moderation, live `F1` world editing.
* **Pluggable modules to create:**
  * `diceRoller`: Tabletop `/roll` and attribute-check commands via Lua `register.command`.
  * `propPlacement`: Player-placeable temporary or persistent scene props (chairs, campfires, signs) with permission limits.
  * `characterProfiles`: Extended RP bio, traits, and inspectable character sheets.

### 2.7 Narrative / Interactive Fiction & Puzzle-Adventure
* **Fit:** Good foundation; missing cinematic/world-state helpers.
* **Built-in stack:** `noop` combat, page-based event engine (ordered preconditions on switches, variables, quests, items, level), branching dialogue with portraits, `Warp` / `Key / Door` / `Slide` tiles.
* **Pluggable modules to create:**
  * `cutscenes`: Scripted NPC move routes, camera panning/locking, and SFX/BGM triggers (noted as unbuilt in `docs/DESIGN.md`).
  * `worldFlags`: Server-wide / shared story switches and variables (distinct from per-character switches).
  * `journal`: Player-facing clue log, bestiary, or lore codex.

### 2.8 Survival / Resource-Management Co-op
* **Fit:** Requires custom tick/action logic via `lua_combat` or Kotlin plugins.
* **Built-in stack:** `realtime` or `lua_combat`, `crafting`, `trading`, `subTileMovement` (or grid), resource nodes, `WorldClock`, weather.
* **Pluggable modules to create:**
  * `survivalVitals`: Periodic hunger, thirst, warmth/exposure drain and shelter checks tied to `WorldClock` and weather.
  * `baseBuilding`: Player-constructed walls, doors, workbenches, and shared storage chests with durability/decay.
  * `nocturnalHazards`: Nighttime spawn tables and heightened aggro/light-radius mechanics.

### 2.9 Turn-Based / Tactical Grid RPGs & Autobattlers
* **Fit:** Possible via `lua_combat` or a dedicated `CombatModule`, but requires disabling real-time movement assumptions during combat.
* **Built-in stack:** Grid-locked movement (`subTileMovement: false`), data-defined stats/vitals (`game.json`), inventory/equipment, `c_action` generic action packets.
* **Pluggable modules to create:**
  * `turnBasedCombat` (`CombatModule` implementation): Pauses real-time tick attacks, manages initiative queues, turn timers, and action-point (`AP`) budgets.
  * `tacticalEncounter`: Moves combatants into an instanced grid arena on engagement and locks free roaming until resolution.
  * `autobattler`: Unit placement phase + deterministic automated round resolution.

---

## 3. Mutual Exclusivity Matrix (Existing & Proposed)

| Module A | Module B | Relationship | Reason |
| :--- | :--- | :--- | :--- |
| `combat.module: realtime` | `combat.module: noop` / `lua_combat` | **Strictly Mutually Exclusive** | Only one `CombatModule` can be active per game in `game.json`. |
| `subTileMovement: true` | Grid-locked movement (`false`) | **Strictly Mutually Exclusive** | Realm-wide movement rule; cannot be mixed per player or per map. |
| `subTileMovement: true` | `turnBasedCombat` / `tacticalEncounter` *(proposed)* | **Incompatible / Mutually Exclusive** | Tactical turn-based combat requires discrete tile coordinates and grid-step validation. |
| `combat.module: noop` | `projectiles: true` | **Redundant / Inert** | `NoOpCombatModule` never fires attacks or spells, so projectiles never spawn. |
| `combat.module: noop` | `arenaMatchmaking` / `extraction` *(proposed)* | **Incompatible** | PvP arena and extraction loops require active combat (`realtime` or `lua_combat`). |
| `instancing: true` | `softSeams: true` | **Per-Map Exclusive** | Discrete instanced maps do not render or stitch neighbors across `softSeams`. |
| `parties: false` | `instancing: true` | **Degraded** | Instancing works, but only for solo players since parties cannot be created. |
