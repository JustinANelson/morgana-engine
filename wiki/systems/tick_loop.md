---
type: Simulation System
title: "Tick Loop and System Layout"
description: "The exact order of work in one simulation tick and how WorldSimulation delegates to system files."
tags: [simulation, tick]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim"]
symbols: ["stepTick", "tickProjectiles", "tickPlayers", "tickNpcs", "unlockedDoors", "respawnAtTick"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Tick Loop and System Layout

`WorldSimulation.stepTick(deltaMs = 50)` increments `currentTick`, then runs, in order:
1. Expire temporarily unlocked doors (`unlockedDoors`).
2. Respawn depleted resource nodes whose `respawnAtTick` has arrived (emits `ResourceNodeChanged`).
3. Despawn old ground items (`despawnAtTick`), emitting `GroundItemsChanged`.
4. Advance projectiles ([combat](/systems/combat.md)).
5. Players: status effects, natural regeneration, heal/trap tiles ([combat](/systems/combat.md)).
6. NPCs: respawn, status, AI, attacks, movement ([NPC AI](/systems/npc_ai.md)).

It returns the events produced; it performs no I/O ([simulation events](/domain/simulation_events.md)).

## Layout
`WorldSimulation.kt` is a facade: state maps, `registerMap`, `spawnOrUpdatePlayer`, `removePlayer`, `stepTick`, plus thin public methods that call
`internal` extension functions named `<method>Impl` in the system files:

| File | Concern |
| :--- | :--- |
| `MovementSystem.kt` | [Movement](/systems/movement.md) |
| `NpcAiSystem.kt` | [NPC AI](/systems/npc_ai.md) |
| `CombatSystem.kt` | [Combat](/systems/combat.md) |
| `InventorySystem.kt` | [Interaction and inventory](/systems/inventory_interaction.md) |
| `EconomySystem.kt` | [Shops, bank, crafting](/systems/economy.md) |
| `TradeSystem.kt` | [Trading](/systems/trading.md) |
| `EventSystem.kt` | [Events and quests](/systems/events_quests.md) |
| `AdminSystem.kt` | [Admin and live edits](/systems/admin_live_edit.md) |

Why this shape: [ADR 0006](/decisions/0006_system_decomposition.md). Server tick scheduling: [coordinator](/server/coordinator.md).
