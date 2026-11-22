---
type: Decision
title: "ADR 0002: JSON Document Persistence"
description: "World and player data are one human-readable JSON file per document behind a storage port."
tags: [adr, persistence]
status: draft
code: ["server-persistence/src/main/kotlin/com/morgana/persistence/DocumentStore.kt"]
symbols: ["DocumentCollection", "JsonFileDocumentCollection"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0002: JSON Document Persistence

**Status**: Accepted (provisional beyond the JSON adapter).

**Decision**: Store each document as `<id>.json` with atomic writes behind the `DocumentCollection` port ([persistence layout](/server/persistence_layout.md)).

**Why**: zero-setup hosting (Mirage/Player Worlds simplicity), Git-diffable content, trivially editable by humans and agents ([ADR 0005](/decisions/0005_data_first_content.md)).

**Consequences**: no queries or transactions; `findAll` scans files (fine at hobby scale); player data concurrency relies on the [single coordinator lock](/decisions/0004_single_synchronized_coordinator.md).
The roadmap originally planned MongoDB; the preferred extension is now **SQLite behind the same port**, with Mongo optional ([roadmap](/reference/roadmap_status.md)).

**Update (Phase 16)**: SQLite exists for *player data only*, opt-in via `server.json`; world content stays JSON files by design. See [persistence layout](/server/persistence_layout.md).

> Rationale source: reconstructed from the code, design docs, and roadmap; confirm with the original author and then add a `verified` entry.
