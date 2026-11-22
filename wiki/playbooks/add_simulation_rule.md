---
type: Playbook
title: "Add a Simulation Rule"
description: "How to add gameplay behavior in the right system file while preserving determinism."
tags: [playbook, simulation]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt"]
symbols: ["stepTick", "nextId", "Impl"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Add a Simulation Rule

1. Choose the system file ([tick loop](/systems/tick_loop.md)); create a new `XSystem.kt` only for a genuinely new domain.
2. Write `internal fun WorldSimulation.myRuleImpl(...) : List<WorldSimulationEvent>`; add a one-line public delegate `fun myRule(...) = myRuleImpl(...)` in `WorldSimulation` (never name the extension the same as the member).
3. Rules: ticks not milliseconds; `rng` not random; ordered iteration; ids from `nextId`; no I/O ([determinism](/domain/determinism_and_rng.md)).
4. Return events instead of sending packets ([simulation events](/domain/simulation_events.md)).
5. If it needs per-tick work, hook it into `stepTick` and document its position in the order.
6. Add a test next to `Phase2WorldSimulationTest` with a fixed seed; update the matching system page and [glossary](/glossary.md) if new terms appear.
Rationale: [ADR 0006](/decisions/0006_system_decomposition.md).
