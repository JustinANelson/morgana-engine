---
type: Decision
title: "ADR 0004: One Synchronized Coordinator"
description: "All server state changes serialize through one lock instead of fine-grained concurrency."
tags: [adr, concurrency]
status: draft
code: ["server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt"]
symbols: ["@Synchronized", "stepServerTick"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0004: One Synchronized Coordinator

**Status**: Accepted.

**Decision**: `MorganaWorldCoordinator` entry points are `@Synchronized`. Netty threads, the tick thread, and the file watcher contend for one lock ([coordinator](/server/coordinator.md)).

**Consequences**: no data races in the simulation (which is single-threaded by design, [ADR 0001](/decisions/0001_deterministic_domain_core.md)); simple reasoning; throughput is bounded by one core and slow work inside handlers (file reloads) delays ticks.
Periodic autosaves and exploration writes are offloaded off-lock to [`AsyncPersistenceWriter`](/architecture/server_app.md) with per-character generation counters so an older queued write can never overwrite a newer synchronous save (such as on disconnect or shutdown).

> Rationale source: reconstructed from the code, design docs, and roadmap; confirm with the original author and then add a `verified` entry.
