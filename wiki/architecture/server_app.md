---
type: Module
title: "Module: server-app"
description: "The dedicated authoritative server: Netty transport, coordinator, tick loop, owner policy, hot reload, and CLI tools."
tags: [module, server]
code: ["server-app/src/main/kotlin/com/morgana/server"]
symbols: ["MorganaServerRuntime", "MORGANA_CONTENT_WATCH", "MorganaNettyServer", "MorganaWorldCoordinator", "AsyncPersistenceWriter", "ContentWatcher", "EventHookRegistry", "OwnerPolicy"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Module: `:server-app`

| Class | Role |
| :--- | :--- |
| `MorganaServerMain` | Entry point for the dedicated server ([server runtime](/server/server_runtime.md)) |
| `MorganaServerRuntime` | Assembles store + coordinator + transport + file watcher; also used by the desktop embedded server |
| `MorganaNettyServer` | TCP transport and the 20 TPS scheduler ([wire format](/protocol/wire_format.md)) |
| `MorganaWorldCoordinator` | Single synchronized authority ([coordinator](/server/coordinator.md)) |
| `AsyncPersistenceWriter` | Off-lock background writer for periodic autosaves, exploration updates, and server-state snapshots |
| `OwnerPolicy` | Who becomes Owner ([owner policy](/server/owner_policy.md)) |
| `ContentWatcher` | Debounced file watcher triggering reload ([hot reload](/content/hot_reload.md)) |
| `EventHookRegistry` / `MorganaServerHook` | Plugin API ([hook API](/server/hook_api.md)) |
| `tools/ContentTool` | `validate` / `schema` / `seed` / `owner` CLI ([gradle tasks](/reference/gradle_tasks.md)) |

Run: `.\gradlew.bat :server-app:run` (working directory is pinned to the repo root, so data lives in `<repo>/server-data`).
Tests: `Phase4ServerCoordinatorTest`, `Phase11ContentReloadTest`, `SecurityHardeningTest`. Security overview: [security model](/reference/security_model.md).
