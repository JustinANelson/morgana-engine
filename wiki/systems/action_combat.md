---
type: Simulation System
title: "Action Combat Module"
description: "The Kotlin action combat module (combat.module action): combo graphs of timed skills, aimed hitboxes, dodge frames and guards, with client input tokens."
tags: [combat, action-combat, roguelike, input]
code: ["core-domain/src/main/kotlin/com/morgana/domain/combat/ActionCombatModule.kt", "core-domain/src/main/kotlin/com/morgana/domain/combat/ActionCombatRules.kt", "core-domain/src/main/kotlin/com/morgana/domain/combat/CombatModule.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/NpcAiSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/systems/BossEncounterSystem.kt", "client-core/src/main/kotlin/com/morgana/client/input/ActionCombatInput.kt", "core-domain/src/test/kotlin/com/morgana/domain/ActionCombatModuleTest.kt", "game-tests/src/test/kotlin/com/morgana/gametests/RoguelikeReferenceGameTest.kt"]
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

## Crit-armed transformations (2026-11-26)
`WeaponSetDefinition.empowerOnCrit` turns the empower machinery (`empowerSkillId`, `empoweredSkillId`, `empowerTicks`) into a crit reward: a critical hit by the character (any skill, arrows included through `actionCrit`) arms the transformation for `empowerTicks`; while armed the entry for `empowerSkillId` starts `empoweredSkillId` instead, and casting it spends the transformation. The transformed skill cannot re-arm itself. `empowerChancePercent` is the chance that the first crit of a skill use arms it (one roll per use, so many small hits do not keep rolling) and `empowerCooldownTicks` is the lockout after one is spent or runs out. The client shows a "<name> ready!" banner, renames the button with a gold rim, and keeps the golden sparks; a skill's `effect` may list several effects separated by commas. Tests: `CritTransformTest`.

## Crits, electric hits, bashes (2026-11-26)
- **Per-skill crit chance**: a hit's chance is the game's agility-based `combat.critChanceBp` scaled by `SkillHit.critScalePercent` (100 = as is), plus `critBonusBp` flat, plus gear and build bonuses. Free-angle projectiles roll their crit when fired (`MapProjectile.crit`).
- **Electric hits**: `SkillHit.electric` stuns the monster for `stunTicks` (status `action-shock`) and the client shows a crackling `shock` effect on it (and stars over any stunned monster). `critElectrifies` makes a critical hit electrify the rest of the skill's hits (and flash an `electrify` effect over the area). Chain lightning stuns everything it strikes. Tests: `ElectricHitTest`.
- **Radial knockback**: `SkillHit.knockbackRadial` pushes each monster directly away from the attacker. Fast moves (a rush, a leap) now go in 20 px sub-steps so they neither skip a thin wall nor pass through a body (`ShieldBashTest`).
- A leap's `spin` effect makes the leaping character hop (`SkillEffect.sourceId`). The momentum system below is still in the engine but Trevena no longer uses it.

## Skill visuals and momentum (2026-11-26)
- **`ActionSkillDefinition.effect`** names a built-in visual the client draws with no art when the skill starts (`WorldSimulationEvent.SkillEffect` -> `ServerPacket.SkillEffect`, drawn by `MorganaWorldRenderer.renderSkillFx` from `SkillFx`/`SkillFxMath`): `arrow_rain` (arrows rise from the shooter and fall on the ground-targeted spot, landing across the skill's hit window), `dodge` (dust trail along the dash), `spin` (a whirl at the leap's landing point), `shockwave` (a ring from the character). Chain lightning also draws a jagged `bolt` between victims, and momentum draws `empower_pulse` and `empower_burst`.
- **Momentum**: a dodge during which an `INVULNERABLE` window took a blow adds `WeaponSetDefinition.momentumPerDodge` to a 0..100 meter (once per dodge; it fades after 12 s unfed). A full meter empowers the character for `empowerTicks`: pressing the entry for `empowerSkillId` starts `empoweredSkillId` instead. State lives on `ActionState` (`momentum`, `empoweredUntil`); `MomentumChanged` -> `ServerPacket.Momentum` tells the owner. `SkillProjectile.angleOffsetDegrees` fans arrows. Tests: `MomentumTest`, `SkillFxTest`.

## Leaps and chain lightning (2026-11-26)
- **`SkillMove.toAim`** (with `maxPixels`): the move is a leap to the aimed point instead of a fixed push. The landing point is fixed when the skill starts (the aim point held within `maxPixels` of the character; half the range forward when there is no aim), the character closes the remaining distance over the move's ticks and arrives on time, and a leap goes over monsters (walls still stop it). Used by a spin attack that jumps to a cluster.
- **`SkillHit.chainTargets`/`chainRadiusPixels`/`chainDamagePercent`**: a melee hit also strikes up to `chainTargets` other hostile monsters within `chainRadiusPixels` of the one hit, nearest first, each for `chainDamagePercent` of the damage dealt, with a yellow "Shock!" notice on each. Tests: `FreeAimCombatTest`.

## Moving presses and free monster movement (2026-11-26)
- **A press made while moving still works.** The client adds the held movement direction to the token ("BACK+LMB"). A weapon set that only defines plain keys used to ignore such presses (a swing sound and nothing else, and no shooting while running). `ActionCombatRules.tokenFallbacks` now tries the token as sent, then without the direction (keeping SHIFT or CTRL), then the key alone, for entries and for combo edges; a set that defines a directional move still wins when it matches.
- **Monsters move freely when the game has `subTileMovement`.** `MapNpcInstance.subPixel`/`pixelX`/`pixelY` hold a monster's pixel position; a chasing monster walks straight at its target every tick (`stepNpcPixels`: slides along walls, keeps about 22 px from other bodies, never enters a tile another body holds), and an idle one ambles to a spot near home (`wanderX`/`wanderY`). A tile change made by anything else (knockback, a leash reset, a respawn) wins and the monster stands on that tile. Hits and free projectiles test the monster's real position (`bodyX`/`bodyY`); the client draws it from the same position. Without the module monsters still step tile by tile. Tests: `FreeNpcMovementTest`.

## Free-angle projectiles and ground-targeted hitboxes (2026-11-26)
- **`SkillProjectile.freeAngle`** (default false, so existing games are unchanged): the shot flies along the exact aim instead of one of eight tile directions. The simulation keeps it as a pixel position and velocity on `MapProjectile` (`free`, `px`, `py`, `vx`, `vy`, `remainingPixels`), moves it every tick (`stepFreeProjectile` in `CombatSystem.kt`), sweeps the whole step so a fast shot cannot skip a target, and hits hostile monsters (and players where PvP is on) whose body (`tuning.actionTargetRadiusPixels` plus 6 px) the path passes within. It stops at a wall, the map edge, its first hit (unless `pierce`) and its range. The client draws it at its pixel position, turned along its heading.
- **`SkillHitbox.groundTarget` and `maxRangePixels`**: the hitbox is centred on the point the player aimed at (the press's `aimX`/`aimY`, kept on `ActionState.aimX/aimY/aimSet`), held within `maxRangePixels` of the character, instead of in front of them. With no aim point it sits in front as before. Tests: `FreeAimCombatTest`.
- The F1 **Action skills** form does not show these three fields yet (edit the JSON; the schema knows them).

## Lanternfall
`games/roguelike` ships on it: **Delver** (Slash, Backslash and Lantern Slam on left click; Shield Bash on right click with super armour; Roll on Space; hold Q to Guard and left click for a Riposte), **Ranger** (Quick Shot into Twin Shot; Piercing Arrow; Backstep; Knife Flick) and **Pyromancer** (Flame Lash into Ember Burst; Fire Wave; Blink). Hotbar spells (Fireball, Mending Light, Frost Nova, Rekindle) still work. See [Lanternfall](/content/roguelike_game.md).

## Not built
Prediction on the client (the server answers over the normal link, so on a slow link a click lands late), skill animations beyond the optional `animationId` effect at the player's tile, player-versus-player hitboxes, the enhancement ladder and durability of White River's later phases, and a weapon set tied to an equipped item (sets are chosen by class and level).

## Combat feel updates (2026-11-21)
- **Hold-to-attack** (`HoldAttackRepeat` in `ActionCombatInput.kt`, hooked in `MorganaGame`): holding left click or `Ctrl` repeats `LMB` at a 150 ms cadence while aiming at the cursor (realtime combat also repeats `Attack` while holding the attack key).
- **Piercing projectiles**: `SkillProjectile.pierce` (and `SpellDefinition.pierce`) now flow onto `MapProjectile.pierce`; piercing projectiles track `hitEntityIds` so they pass through lined-up targets, hitting each once until they hit a wall or run out of range.

## Tests
`ActionCombatModuleTest` (core-domain): combos, buffering, `requiresHit`, costs and cooldowns, hit-stop, knockdown and knockback, dashes with walls, dodge frames, guard arcs and costs, super armour, a replay that hashes the same twice, geometry. `ActionCombatInputTest` (client-core): relative directions and that every token built is valid. `RoguelikeReferenceGameTest`: a Delver chains a combo into a monster, rolls, and a Ranger's arrow hits.
