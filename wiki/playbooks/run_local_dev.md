---
type: Playbook
title: "Run Locally"
description: "Start the server and clients, seed data, and find the right ports and directories."
tags: [playbook, dev]
code: ["AGENTS.md", "server-app/build.gradle.kts"]
symbols: ["seedWorld", ":server-app:run"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Run Locally

```powershell
.\gradlew.bat :server-app:seedWorld   # optional: seeds <repo>/server-data/world (the server itself serves games/blank)
.\gradlew.bat :server-app:run                # authoritative server on port 7001 (watches content files)
.\gradlew.bat :client-desktop:run            # desktop client (can also start an embedded local server)
.\gradlew.bat :client-android:assembleDebug  # APK in client-android/build/outputs/apk/debug/
```
- Data directory: `<repo>/server-data` (both `run` tasks pin their working directory to the repo root; it is gitignored).
- The first account created on the machine running the server (or via the desktop client's embedded server) is Owner: press `F1` to edit. For a remote server, run `.\gradlew.bat :server-app:grantOwner -PcontentArgs="--user Alice"` first ([owner policy](/server/owner_policy.md), [F1 editor](/client/f1_editor.md)).
- Android emulator reaches the PC server at `10.0.2.2:7001` ([android](/architecture/client_android.md)).
- Port stuck: `lsof -i :7001`, `fuser -k 7001/tcp` (see `AGENTS.md` for tooling).
- Desktop client: the login window has a Local World picker and **New World & Play** (worlds live in `<repo>/worlds/<name>/`, gitignored; [world library](/server/world_library.md)). Share a world with `:server-app:exportWorldPack` ([world packs](/content/world_packs.md)).
- Switch off optional systems (trading, guilds, ...) per world with `:server-app:modules` / `server.json` ([optional modules](/server/optional_modules.md)).
- Store accounts/characters in SQLite instead of JSON files: stop the server, run `.\gradlew.bat :server-app:storage -PcontentArgs="--migrate sqlite"`, restart ([persistence layout](/server/persistence_layout.md)).
- Encrypted play: worlds created from the desktop client use TLS for every non-local connection automatically; use **Copy Invite Code for Friends** to share one. For a dedicated server set `"tls": {"mode": "required"}` in `server.json` and run `:server-app:tlsInfo` for the invite ([TLS](/server/tls.md)).
- Public servers: connection limits and IP bans are on by default; tune them with `connections` in `server.json` and manage bans in game with `/ipban`, `/ipunban`, `/ipbans` ([connection limits](/server/connection_limits.md)). Everything from this machine (loopback) is exempt.
- Reset the world: stop the server, delete `server-data/`, restart (reseeds, [blank game](/content/blank_game.md)).
- Before pushing: [verification gate](/playbooks/verification_gate.md).
