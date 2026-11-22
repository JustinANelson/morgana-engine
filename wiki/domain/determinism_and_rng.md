---
type: Concept
title: "Determinism and Seeded Randomness"
description: "How the simulation stays reproducible: no wall clock, injected RNG, ordered collections."
tags: [determinism, rng]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/RandomSource.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt"]
symbols: ["SeededRandomSource", "rollBasisPoints", "nextId", "0xFADE2026L", "XorShift"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Determinism and Seeded Randomness

Same seed + same ordered inputs must give the same world. This enables reliable tests, bug reproduction, and [input-log replay](/systems/deterministic_replay.md).

## Rules for domain code
1. Never read the wall clock. Time is `currentTick` (20 per second). Durations are expressed in ticks.
2. Never call `Math.random()`/`kotlin.random`. Take randomness from `WorldSimulation.rng` (`RandomSource`).
3. Prefer `linkedMapOf`/ordered collections; iterate sorted keys when order affects results. (`WorldSimulation` state maps are `linkedMapOf`.)
4. Probabilities use **basis points** via `RandomSource.rollBasisPoints` (10000 = 100%).
5. Entity ids come from `nextId(prefix)`, a per-simulation counter, never UUIDs.

## RNG
`SeededRandomSource` is XorShift64*. The default simulation seed is the hex literal `0xFADE2026L`. That literal is a number, not
the old product name; it is kept so seeded outputs did not change in the Morgana rename ([rename decision](/decisions/0010_morgana_rename.md)).

Debugging guidance: [debug determinism](/playbooks/debug_determinism.md). Decision record: [ADR 0001](/decisions/0001_deterministic_domain_core.md).
