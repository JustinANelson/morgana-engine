---
type: Simulation System
title: "NPC AI"
description: "How NPCs respawn, acquire targets, attack, leash, and wander."
tags: [npc, ai]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/NpcAiSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/content/WorldContentModels.kt"]
symbols: ["tickNpcs", "canNpcStepTo", "aggroRangeTiles", "leashRangeTiles", "FRIENDLY_ROAM"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# NPC AI

Runs in `tickNpcs` every tick for each map NPC instance (`MapNpcInstance`, spawned from `MapDefinition.npcSpawns`).

1. **Respawn**: dead NPCs return after the spawn slot's `respawnTicks` (default 100). A slot with `respawnTicks` 0 never respawns (used by [generated dungeon floors](/systems/procedural_floors.md); before 2026-11-13, 0 meant one tick).
2. **Status effects** tick on NPCs too.
3. **Target acquisition**: `ATTACK_ON_SIGHT` and `GUARD` behaviors look for targets within `aggroRangeTiles`. With `combat.npcs.maxAttackersPerTarget` set, a player already hunted by that many monsters is skipped, so the rest of a pack waits (and wanders) until a hunter dies or loses the target; a monster the player attacked keeps fighting. A newly acquired target is stored at once (before 2026-11-16 it was kept only if the monster then moved or struck that tick). `firstStrikeDelayTicks` holds back the first blow or spell after acquiring.
4. **Leash**: beyond `leashRangeTiles` from home, the NPC returns.
5. **Spells** (`spellIds`): a healing or `SELF` spell is cast below 60% life; an attack spell is cast in `rangeTiles` when its cooldown allows. With a wind-up (the spell's `castTimeTicks`, else `combat.npcs.spellWindUpTicks`) the monster stops, shows "Casting <spell>..." (floating text, category `CAST`, and `castingSpellId` / `castFinishTick` on the instance), and the spell lands when the wind-up ends if the target is still within range + 1, otherwise it fizzles; a stun interrupts it. With `casterKeepsDistance` a monster in spell range holds its ground instead of closing to melee.
6. **Attack or approach**: attacks when in reach and `attackSpeedTicks` has elapsed; otherwise moves one step when `moveSpeedTicks` has elapsed (`canNpcStepTo` honors blocking, `NPC_AVOID`, and entities).
7. **Idle wander**: for `PASSIVE`, `ATTACK_ON_SIGHT`, and `FRIENDLY_ROAM` NPCs, once `moveSpeedTicks * 3` ticks have passed since the last move there is a 20% (2000 bp) chance per tick to step in a random cardinal direction, staying within Manhattan distance 4 of home.

Behaviors: `PASSIVE`, `ATTACK_ON_SIGHT`, `GUARD`, `SHOPKEEPER`, `BANKER`, `GUILD_BANKER` (opens the guild vault), `QUEST_GIVER`, `FRIENDLY_ROAM`. A `SHOPKEEPER` needs a `shopId` (validator error otherwise; [validation rules](/content/validation_rules.md)).
Damage dealt/taken and loot: [combat](/systems/combat.md). Stats come from `NpcDefinition` ([content collections](/content/content_collections.md)).

## Pacing rules (`combat.npcs`, 2026-11-16)
Opt-in per game in `game.json` (`NpcCombatDefinition`, all off by default so older games behave as before): `maxAttackersPerTarget`, `firstStrikeDelayTicks`, `spellWindUpTicks`, `casterKeepsDistance`. Added after a first pack of monsters hit a player all at once. Conquer Online uses 2 attackers, a 15-tick first-strike delay, a 14-tick spell wind-up and distance-keeping casters. Tests: `NpcPacingTest`.
