---
type: Decision
title: "ADR 0006: Decompose WorldSimulation into Extension-Function Systems"
description: "Why the 2,466-line simulation class became a facade plus per-domain system files."
tags: [adr, refactor]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim"]
symbols: ["movePlayerImpl", "playerAttackImpl", "executeEventCommandsImpl"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0006: Decompose `WorldSimulation` into Extension-Function Systems

**Status**: Accepted (done in roadmap Phase 10).

**Context**: `WorldSimulation.kt` had grown to 2,466 lines, violating the module-decoupling mandate in spirit.

**Decision**: Keep `WorldSimulation` as the state-owning facade; move behavior into `internal fun WorldSimulation.<name>Impl(...)` extension functions in `MovementSystem`, `NpcAiSystem`, `CombatSystem`, `InventorySystem`, `EconomySystem`, `TradeSystem`, `EventSystem`, `AdminSystem` ([tick loop](/systems/tick_loop.md)). Public methods stay as one-line delegates.

**Why extensions, not classes**: function bodies were moved byte-for-byte (references to shared state such as `players`, `rng`, `currentTick` keep working), so behavior was provably unchanged and callers were untouched. The `Impl` suffix avoids a member/extension name clash (the member would shadow the extension).

**Consequences**: shared state remains one big mutable bag (acceptable for a single-threaded simulation); a future step is to give systems narrower state views. Add new rules following [add a simulation rule](/playbooks/add_simulation_rule.md).
