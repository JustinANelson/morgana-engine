---
type: Playbook
title: "Build and Try the SDK"
description: "Package the engine as a self-contained SDK folder and zip for other developers, what is in it, and how to test it as a newcomer would."
tags: [distribution, sdk, release, playbook]
code: ["distribution/build.gradle.kts", "distribution/src/bin/launcher.bat", "distribution/src/bin/launcher.sh", "distribution/src/root/README.md", "server-app/src/main/kotlin/com/morgana/server/MorganaServerMain.kt", "server-app/src/main/resources/logback.xml"]
symbols: ["sdk", "sdkZip", "resolveServerGameDir", "MORGANA_JAVA_OPTS"]
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-13T12:00:00Z }
stale_after: 2027-05-12T00:00:00Z
---

# Build and Try the SDK

The `:distribution` module packages the engine for developers who do not build it from source. They need only Java 17+.

## Build
- `.\gradlew.bat distributables` builds all project distributables (the SDK zip and the Android debug APK) and copies them into `build/distributables/` at the repository root.
- `.\gradlew.bat :distribution:sdk` assembles `distribution/build/sdk/morgana-sdk-<version>/`.
- `.\gradlew.bat :distribution:sdkZip` zips it to `distribution/build/distributions/morgana-sdk-<version>.zip` (about 50 MB). The version is the root project's `version`.

## Contents
| Path | From |
| :--- | :--- |
| `bin/morgana-client`, `morgana-server`, `morgana` (tools, `ContentToolKt`), `morgana-art`, `morgana-tileset` | `distribution/src/bin/launcher.{bat,sh}`, one copy per entry point with `@MAIN@` and friends filled in; `.bat` with CRLF, the shell scripts with LF and mode 755 |
| `lib/` | The runtime classpath of `:client-desktop` (it depends on the server, persistence, scripting and tools, so it is every jar) |
| `games/blank/` | The repository's [blank game](/content/blank_game.md) |
| `web/` | The minified browser client (`:client-web:gdx_teavm_web_js_release_build`), served by `morgana-server --web` ([play on a phone](/playbooks/play_on_a_phone.md)) |
| `games/roguelike/` | [Lanternfall](/content/roguelike_game.md), the reference co-op roguelike |
| `games/tutorial-village/` | [Tutorial Village](/content/tutorial_village.md), the finished game from `docs/GETTING_STARTED.md` |
| `docs/` | `GETTING_STARTED.md`, `CREATOR_GUIDE.md`, `API_REFERENCE.md`, `GAME_DEFINITION.md`, `GENRES_AND_MODULES.md`, `PLUGIN_ARCHITECTURE.md` (the guide links to the last two) and `schemas/` |
| `licenses/` | `third-party/licenses` |
| `README.md` | `distribution/src/root/README.md`: requirements, quick start, creating a realm and a game, the tool commands |
| `worlds/`, `server-data/` | Empty, so the layout is visible |

The launchers check that Java runs (`JAVA_HOME` or `PATH`), **change to the SDK folder** so relative paths always mean the folders next to `bin/`, pass `MORGANA_JAVA_OPTS` to the JVM, and on macOS add `-XstartOnFirstThread` for the client. Logging defaults to INFO with Netty at WARN (`server-app/src/main/resources/logback.xml`, which also applies when running from source).

## Which game a server serves
`morgana-server` picks (`resolveServerGameDir`): `--game`, else `MORGANA_GAME`, else the `--data` folder itself when it has a `game.json` (a realm from the wizard or `world-new`), else `games/blank`, else the data folder. Before 2026-11-13 `--data worlds/<realm>` served `games/blank` instead of the realm's own copy. Tested by `ServerGameDirTest`.

## Try it as a newcomer
Unzip to a folder outside the repository, then from any directory:
1. `bin\morgana world-new --name "My Realm" --disable trading,guilds`, then `bin\morgana worlds`.
2. `bin\morgana new-game --name "Shadows of Eldoria" --dir games/eldoria`, then `bin\morgana validate --game games/eldoria`.
3. `bin\morgana-server --data worlds/my-realm --port 7105`: the console shows `listening on port 7105`.
4. `bin\morgana-client`, **Direct Join** `127.0.0.1:7105`, or **Local Worlds** to host a realm with one click.

Steps 1 to 3 were run this way on Windows when the SDK was added (2026-11-13). The client window and the macOS/Linux scripts have not been exercised by an automated check.

## Not done yet
No bundled Java runtime (a `jlink`/`jpackage` image would remove the Java requirement), no `plugin-example` or plugin guide, no signing or checksums for the zip, and the project itself has no license file yet, which other developers will need before they use it.
