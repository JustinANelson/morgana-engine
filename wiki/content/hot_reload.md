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

- `ContentWatcher` watches four trees of the active game and calls back ~400 ms after the last change, with the kinds that changed (one reload per burst): `content` (`.json` in `data/<collection>/`, including folders created later), `scripts` (`.lua` under `scripts/`), `assets` (any file under `assets/`, subfolders too) and `game` (`game.json`). Dot-files and temp files never count. Disable with env `MORGANA_CONTENT_WATCH=off`. It is started by `MorganaServerRuntime`, which both the dedicated server and the desktop client's embedded server use ([server runtime](/server/server_runtime.md)).
- `/reload` (Owner / `SERVER_MAINTENANCE`) triggers the same path and reports the summary to the caller.
- `reloadContent()`: load all files -> `ContentSalvage` -> diff against live definitions and swap only what changed, emit `ContentCatalogHotSwapped` and `MapHotSwapped`, and broadcast. **Per file, not all-or-nothing:** a document with an error (unreadable, half-written, a dangling reference...) keeps the version already live, or is left out if it is new, and every other file applies. Salvage validates again after each swap until the rest is clean; only errors it cannot set aside (rare) reject the whole reload.
- **The status file.** Every reload writes `<game>/.reload-status.json` (outside `data/`, so the watcher ignores it; atomic): `atMillis`, `applied`, `summary`, `changed` (`item#12`), `rejected` (per file: collection, id, file, `keptPrevious`, and the errors with JSON pointer, code and message) and `warnings`. An LLM or script that writes content reads it to see whether its files took and what to fix.
- Maps: compared ignoring `revision`; changed maps get `revision = max(file, live + 1)` and are re-registered. **Runtime state is kept where it can be:** a monster whose spawn slot (index, npc, home tile) is unchanged stays as it is, a resource node that is still the same resource on the same tile keeps its health and respawn timer, and a player standing on a tile that became a wall (or a blocking object) is moved to the nearest open tile (`settlePlayersOn`). **A map file deleted from disk is unloaded** (`unloadContentMap`): its players go to their class start, its monsters, loot, nodes and world objects go with it; map 1, a class start map and instance copies are never unloaded, and an empty maps folder is never taken to mean 'delete everything'.
- **Art**: a change under `assets/` calls `refreshAssets()`: the folder is rescanned and every new or changed file (by hash) is sent to connected players as `GameArtUpdated` with its PNG inline, the same packet an `F1` art import uses, so a generated tileset or sprite shows without a relog. A file that breaks the art rules is left out and listed under `assetErrors` in the status file.
- **`game.json`**: game rules are compiled at start and cannot be swapped into a running world, so a change is only noted: `restartRequired: true` in the status file and a warning in the log.
- F1 saves write files too, which retriggers the watcher harmlessly (no diff -> no-op).

Test: `Phase11ContentReloadTest`. Pipeline overview: [content pipeline](/content/content_pipeline.md); coordinator context: [coordinator](/server/coordinator.md).

## Not done yet
`game.json` changes need a restart (a safe swap of the rules that can change live, such as `tuning`, `schedules` and object kinds, is future work); the art push sends files inline, so one over the 4 MB art limit is rejected by the art rules, not streamed; no `MapDeleted` packet exists, so clients learn a map is gone only when their player is moved. Roadmap: `docs/DESIGN.md` (Next, "Live content hot-loading for generated content").
