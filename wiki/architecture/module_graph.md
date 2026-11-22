---
type: Architecture
title: "Module Graph and Dependency Rules"
description: "The Gradle module layout, who may depend on whom, and why the boundaries exist."
tags: [architecture, gradle]
code: ["settings.gradle.kts", "core-domain/build.gradle.kts", "shared-protocol/build.gradle.kts", "server-persistence/build.gradle.kts", "server-app/build.gradle.kts", "client-core/build.gradle.kts", "client-desktop/build.gradle.kts", "script-luak/build.gradle.kts", "client-web/build.gradle.kts", "plugin-example/build.gradle.kts", "dev-tools/build.gradle.kts", "tilegen/build.gradle.kts"]
symbols: ["kotlinx-serialization-core", "gdx-backend-lwjgl3", "netty-all"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Module Graph and Dependency Rules

```
client-desktop --> client-core --> shared-protocol --> core-domain
client-core --> tilegen  (pure Kotlin, no project or library dependencies)
game-art --> tilegen    (tooling for the bundled games; no engine module depends on it)
client-android --> client-core
client-desktop --> client-net-netty --> client-core
client-android --> client-net-netty
script-luak --> luak-core-jvm (vendored in third-party/)
client-web --> client-core
client-desktop --> server-app (embedded local server) --> server-persistence --> core-domain
server-app --> shared-protocol, core-domain
dev-tools  (standalone: snakeyaml only)
```

## Actual Gradle dependencies (verified against the build files)
| Module | Depends on |
| :--- | :--- |
| `core-domain` | kotlinx-serialization-core (`api`) only |
| `shared-protocol` | core-domain (`api`), serialization json + cbor |
| `server-persistence` | core-domain (`api`), serialization json |
| `server-app` | core-domain, shared-protocol, server-persistence, Netty, coroutines, SLF4J, Logback (runtime) |
| `game-tests` | Tests of the bundled games' content (test-only module; engine tests never depend on a game) |
| `tilegen` | Kotlin stdlib only; procedural 32x32 tile generation ([tilegen](/architecture/tilegen.md)) |
| `game-art` | tilegen; per-game sprite kits and exporters ([game-art](/architecture/game_art.md)) |
| `client-core` | core-domain + shared-protocol + tilegen (`api`), LibGDX, KTX, VisUI, coroutines (no networking library) |
| `script-luak` | the only module that may depend on Luak (vendored, checksum-pinned); today only smoke tests, the script host and its `ScriptRuntime` port arrive in phase 2 |
| `client-net-netty` | client-core, Netty; `MorganaNetClient`, the TCP `ClientTransport` used by desktop and Android |
| `client-desktop` | client-core, **server-app**, server-persistence, LWJGL3 backend |
| `client-web` | client-core, TeaVM JSO, gdx-teavm plugin; compiled to JavaScript, no server or Netty code |
| `client-android` | client-core, Android backend + natives |
| `plugin-example` | compile-only against server-app, core-domain and shared-protocol; builds `hello-plugin.jar`, an example [server plugin](/playbooks/write_a_server_plugin.md) that is never part of the server |
| `dev-tools` | snakeyaml (tooling only) |

## Rules
- **Engine/game boundary**: engine modules never name a shipped game or build `games/` paths beyond the listed tooling files; `EngineBoundaryTest` (`:dev-tools`) enforces it and lists the known exceptions, which may only shrink ([ADR 0013](/decisions/0013_engine_game_split_and_lua_scripting.md)).
- `:core-domain` must never import Netty, LibGDX, filesystem, or clock APIs ([core domain](/architecture/core_domain.md)).
- The client and server share the same domain classes; the server stays authoritative ([client prediction](/client/client_state_and_prediction.md)).
- Persistence is behind the `DocumentCollection` port ([persistence layout](/server/persistence_layout.md)).
- Adding a module requires: an entry in `settings.gradle.kts`, a row here, and a module page in this section.

The desktop client depends on `:server-app` so it can start an embedded local server ([client desktop](/architecture/client_desktop.md)).
