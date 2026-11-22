---
type: Module
title: "Module: game-art"
description: "Tooling, not engine: the per-game sprite kits, art specs and exporters that draw the bundled games' art on top of the generic :tilegen generators."
tags: [module, tooling, assets, sprites, games]
code: ["game-art/src/main/kotlin/com/morgana/gameart", "game-art/build.gradle.kts", "game-art/specs"]
symbols: ["GameArtCli", "TrevanaGameExporter", "RoguelikeGameExporter", "RoguelikeIconArt", "TrevanaSpriteGenerator", "WhiteRiverPlayerGenerator"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-22T12:00:00Z }
stale_after: 2027-05-21T00:00:00Z
---

# Module: `:game-art`

`:tilegen` is the generic pixel-art toolkit; it knows no game. `:game-art` holds everything that is specific to one of the bundled games and nothing else: a game's NPC, monster and class sprite kits, the JSON specs for its class looks (`game-art/specs/`), and the exporters that write the result into that game's `assets/` in the engine layout. It depends on `:tilegen`; no engine module depends on it, and the engine boundary test (`EngineBoundaryTest`) does not let an engine module name a game.

| Package | Role |
| :--- | :--- |
| `sprite` | `TrevanaSpriteGenerator` and `MonsterArt` (the open-world RPG's 32 x 48 NPCs, monsters and class bodies). |
| `export` | `TrevanaGameExporter`, `RoguelikeGameExporter` and `RoguelikeIconArt`: draw a game's sprites, paperdolls, tilesets and icons straight into its `assets/`, writing only the ids they own. |
| `whiteriver` | Player, enemy, item and skill generators for the action RPG. |
| `cli` | `GameArtCli`; run `.\gradlew.bat :game-art:run "--args=<command> ..."` from the repository root. Commands: `trevana`, `trevana export --game <folder>`, `roguelike export --game <folder>`, `whiteriver <players|enemies|items|skills|all>`. |

Tests never write into `games/`: they export into a temp directory. A new game with its own art kit adds its generator and exporter here (or ships its own art as plain files).
