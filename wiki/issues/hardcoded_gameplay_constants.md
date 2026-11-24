---
type: Known Issue
title: "Gameplay Numbers Still Hard-Coded in the Engine"
description: "Constants in core-domain that a game designer would want to change but that need a code change today: harvest cooldown, shop and pickup range, plus-level values, unarmed damage, action-combat targeting and combo grace, guild size."
tags: [configuration, game-definition, gap, mandate]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/InventorySystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/EconomySystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/item/ItemAndInventory.kt", "core-domain/src/main/kotlin/com/morgana/domain/combat/ActionCombatRules.kt", "core-domain/src/main/kotlin/com/morgana/domain/social/GuildRules.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/WorldClock.kt"]
symbols: ["HARVEST_COOLDOWN_TICKS", "SHOP_RANGE_TILES", "GROUND_PICKUP_RANGE_TILES", "PLUS_WEAPON_DAMAGE", "TARGET_RADIUS", "COMBO_GRACE", "MAX_MEMBERS", "UNARMED_DAMAGE", "WorldClock"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-24T12:00:00Z }
stale_after: 2027-05-23T00:00:00Z
---

# Gameplay Numbers Still Hard-Coded in the Engine

Mandate 12 in `AGENTS.md`: a value a game designer could want to differ belongs in `game.json` with a validated range and a default equal to today's behaviour. Found 2026-11-24 while configuring a survival game on the engine, where the day/night split and the broken-gear penalty could not be set without a code change. Those two are fixed (see the [game definition](/domain/game_definition.md): `clock` phase boundaries and `durability.brokenStatPercent`), and a rarity can now have weight 0. A grep of `core-domain` for numeric constants found the rest below; this is a first pass, not an exhaustive audit.

| Constant | Where | Value | Why a game would change it |
| :--- | :--- | :-: | :--- |
| `HARVEST_COOLDOWN_TICKS` | `sim/InventorySystem.kt` | 10 | Gathering pace (a survival game wants "light cooldown, high repetition") |
| `SHOP_RANGE_TILES` | `sim/EconomySystem.kt` | 6 | How near an NPC a shop or bank must be |
| `GROUND_PICKUP_RANGE_TILES` | `sim/InventorySystem.kt` | 2 | Loot pickup reach |
| `ItemStack.PLUS_WEAPON_DAMAGE`, `PLUS_ARMOR_DEFENSE`, `PLUS_RING_HP` | `item/ItemAndInventory.kt` | 3, 2, 15 | What a `+N` upgrade is worth; a game may want a percentage (+3% per level) |
| `EquipmentLoadout.UNARMED_DAMAGE` | `item/ItemAndInventory.kt` | 2 | Bare-handed damage (was an inline `2` in four places) |
| `TARGET_RADIUS`, `COMBO_GRACE` | `combat/ActionCombatRules.kt` | 12, 20 | Action-combat target assist and combo window |
| `GuildRules.MAX_MEMBERS` | `social/GuildRules.kt` | 50 | Guild size |
| Party size limit | not located | | Check it is configurable |
| `LAST_SEEN_STAMP_TICKS`, `TICKS_PER_HOUR` | `sim/CombatSystem.kt` | 200, 72000 | Internal timing; probably fine as constants |
| Melee/ranged/spell formulas | `game.json` `combat` | | Already data |

## Suggested order
1. Gathering cooldown and the plus-level values (the survival design needs both).
2. Ranges, unarmed damage, guild and party size.
3. Action-combat targeting constants.

When one is moved into `game.json`, remove its row, update [game definition](/domain/game_definition.md), and add a test that the default is unchanged.
