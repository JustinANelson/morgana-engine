---
type: Simulation System
title: "Boss Encounters"
description: "Scripted boss fights: phases by life percent, adds, telegraphed ground attacks, dungeon difficulty tiers and weekly lockouts."
tags: [bosses, combat, instancing, dungeons]
code: ["core-domain/src/main/kotlin/com/morgana/domain/content/BossEncounter.kt", "core-domain/src/main/kotlin/com/morgana/domain/systems/BossEncounterSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/InstanceSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/CombatSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/map/MapModels.kt", "core-domain/src/main/kotlin/com/morgana/domain/entity/EntityAndPlayerModels.kt", "core-domain/src/test/kotlin/com/morgana/domain/BossEncounterTest.kt"]
symbols: ["BossEncounter", "BossPhase", "BossAbility", "BossZoneShape", "BossZone", "tickBossEncounter", "clearBossAdds", "BossTelegraph", "DifficultyTier", "InstanceScaling", "NpcScale", "encounterLockouts", "handleNpcKilled"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-19T12:00:00Z }
stale_after: 2027-05-18T00:00:00Z
---

# Boss Encounters

A boss is an ordinary [monster](/systems/combat.md) whose `NpcDefinition` has an `encounter`. Nothing else changes for monsters without one. The behaviour is installed as `BossEncounterSystem` (`systems/BossEncounterSystem.kt`), a `MonsterBehavior` that steps the fight each tick, folds the phase's attack and speed multipliers into the monster's variant, clears the adds when the fight is over, keeps a boss from being an elite and supplies the kill lockout (see [system seams](/systems/system_seams.md)).

**Phases** (`BossEncounter.phases`, validated for order and ranges): listed from the start of the fight, each with a `fromLifePercent` lower than the one before. When the boss's life falls to that percent (`tickBossEncounter`, run from `tickNpcs` every tick) it moves to that phase, never back, until it resets (leash or respawn: `phaseIndex`, `zone` and ability timers clear and its adds go). A phase can set `announce` (said to the map as a warning notice), `attackPercent` and `moveSpeedPercent` (folded in by `npcVariantWithPhase`), and `summonNpcIds` (adds placed on free tiles around the boss, with no spawn slot so they never respawn; they are cleared when the boss is dead or has reset). Announcements and summons happen when a phase is *entered*, so they apply from the second phase on.

**Telegraphed attacks** (`BossAbility`): while the boss has a target, the first ability that is off cooldown starts. The server sets a `BossZone` (centre on the target or the boss, `radiusTiles`) and emits `BossTelegraph`, sent to nearby players as `s_boss_telegraph`; the client paints the tiles red, deepening until `windUpTicks` have passed (`ClientWorldState.telegraphs`, `renderBossTelegraphs`). When the time is up everyone still inside the square takes the spell's damage (its `basePower`, scaled by the phase and the dungeon scale) and status effect; people who moved out are untouched. The spell's own range and targeting are not used.

**Difficulty tiers** (`InstanceScaling.tiers`, a list of `DifficultyTier`): an instanced dungeon with [scaling](/systems/instancing.md) can offer tiers. The player who opens a copy gets the tier named by their `tierVariable` number (default `dungeon_tier`, set by an NPC's dialogue before the warp; `0` is the first tier), lowered to the highest tier their level allows (`tierFor`, `minLevel`); everyone who joins that copy shares it (`MapInstance.tier`). A tier multiplies the party-size scale's life, damage, XP and drop rate (`NpcScale.dropRatePercent` reaches the drop roll through `npcVariant`), and entering announces the difficulty.

**Lockouts** (`BossEncounter.lockoutTicks`, `CharacterState.encounterLockouts`): when a boss with a lockout dies, the players on its map who are not already saved to it share the rewards (loot goes to the killer if unsaved, else to the first unsaved player; XP follows the same player and party sharing as any kill) and are saved to it until `lockoutTicks` pass (1,728,000 ticks is a real day, 12,096,000 a week; world ticks, so it survives restarts). If everyone present is saved, nothing drops and no XP is given. Saved players are told. Lockouts are per boss npc id.

**Tests**: `BossEncounterTest` (zone dodging, phases and adds, lockouts), `DungeonScalingTest` (tiers).
