---
type: Concept
title: "Stats, Vitals, and Combat Formulas"
description: "Data-defined attributes, vital pools, and the exact integer combat and progression formulas compiled by the engine."
tags: [stats, balance, game-definition]
code: ["core-domain/src/main/kotlin/com/morgana/domain/game/GameDefinition.kt", "core-domain/src/testFixtures/kotlin/com/morgana/domain/game/MorganaClassic.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/BlankGame.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/CompiledGame.kt", "core-domain/src/main/kotlin/com/morgana/domain/stats/StatsAndVitals.kt"]
symbols: ["GameDefinition", "CompiledGame", "MorganaClassic", "BlankGame", "VitalState"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Stats, Vitals, and Combat Formulas

Attributes (stats) and vitals are data-defined via `GameDefinition` (read from `game.json` or defaulting to `BlankGame`). Pools are tracked at runtime using `VitalState(cur, max)`. All formulas are integer expressions compiled and evaluated deterministically by `CompiledGame` (see [change a formula](/playbooks/change_formula.md)).

The Morgana Classic test fixture declares attributes `str`, `def`, `int`, `agi`, `vit` and vitals `hp` (LIFE vital), `mp`, and `sp` (stamina):

| Quantity | Formula |
| :--- | :--- |
| Max level | 100 |
| Max HP | `max(10, 80 + level*20 + vit*12 + str*3 + bonus)` |
| Max MP | `max(0, 30 + level*10 + int*10 + bonus)` |
| Max SP | `max(10, 80 + level*5 + agi*6 + vit*4 + bonus)` |
| XP to next level | `mulDiv(level * level * 100L, 1, 1)` |
| Melee damage | `max(1, a.str*2 + weapon + a.agi/3 - (d.def + armor/2 + d.vit/4))` |
| Ranged damage | `max(1, a.agi*2 + a.str/2 + weapon + ammo - (d.def + armor/2))` |
| Spell output | `rawPower = max(1, basePower + (scales ? a.int*2 + a.vit/4 : 0))`; healing = rawPower; damage = `max(1, rawPower - (d.int/2 + d.def/2))` |
| Crit chance (bp) | `clamp(250 + a.agi*35, 100, 5000)` |
| Dodge chance (bp) | `clamp(300 + (d.agi - a.agi)*30, 100, 4000)` |

Effective attributes = base + equipment bonuses + active status modifiers (`CharacterState.effectiveAttributes`).
Where these are applied: [combat](/systems/combat.md). Regeneration: configured per vital in `GameDefinition.vitals` (every 40 ticks / 2 s for Morgana Classic).
