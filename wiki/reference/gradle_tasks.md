---
type: Reference
title: "Gradle Tasks"
description: "Every project task a contributor or agent needs, with arguments."
tags: [gradle, tasks]
code: ["build.gradle.kts", "server-app/build.gradle.kts", "dev-tools/build.gradle.kts", "script-luak/build.gradle.kts"]
symbols: ["validateContent", "validateScripts", "exportContentSchemas", "seedWorld", "wikiLint", "wikiIndex", "wikiImpact", "grantOwner", "verifyThirdParty", "stressTest"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Gradle Tasks (Windows: `.\gradlew.bat`)

| Task | Purpose |
| :--- | :--- |
| `check` | Full gate: tests, Android lint, wiki lint ([gate](/playbooks/verification_gate.md)) |
| `test` / `cleanTest test --no-build-cache` | Unit/integration tests (force execution) |
| `:server-app:run` | Run the server (cwd = repo root) |
| `:server-app:stressTest` | Benchmark 20 TPS tick times across ramping player counts (`-PstressArgs="--counts 25,50,100,200 --ticks 400 --layout spread --aoi --sqlite"`) |
| `:client-desktop:run` | Run the desktop client |
| `:client-android:assembleDebug` | Build the debug APK |
| `:server-app:gameDefinition` | Validate `game.json` (or show the built-in Morgana Classic); `-PcontentArgs="--write"` creates `game.json` from the built-in one ([game definition](/domain/game_definition.md)) |
| `:verifyThirdParty` | Check the vendored files in `third-party/` against `third-party/checksums.sha256` (runs before `:script-luak` compiles and in `check`) |
| `:script-luak:test` | Smoke tests for the vendored Luak runtime (loads from `third-party/`, sandbox limits work) |
| `:server-app:validateContent` | Validate `server-data/world`; `-PcontentArgs="--json"` for JSON output; exit 1 on errors |
| `:server-app:validateScripts` | Validate all Lua scripts: `-PcontentArgs="--dir <path>"`; exit 1 on errors |
| `:server-app:exportContentSchemas` | Write `docs/schemas/*.schema.json` |
| `:server-app:seedWorld` | Seed `server-data/world` with the blank game's content if it has none |
| `:client-web:dist` | Package the minified release web client into `client-web/build/dist/morgana-realms-client-html.zip` for itch.io / static hosting |
| `:distribution:sdk`, `:distribution:sdkZip` | Assemble the developer SDK folder / zip ([build the SDK](/playbooks/build_the_sdk.md)) |
| `distributables` | Build all distributables (SDK zip, Android debug APK, and web client zip) into `build/distributables/` |
| `:server-app:grantOwner` | List a username as Owner in `server-data/owners.txt`: `-PcontentArgs="--user Alice"` ([owner policy](/server/owner_policy.md)) |
| `:dev-tools:wikiLint` | Lint the wiki |
| `:dev-tools:wikiIndex` | Regenerate `wiki/index.md` |
| `:dev-tools:wikiImpact` | Pages affected by changes: `-PwikiArgs="--git HEAD~1"` or file paths |

**Shell tip**: run these from PowerShell. From Git Bash, `gradlew.bat` mis-parses `-P` values that contain spaces (e.g. `-PwikiArgs="--git HEAD~1"`) because the repository path also contains a space.

Tools are implemented in `ContentTool.kt` and `WikiTool.kt` ([server app](/architecture/server_app.md), [dev tools](/architecture/dev_tools.md)).
