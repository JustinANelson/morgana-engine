---
type: Simulation System
title: "System Seams and Installed Systems"
description: "How the kernel reaches the systems built on it: twelve seam interfaces, WorldSimulation.install, the installed systems, the boundary test and what stays in the kernel."
tags: [systems, kernel, seams, adr]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/RequirementGate.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/EventCommandHandler.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/SystemSeams.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/MonsterBehavior.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/WarpRouter.kt", "core-domain/src/test/kotlin/com/morgana/domain/WarpRouterTest.kt", "core-domain/src/test/kotlin/com/morgana/domain/MonsterBehaviorTest.kt", "core-domain/src/main/kotlin/com/morgana/domain/systems", "core-domain/src/test/kotlin/com/morgana/domain/RequirementGateTest.kt", "core-domain/src/test/kotlin/com/morgana/domain/SystemSeamsTest.kt"]
symbols: ["RequirementGate", "EventCommandHandler", "MonsterBehavior", "TickParticipant", "CombatArbiter", "ChatCommandHandler", "ItemUseHandler", "KillObserver", "GameSystem", "SystemStatus", "systemStatus", "ActionGate", "ActivityInterrupter", "CombatObserver", "TickStage", "PlayerActivity", "install", "ArenaSystem", "GuildWarSystem", "PetSystem", "PromotionSystem", "RebirthSystem", "StallSystem", "DurabilitySystem", "GearUpgradeSystem", "RespecSystem", "ThreatSystem", "BossEncounterSystem"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-21T21:00:00Z }
stale_after: 2027-05-20T00:00:00Z
---

# System Seams and Installed Systems

[ADR 0018](/decisions/0018_core_systems_plugins_templates_terminology.md) separates the **kernel** (core) from the **systems** built on it. A system used to be a set of extension functions on `WorldSimulation` that the kernel called by name. Now a system is an **object** that implements the seams it needs, and `WorldSimulation.install(system)` adds it to every seam list it fits. The kernel only calls the interfaces. Seams run inside the deterministic simulation, so they are pure queries, or change the world only through the recorded paths the kernel uses (`players`, the `out` event list).

## The seams
| Seam | What a system can do | Kernel call site |
| :--- | :--- | :--- |
| `RequirementGate` | refuse a character a spell or item for its own reason (a rank, a rebirth count) | spell casts, item use and wear, spell scrolls, gear upgrades, the `TeachSpell` event command |
| `EventCommandHandler` | carry out an event command that is its own (`Promote`, `Rebirth`, `ArenaQueue`, `UpgradeGear`, `RepairGear`, `RespecAttributes`); a command nobody takes is skipped | the event runner |
| `TickParticipant` | do something every tick, at `TickStage.AFTER_NPCS` or `AFTER_EVENTS` (install order within a stage) | `stepTick` |
| `CombatArbiter` | `mayFight` (who may hurt whom), `deathOutcome` (take over a death), `takesHitOnNpc` (take over a hit on a monster); each defaults to no opinion and the first opinion wins | `canAttackPlayer`, player death, damage to monsters |
| `CombatObserver` | `afterAttack` (a weapon landed a hit) and `afterStruck` (a character took a hit; observers are applied one after another to the victim) | the attack path, player hits from monsters, bosses and players |
| `ActionGate` | stop a character doing a `PlayerActivity` (`MOVE`, `ATTACK`, `CAST`, `USE_ITEM`, `TRADE`) for now | movement, attacks, casts, item use, trade |
| `ActivityInterrupter` | wind down when a character is pulled away from what they were doing | player death, the start of an arena match |
| `MonsterBehavior` | add to how monsters think, fight and die: `beforeMap` (map housekeeping), `holdsStill`, `step` (a scripted turn before the kernel thinks), `adjustVariant`, `retarget`, `onTaunted`, `onHit`, `afterHeal`, `mayBeElite`, `killLockoutTicks`; every method defaults to no opinion | the NPC tick, damage to monsters, healing, elite rolls, kills |
| `WarpRouter` | decide where a warp really goes: `WarpRoute.To` a map (a private copy, say), `Redirect` to another map (routed again, at most four times) or `Refused`; no opinion means the map the warp named | every player warp (`warpedPlayer`: warp tiles, warp spells and scrolls, the event warp step, script warps) |
| `ChatCommandHandler` | own slash commands (`/arena`, `/pet`, `/guildwar`, `/stall`) | `executeAdminSlashCommand`, before the kernel's own commands |
| `WallClockListener` | follow real-world time: told the wall clock about once a second (the scheduler runs from it) | `WorldSimulation.advanceWallClock`, from the recorded `SimInput.WallClock` |
| `WorldObjectGate` | refuse a placement in the world (a claim system keeps builders out of someone's land) | `WorldSimulation.placeWorldObject`, after the world state's own checks |
| `WorldObjectObserver` | hear every world object placed, changed or removed | the world state system |
| `ScheduleHandler` | react to a scheduled warning or start | the scheduler, for each trigger it raises |
| `ItemUseHandler` | give an item a use of its own (a pet item adopts its pet) | using an inventory slot |
| `KillObserver` | share in a kill's reward (a pet earns experience beside its owner) | the monster-death path, after the killer's XP |

`WorldSimulation` keeps one list per seam plus `systems` (everything installed). `install` is a no-op for an object already installed, and one object can serve several seams. A game or add-on installs its own before ticking (`sim.install(MySystem)`), or reorders and clears the lists (`sim.eventCommandHandlers.add(0, handler)` takes a command ahead of the built-in one, `sim.systems` shows what is on). A system that is not installed does nothing: its commands are skipped, its tick never runs, its gates never block.

## One list of systems, with switches
Every installed engine system is a `GameSystem` whose `key` is an `OptionalModule` config key, so the engine's rule systems and the older feature switches are one list ([optional modules](/server/optional_modules.md)). The kernel asks `systemOn(key)` before it calls a system in **any** seam: the system's `server.json` switch must be on and no running Lua plugin may replace it. That makes three ways to take a system out of play, all without code:
- `server.json` `modules` (`"threat": false`), which is also what the F1 Systems panel and the New Realm Wizard write;
- a **template**: a game folder's own `server.json` switches carry into new realms, so a roguelike starter can ship with `arena` and `guildWar` off;
- a **plugin**: `register.plugin{ replaces = {"threat"} }` while the plugin runs. Its hooks then are the only rule; if it stops running (a system it requires is off, or its module is disabled after repeated errors) the engine system comes back by itself.

An installed object that is not a `GameSystem` (a test's, say) is always on, and `ScriptedSystem` is itself the `luaPlugins` system, so that switch silences every Lua hook. `WorldSimulation.systemStatus()` returns a `SystemStatus` per system (switch, replacing plugin id, whether an object is installed, tags, requirements) for editors and diagnostics. A switch is realm-wide and read at call time; `server.json` is still read at startup, so changes need a restart (as for every module).

## Installed by default
`WorldSimulation` installs these in order, so seam lists run in that order: `RebirthSystem` (gate, `Rebirth`), `PromotionSystem` (gate, `Promote`), `ArenaSystem` (tick, combat, `/arena`, `ArenaQueue`), `GuildWarSystem` (tick, combat, `/guildwar`), `PetSystem` (tick, kill reward, item use, `/pet`), `StallSystem` (action gate, interrupter, `/stall`), `DurabilitySystem` (combat observer, `RepairGear`), `GearUpgradeSystem` (`UpgradeGear`), `RespecSystem` (`RespecAttributes`), `ThreatSystem` (monster behaviour: the threat table) and `BossEncounterSystem` (monster behaviour: phases, ground attacks, adds, lockout), `ScriptedWarps` (the Lua warp hooks, before instancing so a plugin sees a warp as it was made) and `InstancingSystem` (warp router: private copies for party, guild and solo, generated floors, difficulty tiers and monster scaling); the Lua plugins system follows. `GuildWarSystem` is also a monster behaviour: its poles and gates stand still. They live in `core-domain/.../systems/`; the old extension functions are the systems' internals. Behaviour is unchanged: the existing arena, guild war, pet, promotion, rebirth, stall, durability, gear upgrade and respec tests and the golden replay pass untouched.

Two more files in `systems/` are **services behind the public facade**, not hooks: `TradeSystem` (player trading) and `GuildBankSystem` (the guild vault). Clients reach them through `WorldSimulation.initiateTrade`, `guildBankDeposit` and the like, which check the `trading` switch and call into them; `WorldSimulation.kt` is the composition root and the only kernel file allowed to import `systems/`. Their next step is to move the call from the facade to the server coordinator, so the simulation class stops carrying their method names.

The state a system keeps stays in the kernel's fields so replay, saving and the state hash see it: `arenaQueue`, `arenaMatches`, `guildWar` and `stalls` on `WorldSimulation`, pets and gear on `CharacterState`.

## Boundary test
`RequirementGateTest` fails if any file outside `systems/` imports `com.morgana.domain.systems` other than `sim/WorldSimulation.kt` (the composition root). The list may only shrink. `SystemSeamsTest` shows each seam used the way a game's own system would, and snapshots which systems are installed in which seam lists by default.

## What stays in the kernel, and why
The architecture is finished when the kernel names no optional feature, and what remains in `sim/` is there for a stated reason:

- **Kernel services** that other systems and ordinary play depend on: movement, combat (damage, spells, projectiles), inventory and equipment, NPC AI, events and quests, shops and banks (`EconomySystem`), admin commands and live editing, scripts, the tick loop, the ghost and death rules.
- **Map-copy infrastructure** (`InstanceSystem.kt`): the reserved id range, `MapInstance`, `contentMapId` and `contentMaps`, closing a copy that has stood empty, the wipe-ends-run rule and putting a character back after login. It stays in the kernel because the arena makes copies too and because content, validation and the editors must tell copies from content maps. *Who gets which copy* is the `instancing` system.
- **Reflect and fly** (`ReflectAndFly`): `isFlying` and `reflectPercent` are fields of `CharacterState` that damage and movement read, so they are rules of those kernel services, not a feature that can be switched off.
- **The composition root**: `WorldSimulation` installs the default systems, holds the state they keep (`arenaQueue`, `arenaMatches`, `guildWar`, `stalls`, so replay and saving see it) and exposes the public methods the server calls (`initiateTrade`, `guildBankDeposit`...). It is the one file the boundary test lets import `systems/`.
- **Switches the kernel checks itself** (parties, guilds, trading, crafting, projectiles, free pixel movement, soft seams, role-play chat, friends, exploration, ignore, market, mail and the guild bank): features that live in their own domain packages and are driven by the coordinator. `SystemListTest` pins this list, so a new `OptionalModule` has to be either an installed `GameSystem` or added there on purpose.

Everything else (promotion, rebirth, arena, Guild War, pets, stalls, gear wear, gear upgrades, respec, the threat table, boss encounters, instancing and the Lua plugins) is an installed system behind a seam. To add one, see the checklist in [optional modules](/server/optional_modules.md).

### The monster-behaviour seam
`MonsterBehavior` is where the monster side of the engine is open to systems and, later, to Lua. The kernel keeps the basics (respawn, aggro by sight, walking, striking, leashing, status effects, loot) and asks the behaviours at fixed points of the NPC tick: before a map's monsters are stepped (`beforeMap`), whether this one acts at all (`holdsStill`), a scripted step before it thinks (`step`), its variant multipliers (`adjustVariant`), whether to change who it fights (`retarget`, `onTaunted`), and on a hit or a heal. Methods that return a monster return it as it should now be stored; behaviours are applied in install order and `onHit` is first-opinion-wins. `MonsterBehaviorTest` shows a game's own behaviour holding monsters still, stepping them, retargeting, recording hits and folding variants, and that monsters still hunt and fight with no built-in behaviours installed. This is the seam an NPC AI or enemy-skill override in Lua would implement.

## Lua
Every seam is open to a game's Lua plugin. The plugin API is in `docs/API_REFERENCE.md` and the adapter is `ScriptedSystem` (the `luaPlugins` system, installed last in every world, plus one `ScriptedTicks` per tick stage); see [script host](/systems/scripting.md).

| Seam | Lua |
| :--- | :--- |
| `RequirementGate` | `register.requirement` |
| `CombatArbiter` (`mayFight`) | `register.may_fight` |
| `MonsterBehavior` | `register.monster` |
| `WarpRouter` | `register.warp` |
| `TickParticipant` | `register.tick` |
| `ItemUseHandler` | `register.item_use` |
| `ScheduleHandler` | `register.schedule` |
| `WorldObjectGate` | `register.object_gate` |
| `ActionGate` | `register.action_gate` |
| `ActivityInterrupter` | `register.on_interrupt` |
| `CombatObserver` | `register.combat_observer` |
| `KillObserver` | `register.on_kill` |
| `ChatCommandHandler` | `register.command` (older, not through the adapter) |
| `EventCommandHandler` | `register.event_command` / `register.event_condition` (older, not through the adapter) |

A plugin names the systems it needs with `register.plugin{ requires = ... }` and can replace any engine system with `replaces = ...` (including `instancing`). Not open to Lua: `CombatArbiter.deathOutcome` and `takesHitOnNpc` (taking over a death or a hit on a monster), and `CombatObserver.afterStruck` changing the character who was struck, because the engine is part-way through changing them; a plugin that needs those replaces the system that owns them.
