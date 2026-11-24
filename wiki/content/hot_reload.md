---
type: Concept
title: "Hot Reload"
description: "How file edits and /reload update a running server, and what is deliberately not applied."
tags: [content, reload]
code: ["server-app/src/main/kotlin/com/morgana/server/ContentWatcher.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaServerMain.kt"]
symbols: ["MORGANA_CONTENT_WATCH", "ContentReloadResult", "debounceMs", "ContentCatalogHotSwapped"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Hot Reload

- `ContentWatcher` watches `server-data/world/<collection>/` (including directories created later) and calls the callback ~400 ms after the last `.json` change. Disable with env `MORGANA_CONTENT_WATCH=off`. It is started by `MorganaServerRuntime`, which both the dedicated server and the desktop client's embedded server use ([server runtime](/server/server_runtime.md)).
- `/reload` (Owner / `SERVER_MAINTENANCE`) triggers the same path and reports the summary to the caller.
- `reloadContent()`: load all files -> `ContentSalvage` -> diff against live definitions and swap only what changed, emit `ContentCatalogHotSwapped` and `MapHotSwapped`, and broadcast. **Per file, not all-or-nothing:** a document with an error (unreadable, half-written, a dangling reference...) keeps the version already live, or is left out if it is new, and every other file applies. Salvage validates again after each swap until the rest is clean; only errors it cannot set aside (rare) reject the whole reload.
- **The status file.** Every reload writes `<game>/.reload-status.json` (outside `data/`, so the watcher ignores it; atomic): `atMillis`, `applied`, `summary`, `changed` (`item#12`), `rejected` (per file: collection, id, file, `keptPrevious`, and the errors with JSON pointer, code and message) and `warnings`. An LLM or script that writes content reads it to see whether its files took and what to fix.
- Maps: compared ignoring `revision`; changed maps get `revision = max(file, live + 1)` and are re-registered (resets that map's NPC/resource runtime state). **Maps deleted from disk stay loaded until restart.**
- F1 saves write files too, which retriggers the watcher harmlessly (no diff -> no-op).

Test: `Phase11ContentReloadTest`. Pipeline overview: [content pipeline](/content/content_pipeline.md); coordinator context: [coordinator](/server/coordinator.md).

## Known gaps (roadmap: do before map authoring)
Only `.json` under `data/` is watched (not `game.json`, `scripts/` or `assets/`); changed maps lose their runtime state and deleted maps stay loaded; new assets are not pushed to connected clients. The plan is in `docs/DESIGN.md` (Next, "Live content hot-loading for generated content").
