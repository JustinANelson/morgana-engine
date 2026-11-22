---
type: Concept
title: "Server Runtime"
description: "The single assembly point for a running server, shared by the dedicated server and the desktop embedded server."
tags: [server, runtime]
code: ["server-app/src/main/kotlin/com/morgana/server/MorganaServerRuntime.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaServerMain.kt", "client-desktop/src/main/kotlin"]
symbols: ["MorganaServerRuntime", "WATCH_ENV", "watchContent", "MorganaServerRuntime.start"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Server Runtime (`MorganaServerRuntime`)

With `MORGANA_RECORD_REPLAY=<file>` set it also records a [replay log](/systems/deterministic_replay.md) written on `close()`.

`MorganaServerRuntime.start(dataDir, port, watchContent)` builds the `MorganaDataStore`, `MorganaWorldCoordinator`, and `MorganaNettyServer`, starts the transport, and (unless `MORGANA_CONTENT_WATCH=off`) the `ContentWatcher` that calls `reloadContent()`. `close()` stops watcher and server; `port` reports the bound port (use 0 in tests).

Used by `MorganaServerMain` (dedicated server) and `DesktopLauncher` (embedded local server), so both get identical behavior including [hot reload](/content/hot_reload.md) and the [owner policy](/server/owner_policy.md).
Test: `SecurityHardeningTest` (file edit is picked up through the runtime). Module overview: [server app](/architecture/server_app.md).

The same web port also serves the data-driven [game wiki](/server/game_wiki.md) at `/wiki/` (items, monsters, maps and a balance report built from the game's data files; off with `"wiki": { "enabled": false }` in `server.json`).
