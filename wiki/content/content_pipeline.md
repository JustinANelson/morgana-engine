---
type: Concept
title: "Content Pipeline"
description: "The single validate, persist, hot-reload, broadcast path that GUI edits, file edits, and agents all share."
tags: [content, pipeline]
code: ["core-domain/src/main/kotlin/com/morgana/domain/validation", "server-persistence/src/main/kotlin/com/morgana/persistence/ContentLoader.kt", "server-app/src/main/kotlin/com/morgana/server/ContentWatcher.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt"]
symbols: ["ContentValidator", "ContentLoader", "ContentWatcher", "acceptEdit", "reloadContent"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Content Pipeline

Every authoring route converges on the same stages:

```
F1 editor / file edit / script / agent
        -> Validate (schema + ranges + cross-references)
        -> Persist (atomic JSON, one document per file)
        -> Hot-reload into the live simulation
        -> Broadcast deltas to clients
```
- **F1 route**: `Save*Definition` -> `acceptEdit` (validator) -> `applyLive*Edit` -> save -> `ContentCatalogUpdated`/`MapDefinitionUpdated`.
- **File route**: edit `server-data/world/...` -> `ContentWatcher` (debounced) or `/reload` -> `reloadContent()` (validate everything; apply only if error-free).
- **CLI route**: `validateContent` checks files with no server running ([gradle tasks](/reference/gradle_tasks.md)).

Rules: anything creatable in the GUI must be representable as a file and vice versa; validation errors are machine-readable (document, JSON pointer, code, message).
Pages: [collections](/content/content_collections.md), [validation rules](/content/validation_rules.md), [hot reload](/content/hot_reload.md), [agent playbook](/playbooks/author_content_as_files.md), rationale [ADR 0005](/decisions/0005_data_first_content.md).
