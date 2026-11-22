---
type: Module
title: "Module: client-desktop"
description: "The LWJGL3 desktop launcher, including the embedded local server."
tags: [module, client, desktop]
code: ["client-desktop/src/main/kotlin", "client-desktop/build.gradle.kts"]
symbols: ["onEnsureLocalServer", "Lwjgl3Application", "MorganaServerRuntime", "EmbeddedWorldHost"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Module: `:client-desktop`

`DesktopLauncher.kt` creates a 1280x768 vsynced window (60 FPS foreground) and a `MorganaGame`.
Defaults: host `127.0.0.1`, port `7001` (overridable by args).

The game receives an `onEnsureLocalServer` callback that lazily starts an **embedded server** with `MorganaServerRuntime.start("server-data", port)`: the same assembly the dedicated server uses, so it also hot-reloads edited content files and applies the [owner policy](/server/owner_policy.md) - the first account created on this machine (a loopback connection) becomes Owner. See [server runtime](/server/server_runtime.md).

Since Phase 13 the launcher wires an `EmbeddedWorldHost` (a `LocalWorldHost`) into the login window: local world picker plus **New World & Play** ([world library](/server/world_library.md)). `onEnsureLocalServer` now delegates to it.

Run: `.\gradlew.bat :client-desktop:run` (working directory pinned to the repo root).
