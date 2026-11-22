---
type: Overview
title: "Morgana ORPG Overview"
description: "What Morgana ORPG is, its design pillars, the module map, and where to start reading."
tags: [overview, onboarding]
code: ["settings.gradle.kts", "AGENTS.md"]
symbols: ["rootProject.name"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Morgana ORPG Overview

Morgana ORPG is a cross-platform (desktop, Android, browser) 2D game engine built on **Kotlin + LibGDX**. It is geared toward
online RPGs in the spirit of **Mirage Source** and **Player Worlds** (simple to run, build the world while standing in it, host
it yourself) and also supports other genres. The engine is genre-neutral; a game is a folder of data (`game.json`, JSON content)
plus sandboxed Lua scripts ([game definition](/domain/game_definition.md)). It was renamed from
"FADE" (Forgotten Arts Dungeon Engine); all packages are `com.morgana.*`.

## Design pillars
1. **Deterministic core**: all rules live in the pure `:core-domain` module ([determinism](/domain/determinism_and_rng.md)).
2. **Strict boundaries**: domain never touches I/O, network, persistence, or rendering ([module graph](/architecture/module_graph.md)).
3. **Live in-game creation**: the `F1` editor edits the running world ([F1 editor](/client/f1_editor.md)).
4. **Data first, GUI optional**: every piece of content is a JSON file that humans, scripts, agents, and the GUI all author through one pipeline ([content pipeline](/content/content_pipeline.md)).
5. **Verifiable increments**: each roadmap phase ships with tests ([testing strategy](/reference/testing_strategy.md)).
6. **Mirage simplicity**: heavy systems are optional modules; the core path stays small.

## Module map
| Module | Role | Page |
| :--- | :--- | :--- |
| `:core-domain` | Pure rules, 20 TPS simulation, content validation | [core domain](/architecture/core_domain.md) |
| `:shared-protocol` | Client/server packets and codecs | [shared protocol](/architecture/shared_protocol.md) |
| `:server-persistence` | JSON document store, content loader, schema export | [server persistence](/architecture/server_persistence.md) |
| `:server-app` | Netty server, coordinator, hot reload, content CLI | [server app](/architecture/server_app.md) |
| `:client-core` | LibGDX client: renderer, HUD, F1 editor, net client | [client core](/architecture/client_core.md) |
| `:script-luak` | Sandboxed Lua script runtime for game scripts | [ADR 0013](/decisions/0013_engine_game_split_and_lua_scripting.md) |
| `:client-net-netty` | Netty (TCP) transport for the desktop and Android clients | [client core](/architecture/client_core.md) |
| `:client-web` | Browser client: client-core compiled to JavaScript | [client web](/architecture/client_web.md) |
| `:plugin-example` | Example server plugin | [PLUGIN_ARCHITECTURE](../docs/PLUGIN_ARCHITECTURE.md) |
| `games/` | Game folders (`blank`, `roguelike`, `halcyon-reach`, `white-river`, `tutorial-village`): `game.json`, content, scripts, assets. Not engine code | [blank game](/content/blank_game.md) |
| `:tilegen` | Creator tool: pixel-art generator (tiles, props, paper dolls) and CLI | [tilegen](/architecture/tilegen.md) |
| `:client-desktop` / `:client-android` | Platform launchers | [desktop](/architecture/client_desktop.md), [android](/architecture/client_android.md) |
| `:dev-tools` | Repository tooling (this wiki's lint/index) | [dev tools](/architecture/dev_tools.md) |

## Where to start
- New contributor: [run local dev](/playbooks/run_local_dev.md), then [verification gate](/playbooks/verification_gate.md).
- Adding a feature: pick a [playbook](/playbooks/add_content_type.md) and read the matching [system page](/systems/tick_loop.md).
- Authoring world content (human or agent): [content collections](/content/content_collections.md) and [author content as files](/playbooks/author_content_as_files.md).
- Known gaps and risks: the [security model](/reference/security_model.md) lists the remaining ones; the wiki currently has no open Known Issue pages (add them under `issues/`).
- Vocabulary: [glossary](/glossary.md). Code and repo conventions: [conventions](/reference/conventions.md). Wiki rules: [wiki conventions](/reference/wiki_conventions.md).
