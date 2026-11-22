---
type: Simulation System
title: "Action Combat Module"
description: "The Kotlin action combat module (combat.module action): combo graphs of timed skills, aimed hitboxes, dodge frames and guards, with client input tokens."
tags: [combat, action-combat, roguelike, input]
code: ["core-domain/src/main/kotlin/com/morgana/domain/combat/ActionCombatModule.kt", "core-domain/src/main/kotlin/com/morgana/domain/combat/ActionCombatRules.kt", "core-domain/src/main/kotlin/com/morgana/domain/combat/CombatModule.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/NpcAiSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/systems/BossEncounterSystem.kt", "client-core/src/main/kotlin/com/morgana/client/input/ActionCombatInput.kt", "core-domain/src/test/kotlin/com/morgana/domain/ActionCombatModuleTest.kt", "server-app/src/test/kotlin/com/morgana/server/RoguelikeReferenceGameTest.kt"]
symbols: ["ActionCombatModule", "ActionState", "ActionCombatRules", "ActionCombatInput", "actionDefence", "actionStates", "incomingDamage"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-21T18:00:00Z }
stale_after: 2027-05-20T00:00:00Z
---

# Action Combat Module

Added 2026-11-21 for the co-op roguelike (owner request: "I need action combat for the roguelike"). A game turns it on with `"combat": { "module": "action" }` in `game.json`. It runs the [action-combat content](/domain/action_combat_content.md) (`action_skills` and `weapon_sets`, authorable in the F1 **Action skills** and **Weapon sets** panels) and is the Kotlin port of the White River Lua prototype, plus the pieces that prototype left out.

## What it replaces and what it keeps
`ActionCombatModule` **wraps** the realtime module. The player's attack is a skill from a weapon set; everything else is the realtime module's: monsters and their AI, [boss encounters](/systems/boss_encounters.md), spells cast from the hotbar, projectiles in flight, status effects, regeneration, loot, XP, [ghost death](/systems/combat.md) and party play. So a game gets action combat for the player without changing a single monster.

## Input
Every key press is an **input token** (`LMB`, `RMB`, `SPACE`, `Q`, `E`, `F`, `TAB`, with `SHIFT+` and a direction `FORWARD`, `BACK`, `LEFT`, `RIGHT` relative to the facing; see `InputTokens`), sent as `c_action` `input` with `params.token`, and optionally `aimX` and `aimY` (the cursor in map pixels, y down). The plain attack packet counts as `LMB`; `c_action` `release` ends a held guard. The client (`ActionCombatInput`, hooked in `MorganaGame`) sends: left click or Ctrl = `LMB` toward the cursor, right click = `RMB`, Space = `SPACE` (a dodge in most sets), `Q` = `Q` and releases on key up, `F` = `F` when jump is off, Tab = `TAB`. A held Shift adds `SHIFT+`, held movement keys add a direction. A click attacks instead of walking.

## Rules (per tick, integers and one `StrictMath` cosine, only the seeded random)
- **Weapon set**: the first `MAIN` set (by id) whose `classIds` and `levelReq` fit the character.
- **Starting a skill**: an *entry* from neutral, or an *edge* from the running skill when the press lands in `[cancel, cancel + windowTicks]`; an earlier press within `inputBufferTicks` is **buffered** and fires at the cancel point; `requiresHit` edges need the skill to have connected; `levelReq`, `cooldownTicks` and `costs` (any vital but life; a short vital refuses the skill with a floating notice) are checked. The aim (cursor, else facing) is fixed for the skill's length and turns the sprite to the nearest of eight directions.
- **Hitboxes** (`CIRCLE`, `RECT`, `ARC`; bodies are discs of 12 px) are tested on every skill tick they are live, against hostile monsters in range. A target is hit once per `hitGroup` (and `maxTargets`). Damage is the game's own melee, ranged or spell formula with the equipped weapon, times `damagePercent`, with crits from the seeded random, through `applyDamageToNpc`, so threat, loot, XP and bosses are unchanged. A projectile hitbox spawns a normal projectile once, in the aim's nearest direction.
- **Reactions**: a hit with a `reaction` and ticks stuns the monster for that long; `knockbackPixels` pushes it one tile per 32 px along the aim (stopping at walls and bodies); a `statusEffectType` is applied; `hitStopTicks` freezes the attacker's timeline.
- **Movement**: a skill's `moves` spread their pixels over their ticks along the aim (negative is backwards) with collision; walls and bodies stop a dash.
- **Dodge frames**: while an `INVULNERABLE` window is open every hit from a monster, a boss ground attack or a monster's projectile does nothing ("Dodge!"). **Guard**: a skill with `guard` holds at the end of its start-up while the key is down (up to `maxHoldTicks`); hits from inside its arc are cut by `damageReductionPercent` and each one pays `blockCosts` ("Blocked"; a guard that cannot pay breaks). Monsters' damage reaches this through `actionDefence` in `NpcAiSystem`, `BossEncounterSystem` and `tickProjectiles`; other combat modules ignore it.
- **Super armour**: while a `SUPER_ARMOR` (or invulnerable) window is open, a stun or root on the character is removed instead of ending the skill; otherwise a stun ends the skill.
- **State**: `WorldSimulation.actionStates` (`ActionState` per character, a sorted map), part of the state hash; cleaned up when idle. Not saved.

## Lanternfall
`games/roguelike` ships on it: **Delver** (Slash, Backslash and Lantern Slam on left click; Shield Bash on right click with super armour; Roll on Space; hold Q to Guard and left click for a Riposte), **Ranger** (Quick Shot into Twin Shot; Piercing Arrow; Backstep; Knife Flick) and **Pyromancer** (Flame Lash into Ember Burst; Fire Wave; Blink). Hotbar spells (Fireball, Mending Light, Frost Nova, Rekindle) still work. See [Lanternfall](/content/roguelike_game.md).

## Not built
Prediction on the client (the server answers over the normal link, so on a slow link a click lands late), skill animations beyond the optional `animationId` effect at the player's tile, player-versus-player hitboxes, the enhancement ladder and durability of White River's later phases, and a weapon set tied to an equipped item (sets are chosen by class and level).

## Combat feel updates (2026-11-21)
- **Hold-to-attack** (`HoldAttackRepeat` in `ActionCombatInput.kt`, hooked in `MorganaGame`): holding left click or `Ctrl` repeats `LMB` at a 150 ms cadence while aiming at the cursor (realtime combat also repeats `Attack` while holding the attack key).
- **Piercing projectiles**: `SkillProjectile.pierce` (and `SpellDefinition.pierce`) now flow onto `MapProjectile.pierce`; piercing projectiles track `hitEntityIds` so they pass through lined-up targets, hitting each once until they hit a wall or run out of range.

## Tests
`ActionCombatModuleTest` (core-domain): combos, buffering, `requiresHit`, costs and cooldowns, hit-stop, knockdown and knockback, dashes with walls, dodge frames, guard arcs and costs, super armour, a replay that hashes the same twice, geometry. `ActionCombatInputTest` (client-core): relative directions and that every token built is valid. `RoguelikeReferenceGameTest`: a Delver chains a combo into a monster, rolls, and a Ranger's arrow hits.
