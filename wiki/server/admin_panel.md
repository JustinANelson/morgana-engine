---
type: Reference
title: "Admin Web Panel and REST API"
description: "The operator's web panel and JSON API: how to turn it on, the token, every endpoint, what it can do, the security rules and how it is built."
tags: [admin, moderation, metrics, http, security]
code: ["server-app/src/main/kotlin/com/morgana/server/admin/AdminApi.kt", "server-app/src/main/kotlin/com/morgana/server/admin/AdminHttpServer.kt", "server-app/src/main/kotlin/com/morgana/server/admin/AdminToken.kt", "server-app/src/main/kotlin/com/morgana/server/admin/AdminBackend.kt", "server-app/src/main/kotlin/com/morgana/server/admin/CoordinatorAdminBackend.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-app/src/main/resources/admin/app.js", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt"]
symbols: ["AdminApi", "AdminHttpServer", "AdminToken", "AdminBackend", "CoordinatorAdminBackend", "AdminConfig", "adminStatus", "adminKick", "adminSanction", "recordTick"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-14T16:00:00Z }
stale_after: 2027-05-13T00:00:00Z
---

# Admin Web Panel and REST API

A browser page and a JSON API for the person running a realm: who is online, kick, mute and ban, the report queue, the audit log, a content reload, announcements, and a graph of players and tick time. It does what the [chat moderation commands](/server/moderation.md) do, without being logged in to the game, and every action is audited as `web-admin`.

## Turning it on
Off by default. Add to `<data dir>/server.json` (see [optional modules and server.json](/server/optional_modules.md)):
```json
{ "schemaVersion": 1, "admin": { "enabled": true, "port": 7003 } }
```
Restart the server, then open `http://127.0.0.1:7003/`. The token is in `<data dir>/admin-token.txt`, made with 256 random bits the first time the panel is enabled (or set the `MORGANA_ADMIN_TOKEN` environment variable, at least 16 characters, for hosts that inject secrets). The token is never logged. `admin.bindAddress` defaults to `127.0.0.1`; `admin.port` `0` picks a free port.

**Reaching it from another computer**: the panel is plain HTTP, so use an SSH tunnel (`ssh -L 7003:127.0.0.1:7003 host`) or a reverse proxy that adds TLS. A `bindAddress` that is not this machine is refused at start unless `"allowRemote": true` says you accept sending the token unencrypted.

## Security rules (`AdminApi`, `AdminApiTest`)
- Every `/api/` call needs `Authorization: Bearer <token>`, compared in constant time. The page itself (`/`, `/app.js`, `/admin.css`) is served without it because it holds nothing secret and must load before it can ask.
- A wrong token is counted per client address; 10 in a minute gives that address 429 until the minute passes.
- Bodies over 8 KB are refused; names, reasons and messages are length-limited; unknown JSON fields are ignored, wrong shapes are 400.
- The page is served with `Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; ...`, `no-store`, `nosniff`, `X-Frame-Options: DENY`, and never writes server data as HTML (`textContent` only). There are no cookies and no CORS headers, so another website cannot use the API even in a browser that has the panel open.
- The token is kept in the browser's `sessionStorage` only (gone when the tab closes); Sign out clears it.

## Endpoints
All answer JSON; a refused request is `{"error": "..."}` with 400, 401, 404, 405, 413 or 429.

| Request | Does |
| :--- | :--- |
| `GET /api/status` | tick, uptime, players online, accounts, characters, maps, average and worst tick time, heap, disabled modules, open reports, active bans and mutes |
| `GET /api/metrics` | one sample a minute for the last three hours: players online and average tick time |
| `GET /api/players` | who is online: character and account ids, level, map, tile, access level |
| `POST /api/players/kick` `{name, reason}` | disconnect them |
| accounts, characters, tickets, warp, give, content | see [agent control](/server/agent_control.md) |
| `POST /api/players/mute` `{name, duration, reason}`; `.../unmute` `{name}` | mute (`30m`, `12h`, `7d`, `perm`) |
| `POST /api/players/ban` `{name, duration?, reason}`; `.../unban` `{name}` | ban (no duration = permanent); an online player is disconnected |
| `GET /api/sanctions?kind=ban\|mute` | active bans or mutes |
| `GET /api/reports`; `POST /api/reports/close` `{id, note}` | the open report queue |
| `GET /api/audit?name=&limit=` | the audit log (newest last, at most 500) |
| `POST /api/reload` | the same content reload as `/reload` and the F1 editor |
| `POST /api/broadcast` `{message}` | an announcement on the broadcast channel |

Example: `curl -H "Authorization: Bearer $(cat server-data/admin-token.txt)" http://127.0.0.1:7003/api/players`.

Unlike the chat commands, the API does not check the target's access level: whoever holds the token is the operator.

## How it is built
`AdminApi` (pure routing, authentication, throttling, validation; tested with a fake `AdminBackend`) is fed by `AdminHttpServer`, a small separate Netty listener whose handler runs on its own two threads so a call that waits for the world lock never stalls a socket thread. `CoordinatorAdminBackend` forwards to `@Synchronized` operations on `MorganaWorldCoordinator` (`adminStatus`, `adminKick`, `adminSanction`, ...), so the panel is serialised with the game exactly like a player's packet. `MorganaServerRuntime.start` creates it when `admin.enabled` is true and closes it first on shutdown. The coordinator times every `stepServerTick` (an exponential average and the worst tick of the last minute) and keeps up to 180 once-a-minute samples for `/api/metrics`. The panel files are classpath resources under `admin/` (`index.html`, `admin.css`, `app.js`, no libraries, no build step). `AdminServerEndToEndTest` drives a real runtime over a real socket; `AdminConfigTest` covers the `server.json` rules.

## Not done yet
Per-moderator accounts and roles (there is one token), TLS in the panel itself, a live player list push (it refreshes every 5 seconds), alert thresholds that notify someone, content editing, player and character inspection, report context, and metrics kept across a restart.
