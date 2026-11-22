---
type: Playbook
title: "Debug a Determinism Problem"
description: "Checklist when two runs with the same seed and inputs diverge."
tags: [playbook, determinism]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/RandomSource.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/MorganaRepositories.kt"]
symbols: ["SeededRandomSource", "loadIntoSimulation"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Debug a Determinism Problem

Symptoms: flaky tests, different loot/crits between runs. Check, in order:
1. Wall-clock or `Math.random()` in `:core-domain` (`rg "currentTimeMillis|Math.random|kotlin.random" core-domain/src/main`).
2. Unordered iteration affecting results (`HashMap`/`HashSet`); the simulation uses `linkedMapOf`; sort keys when iterating externally supplied maps ([determinism](/domain/determinism_and_rng.md)).
3. RNG draw order changed (an extra or conditional `rng` call shifts every later roll); log draws around the suspect rule.
4. IDs from anything other than `nextId`.
5. Content load order: load definitions sorted by id, maps last (`loadIntoSimulation`) ([persistence layout](/server/persistence_layout.md)).
6. Tests that share a `WorldSimulation` across cases.
Use [deterministic replay](/systems/deterministic_replay.md) to bisect: record the failing session and compare checkpoint hashes to find the first diverging tick.
