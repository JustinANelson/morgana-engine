---
type: Known Issue
title: "Gameplay Numbers Still Hard-Coded in the Engine"
description: "Constants in core-domain that a game designer might want to change but that still need a code change: the action-combat combo grace, social list limits and a few internal timings. Most of the first audit is fixed."
tags: [configuration, game-definition, gap, mandate]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/InventorySystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/EconomySystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/item/ItemAndInventory.kt", "core-domain/src/main/kotlin/com/morgana/domain/combat/ActionCombatRules.kt", "core-domain/src/main/kotlin/com/morgana/domain/social/GuildRules.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/WorldClock.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/Tuning.kt", "core-domain/src/main/kotlin/com/morgana/domain/item/GearRules.kt"]
symbols: ["HARVEST_COOLDOWN_TICKS", "SHOP_RANGE_TILES", "GROUND_PICKUP_RANGE_TILES", "PLUS_WEAPON_DAMAGE", "TARGET_RADIUS", "COMBO_GRACE", "MAX_MEMBERS", "GearRules", "GameplayTuning", "WorldClock"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-24T12:00:00Z }
stale_after: 2027-05-23T00:00:00Z
---

# Gameplay Numbers Still Hard-Coded in the Engine

Mandate 12 in `AGENTS.md`: a value a game designer could want to differ belongs in `game.json` with a validated range and a default equal to today's behaviour. Found 2026-11-24 while configuring a survival game on the engine, where the day/night split and the broken-gear penalty could not be set without a code change. Those two are fixed (see the [game definition](/domain/game_definition.md): `clock` phase boundaries and `durability.brokenStatPercent`), and a rarity can now have weight 0. A grep of `core-domain` for numeric constants found the rest; this is a first pass, not an exhaustive audit.

| Constant | Where | Value | Why a game would change it |
| :--- | :--- | :-: | :--- |
| `ActionCombatRules.COMBO_GRACE` | `combat/ActionCombatRules.kt` | 20 | Combo window. Left alone on purpose: action-skill validation checks chains against `ActionCombatLimits.MAX_COMBO_GRACE_TICKS`, so making it tunable means the validator needs the game's value too |
| `LAST_SEEN_STAMP_TICKS`, `TICKS_PER_HOUR` | `sim/CombatSystem.kt` | 200, 72000 | Internal timing; probably fine as constants |
| `MAX_FRIENDS`, `MAX_IGNORED`, mailbox size | `social/`, `mail/` | | Limits; same treatment if a game wants them |
| Melee/ranged/spell formulas | `game.json` `combat` | | Already data |

**Fixed 2026-11-24** (now `game.json` `tuning`, see [game definition](/domain/game_definition.md)): harvest cooldown, shop and pickup range, unarmed damage, the `+` level values (and a percentage form), guild size, party size (the engine had no limit) and the action-combat target size. The old constants (`HARVEST_COOLDOWN_TICKS`, `SHOP_RANGE_TILES`, `GROUND_PICKUP_RANGE_TILES`, `ItemStack.PLUS_*`, `GuildRules.MAX_MEMBERS`, `ActionCombatRules.TARGET_RADIUS`) remain as the documented defaults. Earlier the same day: the clock phase boundaries, `durability.brokenStatPercent` and zero-weight rarities.

## Suggested order
1. Make the combo grace tunable together with its validator limit.
2. Friends, ignore list and mailbox limits, if a game needs them.
3. Keep auditing: grep `core-domain` for numeric `const val`s and inline literals in rules whenever a new system is added.

When one is moved into `game.json`, remove its row, update [game definition](/domain/game_definition.md), and add a test that the default is unchanged.
