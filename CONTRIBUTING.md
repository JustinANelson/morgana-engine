# Contributing to Morgana ORPG

Thank you for contributing to Morgana ORPG! Whether you are building a game on top of the engine or improving the engine itself, this guide covers the repository layout, conventions, and verification steps.

## Prerequisites
- **JDK 17+** (Gradle wrapper `./gradlew` / `.\gradlew.bat` downloads Gradle automatically).
- Optional: **Node.js + Chrome** only if running the browser smoke test (`:client-web:smokeTest`).

## Two Ways to Work

### 1. Building or Collaborating on a Game (`games/<name>/`)
You do **not** need to edit Kotlin engine code to build a game:
1. Create a game folder via `.\gradlew.bat :server-app:newGame -PcontentArgs="--name 'My Game' --dir games/my-game"` (or copy an existing template such as `games/roguelike`).
2. Author content via the live `F1` editors, hand-edited JSON (`game.json`, `data/`), Tiled (`.tmj` converter), and sandboxed Lua plugins (`scripts/*.lua`).
3. Ship game-owned tilesets, sprites, icons, paperdolls, and `.ogg` music in `games/<name>/assets/` (see [wiki/playbooks/make_game_art.md](wiki/playbooks/make_game_art.md)).
4. **Source of truth rule**: never commit destructive scripts that wipe and regenerate `data/`, `assets/`, or `game.json`—edit content directly or through `F1`.
5. Validate your game before pushing:
   ```powershell
   .\gradlew.bat :server-app:validateContent "-PcontentArgs=--game games/my-game"
   .\gradlew.bat :server-app:validateArt
   .\gradlew.bat :server-app:validateScripts
   ```

### 2. Contributing to the Engine (`:core-domain`, `:shared-protocol`, `:server-*`, `:client-*`)
Follow the architectural boundaries documented in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [ADR 0018](wiki/decisions/0018_core_systems_plugins_templates_terminology.md):
- **Pure, deterministic `:core-domain`**: zero LibGDX, Netty, filesystem I/O, `System.currentTimeMillis()`, or `Math.random()`. Use tick-based time (`currentTick`), injected `RandomSource`, and sorted/ordered collections.
- **Engine / game separation**: never hard-code game names, `games/<name>` paths, or content IDs in engine modules (`EngineBoundaryTest` enforces this).
- **Systems through seams**: optional gameplay features belong in `core-domain/.../systems/` implementing `GameSystem` + kernel seams, toggled via `OptionalModule` (`server.json`) or opt-in `game.json` sections. Always test with the module switched **off**.
- **Data first, GUI optional**: new content fields/types must be validated in `ContentValidator` / `GameDefinitionValidator`, exported to `docs/schemas/` (`:server-app:exportContentSchemas`), and editable in `F1` forms.
- **Never write into `games/` or `server-data/` from tests**: use temp directories and `GameScaffolder`.
- **Keep the wiki accurate**: when behavior, commands, formats, or architecture change, update the affected pages under `wiki/` and add a brief entry to `wiki/log.md`.

## Before Opening a Pull Request
Run the full verification gate:
```powershell
.\gradlew.bat check
```
This runs all unit/integration tests across every module, `EngineBoundaryTest`, schema drift checks, `validateArt`, `validateScripts`, and `wikiLint`.

If you modified any content/game models, run `:server-app:exportContentSchemas` first and commit the updated files in `docs/schemas/`.
