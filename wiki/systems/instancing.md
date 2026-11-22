---
type: Reference
title: "Instanced Maps"
description: "How maps marked instanced become private per-party copies: entering, sharing, rejoining, closing, limits, and what counts as content."
tags: [instancing, maps, roguelike, modules]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/InstanceSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/systems/InstancingSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/WarpRouter.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt"]
symbols: ["MapInstances", "MapInstance", "InstanceSettings", "InstancesConfig", "warpedPlayer", "positionAfterLogin", "tickInstances", "contentMapId", "contentMaps", "InstanceClosed", "INSTANCING", "instanceReturn", "InstancingSystem", "WarpRouter", "scaleInstanceMonsters"]
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-13T12:00:00Z }
stale_after: 2027-05-12T00:00:00Z
---

# Instanced Maps

A map whose content sets `"instanced": true` is a **template**. Warping into it puts a player in a private copy shared with their party, or alone when they have no party. This is the first piece of co-op roguelike support ([ADR 0016](/decisions/0016_map_instancing_and_coop_roguelikes.md)). It is the optional module `instancing` ([optional modules](/server/optional_modules.md)); with it off, instanced maps are shared as they always were.

## Where the code is (2026-11-21)
Instancing is split along the line between infrastructure and policy ([system seams](/systems/system_seams.md)). The kernel keeps the **map-copy infrastructure** in `sim/InstanceSystem.kt`: the reserved id range (`MapInstances`), `MapInstance`, `contentMapId` and `contentMaps`, `warpedPlayer` (which places the player and keeps `instanceReturn`), closing a copy that has stood empty (`tickInstances`), the wipe-ends-run rule and `positionAfterLogin`; the arena makes copies through the same pieces. The **policy** (who gets which copy: per party, per guild or alone, a fresh generated floor, the opener's difficulty tier, scaling the monsters for the people inside) is `InstancingSystem` in `systems/InstancingSystem.kt`, the `instancing` system, reached through the `WarpRouter` seam that every player warp asks. With the system switched off, or replaced by a plugin (`register.plugin{ replaces = {"instancing"} }`), an instanced map is one shared map and `warpedPlayer` goes where the warp named. A Lua plugin can refuse or redirect warps with `register.warp`.

## Behaviour
- **Entering**: a warp tile, the event `WarpPlayer` step, a warp spell or the script `WarpPlayer` effect aimed at a template goes through `warpedPlayer`. It finds the open instance of that template owned by the player's party (`party:<id>`) or by them alone (`solo:<characterId>`), or opens a new one, and records the player as a member. Admin `/warpto <template>` goes to the template itself (for building).
- **Inside**: the copy has the template's tiles, NPC spawns and resource nodes, its own monsters and loot, and its own map chat. Warping between two copies keeps the player's return point; a warp inside a copy to its own template stays in the copy.
- **Events and quests**: events placed on the template run in its copies, and a visit-map objective for the template counts (`contentMapId`).
- **Closing**: once nobody is inside, the copy is kept for `emptySeconds` (so a dropped player can come back), then closed with everything on it (`WorldSimulationEvent.InstanceClosed`). Ids are never reused within a run.
- **Logging in**: a character saved inside a copy goes back into it if it is still open and they had entered it; otherwise to `CharacterState.instanceReturn` (where they stood before entering), or their class start. A restart closes every instance.
- **Limit**: when `max` instances are open, a new one is refused with "*Map* is full right now."
- **Generated floors**: a template with a floor `generator` is built fresh for each copy, players arriving from elsewhere stand at its entry, and walking in again after the party's copy has emptied starts a new floor. See [procedural floors](/systems/procedural_floors.md).
- **Death**: the normal death rules apply; a player who dies in a copy respawns at their class start, outside it.

## Not content
Copies use map ids from **1,000,000** up (`MapInstances.FIRST_ID`). They never appear in the content catalogue, validation, the F1 map list or the replay content fingerprint (`contentMaps()`). A content map with an id in that range is a validation error (`reserved_id`). Editing a template changes new copies only.

## Settings (`server.json`)
```json
{ "instances": { "emptySeconds": 60, "max": 200 } }
```
`emptySeconds` 0..3600 (default 60), `max` 1..10000 (default 200). Validated at startup; not hot-reloaded.

## Determinism and replay
Instances are part of the simulation state and of `StateHasher` (only when any are open). Instance state is not in a replay's starting state, so record from a moment with no open instances.

## Tests
`InstanceSystemTest` (core-domain: private copies, party sharing, events in copies, closing, rejoin and return, cap, module off, determinism) and `InstancingServerTest` (server: separate chat, editor catalogue, `server.json`).

**Owner**: `MapDefinition.instanceOwner` is `PARTY` (default: the party, or the lone player) or `GUILD` (one copy per guild, guild members only; a guild hall). See [guilds](/systems/guilds.md).

**Scaling dungeons** (`MapDefinition.scaling`, `InstanceScaling`, `NpcScale`, `scaleInstanceMonsters` in `systems/InstancingSystem.kt`, `DungeonScalingTest`): an instanced map may carry a `scaling` block. When a player arrives in a copy, every hostile monster in it is rescaled for the players there plus the newcomer: each player beyond the first (up to `maxMembers`) adds `hpPercentPerMember` to its life, `attackPercentPerMember` to its damage and `xpPercentPerMember` to its XP reward; with `levelFollowsMembers` it also gains `percentPerLevel` percent of life and damage per level the party's average level exceeds its own (capped by `maxLevelShift`, and the shift counts toward the level-offset XP rules and the nameplate). The result is stored on the monster as `MapNpcInstance.scale` and folded into `WorldSimulation.npcVariant`, so combat, XP and drops need no other change; a monster that respawns keeps it. Only untouched monsters (full life, no target) are rescaled when more players arrive, so a dungeon grows with its party and does not shrink mid-run. Trevana Realms's Undercroft 1F to 4F use it.

Difficulty tiers and boss lockouts build on this: see [Boss Encounters](/systems/boss_encounters.md).

Arena matches are fought in instanced copies of the arena map: see [Arena](/systems/arena.md).
