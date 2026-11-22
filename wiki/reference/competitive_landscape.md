---
type: Reference
title: "Competitive Landscape: Intersect, Mirage, Player Worlds"
description: "How Morgana positions itself against the engines that inspired it, separating sourced facts from unverified claims."
tags: [strategy]
code: ["docs/DESIGN.md"]
symbols: ["Defining Features"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Competitive Landscape

## Intersect (verified from its repository, fetched 2026-11-07)
Sources: the `AscensionGameDev/Intersect-Engine` README, `Documentation/Features.md`, and `REQUIREMENTS.md` on GitHub.
- **Technology**: C# on .NET 8, rendering with MonoGame; advertised as a suite for 2D MMORPGs.
- **Editor**: a separate `Intersect.Editor` application that uses DirectX and must run on Windows. (Whether any in-game editing exists is not stated.)
- **Platforms**: host and play on desktop OSes with OpenGL (Windows, Linux, macOS); Android/iOS "Not Yet Supported"; web not supported. Maintainers say they are open to mobile/web but lack capacity.
- **Licensing**: MIT for Core, Client, Client.Framework, Network; GPLv3 for Editor, Server, Server.Framework, Utilities.
- **Activity**: ~3,900 commits, ~284 stars, ~396 forks; 0.8.0-beta builds referenced.

**Not established by those pages** (do not rely on these): database options, networking transport, plugin system details, event system details, and whether maps link seamlessly.

## Mirage Source and Player Worlds
Not covered by any source consulted. Treat statements about them in this repository as design inspiration ("simple to host, build while playing"), not as verified comparison.

## Morgana (verified against this repository)
| Aspect | Morgana |
| :--- | :--- |
| Editing | In-game `F1` editor plus file authoring through one validator ([ADR 0005](/decisions/0005_data_first_content.md)) |
| Logic location | Pure shared domain ([ADR 0001](/decisions/0001_deterministic_domain_core.md)) |
| Platforms | Desktop and Android from one client core ([ADR 0003](/decisions/0003_kotlin_libgdx_shared_client.md)); no web client |
| Storage | JSON documents behind a port; optional SQLite for player data ([persistence layout](/server/persistence_layout.md)); Mongo is a possible future extension ([ADR 0002](/decisions/0002_json_document_persistence.md)) |
| Plugins | Typed Kotlin [hook API](/server/hook_api.md): login/logout, item use, spell cast, NPC kill, player death, chat, custom events, and combat formula overrides |
| Licensing | Not yet declared in the repository |

Intended differentiators: live in-game building, dual GUI/file authoring with one validator, deterministic replayable simulation, one-click host, shareable world packs, headless bots ([roadmap](/reference/roadmap_status.md)). Guardrail: heavy features stay optional ([overview](/overview.md)).
