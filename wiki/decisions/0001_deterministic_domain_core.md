---
type: Decision
title: "ADR 0001: Deterministic, Pure Domain Core"
description: "All game rules live in a pure Kotlin module with seeded randomness and tick-based time."
tags: [adr, determinism]
status: draft
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt"]
symbols: ["WorldSimulation", "RandomSource"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0001: Deterministic, Pure Domain Core

**Status**: Accepted.

**Context**: Mirage-style engines tangle rules with networking and rendering, making them hard to test and to keep authoritative.

**Decision**: Put all state transitions and formulas in `:core-domain`, with no I/O, wall-clock, or unseeded randomness; time is ticks, randomness is an injected `RandomSource` ([determinism](/domain/determinism_and_rng.md)).

**Consequences**
- Server and client can share movement/collision rules (client prediction); tests need no network or GL.
- Enables replay and golden-master testing (roadmap Phase 12; [roadmap](/reference/roadmap_status.md)).
- Contributors must obey the purity rules or tests lose meaning ([core domain](/architecture/core_domain.md)).

**Alternatives**: rules in server classes (as in Intersect), rejected for testability.

> Rationale source: reconstructed from the code, design docs, and roadmap; confirm with the original author and then add a `verified` entry.
