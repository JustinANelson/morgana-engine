---
type: Module
title: "Module: game-art"
description: "Tooling, not engine: the shared 32 x 48 sprite kit, the public games' art exporters and the command line, on top of the generic :tilegen generators."
tags: [module, tooling, assets, sprites, games]
code: ["game-art/src/main/kotlin/com/morgana/gameart", "game-art/build.gradle.kts", "game-art/specs"]
symbols: ["GameArtCli", "RoguelikeGameExporter", "RoguelikeIconArt", "RpgSpriteGenerator", "RpgNpcKind", "RpgEnemyKind", "WhiteRiverPlayerGenerator"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-22T12:00:00Z }
stale_after: 2027-05-21T00:00:00Z
---

# Module: `:game-art`

`:tilegen` is the generic pixel-art toolkit; it knows no game. `:game-art` holds everything that is specific to one of the bundled games and nothing else: a game's NPC, monster and class sprite kits, the JSON specs for its class looks (`game-art/specs/`), and the exporters that write the result into that game's `assets/` in the engine layout. It depends on `:tilegen`; no engine module depends on it, and the engine boundary test (`EngineBoundaryTest`) does not let an engine module name a game.

| Package | Role |
| :--- | :--- |
| `sprite` | `RpgSpriteGenerator` (with `RpgNpcKind` and `RpgEnemyKind`) and `MonsterArt`: the shared 32 x 48 NPC and monster kit that the exporters build on. |
| `export` | `RoguelikeGameExporter` and `RoguelikeIconArt`: draw a game's sprites, paperdolls, tilesets and icons straight into its `assets/`, writing only the ids they own. |
| `whiteriver` | Player, enemy, item and skill generators for the action RPG. |
| `cli` | `GameArtCli`; run `.\gradlew.bat :game-art:run "--args=<command> ..."` from the repository root. Commands: `sprites <npcs|enemies|all>`, `roguelike export --game <folder>`, `whiteriver <players|enemies|items|skills|all>`. |

Per-game tileset specs are plain JSON that tilegen's `gametilesets` command renders (`--specs <folder>`); the public games keep theirs in their own folders. A private game's own exporter, specs and tests stay local and gitignored, next to the game: run a local-only exporter with `.\gradlew.bat :game-art:runLocal -PmainClass=<its main class> --args="..."`, which runs any main class on this module's classpath. See [tilegen](/architecture/tilegen.md).

Tests never write into `games/`: they export into a temp directory. A new game with its own art kit adds its generator and exporter here (or ships its own art as plain files).
