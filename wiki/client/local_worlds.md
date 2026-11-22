---
type: Reference
title: "Local Worlds (Host Your Own Realm)"
description: "The desktop hub's Local Worlds tab: the worlds on this machine, what is being hosted, and play, share, stop, rename, delete and open-folder for each."
tags: [client, ui, realms, hosting]
code: ["client-core/src/main/kotlin/com/morgana/client/ui/MorganaRealmsHubWindow.kt", "client-core/src/main/kotlin/com/morgana/client/realms/LocalWorldsModel.kt", "client-core/src/main/kotlin/com/morgana/client/preview/DemoLocalWorlds.kt", "client-core/src/main/kotlin/com/morgana/client/LocalWorldHost.kt", "client-desktop/src/main/kotlin/com/morgana/client/desktop/DesktopLauncher.kt", "server-app/src/main/kotlin/com/morgana/server/worlds/WorldLibrary.kt", "smoke-test/local-worlds-look.mjs"]
symbols: ["LocalWorldHost", "LocalWorldDetails", "LocalHostingStatus", "LocalWorldsModel", "DemoLocalWorldHost", "fakeWorldsFromQuery", "EmbeddedWorldHost", "WorldSummary", "WorldLibrary"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-13T09:00:00Z }
stale_after: 2027-05-12T00:00:00Z
---

# Local Worlds (Host Your Own Realm)

The third tab of the [realms hub](/architecture/client_core.md), shown only where the client can run a server on the same machine (the desktop client; the web and Android clients have no local hosting, so no tab). It used to be a drop-down, a wizard button and an invite button. It is now where someone who wants to run a world for themselves and their friends does everything.

## What the player sees
- **A status line** at the top: `Hosting Dungeon Test on port 7001 - 2 players online`, or that nothing is being hosted, and what friends type to join (the machine's network address and port, with a reminder to forward the port for friends elsewhere). It refreshes every two seconds while the tab is open.
- **A list of worlds** (left, or on top on a narrow screen): the name, and a second line with where it came from (Blank game, Copy of <game>, Imported world pack), its map count and when it was last played or "hosting now". The default world is listed first.
- **A details pane** for the selected world: origin, maps and classes, which optional systems are switched off, "Hosting now", the folder on disk, and the actions:
  - **Play** points the sign-in at this machine and signs in (starting the world's server, and stopping another hosted world first; a warning shows when that world has players online).
  - **Copy Invite Code** starts the world if needed and puts a `morgana://` invite on the clipboard.
  - **Stop hosting** (only on the hosted world) shuts the server down through its normal save path.
  - **Open folder** opens the world's folder in the file manager.
  - **Rename** edits the display name in place (the folder and the saved characters are untouched).
  - **Delete...** asks first ("its maps, accounts and characters are removed for good and cannot be brought back"), then removes the world, stopping its server first if it is the hosted one.
- The default world (the legacy `server-data/` folder) cannot be renamed or deleted. **New Realm Wizard...** (or a name box plus **Create World** on a host without templates) sits under the list; a created world is selected.

## How it works
- `LocalWorldHost` (in `:client-core`) gained `details`, `hostingStatus`, `stopHosting`, `renameWorld`, `deleteWorld`, `openFolder` and `canManageWorlds`, all with defaults so a host that cannot do them still compiles. The desktop `EmbeddedWorldHost` implements them on top of `WorldLibrary`, which gained `summary`, `rename`, `delete` and `markPlayed` (the play time is stored as `lastPlayedAtMillis` in `world.meta.json`, an additive field).
- `WorldLibrary.delete` only removes a folder directly under `worlds/` that has this library's `world.meta.json`; it refuses the default world, empty names, `.`/`..` and anything with a path separator. It runs after the host has stopped the server using the folder.
- The player count comes from `MorganaWorldCoordinator.onlineCount`, a plain size read, so it can lag a tick behind.
- `LocalWorldsModel` holds the wording (no widgets) and is unit-tested. The UI font has no middle-dot glyph, so lines use ` - `.

## Previewing without a server
`DemoLocalWorldHost` is an in-memory pretend host. Open the web client with `?fakeWorlds=1` to see the tab with four made-up worlds (one "hosted" with two players). `smoke-test/local-worlds-look.mjs` takes screenshots at a wide and a narrow size into `build/local-worlds-look/` and can click through the flow: `node local-worlds-look.mjs 400,335 "Delete..." "Yes, delete it forever"` (browser-pixel positions or button labels).

## Tests
`WorldLibraryManagementTest` (summary, play time, rename, delete and its refusals), `EmbeddedWorldHostTest` (status, stop, rename, delete while hosting), `LocalWorldsModelTest` (the wording).

## Not done yet
Choosing the port or a "friends can join" switch (the embedded server always listens on the port the client was started with, encrypting every connection except this machine's own); showing who is online or kicking from the tab; copying or exporting a world as a pack from the tab (the CLI does it); a live reachability check for the forwarded port; the character picker and per-world server settings. See the [New Realm Wizard](/server/new_realm_wizard.md) for creating worlds.
