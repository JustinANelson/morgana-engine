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
- `reloadContent()`: load all files -> full validation -> **if any ERROR, apply nothing** (live world untouched, errors logged/returned) -> otherwise diff against live definitions and swap only what changed, emit `ContentCatalogHotSwapped` and `MapHotSwapped`, and broadcast.
- Maps: compared ignoring `revision`; changed maps get `revision = max(file, live + 1)` and are re-registered (resets that map's NPC/resource runtime state). **Maps deleted from disk stay loaded until restart.**
- F1 saves write files too, which retriggers the watcher harmlessly (no diff -> no-op).

Test: `Phase11ContentReloadTest`. Pipeline overview: [content pipeline](/content/content_pipeline.md); coordinator context: [coordinator](/server/coordinator.md).
