---
type: Concept
title: "World Library and Embedded Host"
description: "Independent per-world data folders under worlds/, and the desktop client's one-click New World / Play flow."
tags: [worlds, embedded-server, desktop]
code: ["server-app/src/main/kotlin/com/morgana/server/worlds/WorldLibrary.kt", "client-desktop/src/main/kotlin/com/morgana/client/desktop/DesktopLauncher.kt", "client-core/src/main/kotlin/com/morgana/client/LocalWorldHost.kt"]
symbols: ["WorldLibrary", "WorldMeta", "EmbeddedWorldHost", "LocalWorldHost", "ensureRunning", "slugify"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# World Library and Embedded Host

## WorldLibrary (`:server-app`)
Each world is a complete server data directory (`world/`, `accounts/`, `characters/`, `social/`) at `worlds/<slug>/`, marked by a `world.meta.json` (`name`, `origin`, `createdAtMillis`). Directories without that file are ignored. The legacy `server-data/` directory is always listed first as the `default` world, so existing installs are unaffected. Any library world can also be served headless or exported as a [world pack](/content/world_packs.md).

- `create(name, pack?)` makes a Blank Game realm ([blank game](/content/blank_game.md)) or imports the pack (`NEW_ONLY`, validated). Slugs are `[a-z0-9-]`, made unique with `-2`, `-3`, and never equal `default`. A failed create removes its folder.
- `create(RealmBlueprint)` is the richer form used by the [New Realm Wizard](/server/new_realm_wizard.md): choose a template (starter, a blank game, a game discovered under `games/`, or a pack) and which optional modules are off; the result is validated and rolled back on any failure.
- Tools: `:server-app:newWorld -PcontentArgs="--name Camp [--template starter|classic|blank|pack] [--pack p.zip] [--disable trading,guilds]"`, `:server-app:listWorlds`.

## One-click play (`:client-desktop`)
`:client-core` defines `LocalWorldHost` (list worlds, `selectedSlug`, `createWorld`, `ensureRunning`); the desktop launcher implements it as `EmbeddedWorldHost`. The login window then shows a **Local World** picker and a **New World & Play** button, which creates a fresh starter world, selects it, and connects to `127.0.0.1`.

`ensureRunning()` starts a `MorganaServerRuntime` for the selected world's data dir (same assembly as the dedicated server: hot reload, owner policy). If another world was running it is shut down first, so only one embedded server exists. If the port is already taken (e.g. a dedicated server), the start failure is logged and the client connects to whatever is listening. Owner rights follow the usual [owner policy](/server/owner_policy.md): the first account on a new world connecting over loopback becomes Owner, so the creator can use `F1` immediately.

Not covered by automated tests: the Scene2D login-window layout itself (only `EmbeddedWorldHost` is tested headlessly). Android has no embedded host.
