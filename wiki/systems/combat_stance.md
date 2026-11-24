---
type: Simulation System
title: "Combat stance (SAFE / PvE / PvP)"
description: "A stance each character chooses: only two PvP-stance characters can hurt each other. Per-map permission, arming and disarming times, forced PvP in a siege window, and a shared 'in combat' record."
tags: [systems, combat, pvp, territory]
code: ["core-domain/src/main/kotlin/com/morgana/domain/systems/CombatStanceSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/CombatStance.kt", "core-domain/src/test/kotlin/com/morgana/domain/CombatStanceTest.kt"]
symbols: ["CombatStanceSystem", "CombatStanceDefinition", "Stance", "StancePermission", "StanceForcer", "effectiveStance", "isInCombat", "ticksSinceCombat"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-24T23:30:00Z }
stale_after: 2027-05-23T00:00:00Z
---

# Combat stance (SAFE / PvE / PvP)

The `combatStance` system ([optional modules](/server/optional_modules.md), tags: survival, rpg) lets each character choose who can hit them, so an area attack or a skillshot does not hurt a bystander by accident. It is a layer over the PK mode and safe zones: the stance decides *whether* two characters may fight, then `pkMode` and [territory](/systems/territory.md) safe zones decide the rest. Without a `combatStance` section in `game.json` nothing changes.

## The stances
| Stance | Meaning |
| :--- | :--- |
| SAFE | no players can hit them or be hit by them; hitting something with a weapon switches them to PvE |
| PvE | monsters only (the default) |
| PvP | may hit and be hit by other PvP-stance characters |

Two characters may fight only when **both** are in PvP stance, so a hit never lands on a PvE or SAFE bystander (an area effect asks the same question, per target). Monsters are not affected by stance.

## Configuration (`game.json` `combatStance`)
`defaultPermission` and `maps` give each map a permission: **NONE** (a PvP stance is ignored there, and kept for elsewhere), **OPTIONAL** (characters choose), **FORCED** (everyone is in PvP and cannot leave). `armTicks` is how long asking for PvP takes; `disarmTicks` how long a character must have been out of combat to leave it; `switchTicks` the same for SAFE and PvE; `defaultStance` is PvE or SAFE. Validation points at each bad field (`/combatStance/maps/1/mapIds`...). See [game definition](/domain/game_definition.md).

## Rules
- `/stance` shows the stance; `/stance safe|pve|pvp` asks for one. PvP arms after `armTicks` and taking a hit cancels it; leaving it (or switching SAFE and PvE) is refused while the character is in combat.
- **In combat** means having dealt a weapon hit or taken a hit: `lastCombatTick` on the character. `CombatStanceSystem.isInCombat(sim, id, withinTicks)` and `ticksSinceCombat` let other rules share it (a totem placement lockout).
- **Siege**: a `StanceForcer` ([system seams](/systems/system_seams.md)) can force PvP where a character stands; `territory` does so inside a claim whose attack window is open, so a siege cannot be sat out. Arenas decide by team and ignore stance, because the arena's arbiter answers first.
- State is on the character (saved, hashed, replayed); `/stance` is a recorded chat command and arming runs on the tick.

## Not built yet
- Hitting a SAFE character with a spell does not mark combat (only weapon hits and hits taken do).
- Fugitive rules (a fugitive in a safe area may be hit by anyone in any stance, striking a PvP player who has not attacked you flags you) are not modelled: PK points and `pkMode` are unchanged, and a game wanting the exception uses a Lua `may_fight`... which is asked after this system, so a rule that must override stance replaces the system for now.
- Hotspots smaller than a map need map regions; today permission is per map.
- No client UI: the stance shows through `/stance` and chat.
