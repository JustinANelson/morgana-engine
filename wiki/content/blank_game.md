---
type: Reference
title: "Blank Game and the Seeded World"
description: "The games/blank scaffold, which is also what an empty server data directory is seeded with (the repository also ships the reference roguelike)."
tags: [content, seed, scaffold]
code: ["server-persistence/src/main/kotlin/com/morgana/persistence/GameScaffolder.kt", "games/blank/game.json", "server-app/src/main/kotlin/com/morgana/server/MorganaServerMain.kt", "server-app/build.gradle.kts"]
symbols: ["GameScaffolder", "scaffold", "seedIfEmpty", "DEFAULT_NAME", "seedWorld", "newGame"]
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-13T12:00:00Z }
stale_after: 2027-05-12T00:00:00Z
---

# Blank Game and the Seeded World

The repository ships no finished game. On 2026-11-13 the owner deleted *Morgana Classic*, *Hearthvale* and the code-built *Starter Town* (`StarterWorldSeeder`: Havenwood Town, Havenwood Outskirts, Forgotten Crypt). What is left is one small scaffold made by `GameScaffolder`:

- **Content**: class 1 *Adventurer* (starts on map 1 at (5, 5) with item 1), item 1 *Bronze Sword*, NPC 1 *Town Guide* (friendly), map 1 *Town Square* (20 x 20, walled border, the guide spawns at (10, 10), `SAFE_ZONE`).
- **As a game project** (`GameScaffolder.scaffold`): `game.json` with its own attributes (`str`, `agi`, `int`, `vit`) and vitals (`hp`, `mp`), the content above in `data/`, `scripts/main.lua` (`/hello`, the `scout` action) and a `README.md`. `games/blank` is this project, and it is what the server (`MorganaServerMain`) and the content tools use when no `--game` is given. The `newGame` task and the New Realm Wizard's **Blank Game** template make new copies.
- **As a seed** (`GameScaffolder.seedIfEmpty`): when a server data directory has no game of its own (`gameDir == rootDir`) and its `world/` is empty, the coordinator writes the same content there (run with the engine default rules, `BlankGame`, which are the same rules as `games/blank/game.json`; `BlankGameConsistencyTest` keeps them equal). `.\gradlew.bat :server-app:seedWorld` does it by hand.

Realms made with the wizard's Blank template keep their content in `data/` next to `game.json`; realms imported from a world pack use `world/` and the built-in rules.

## Tests
Tests never rely on this content beyond its shape: those that need a game build a throwaway one in a temp folder (`TestGames` in `:server-app` tests, with a neighbour-map variant and an in-memory combat kit) or construct content directly. `Phase3PersistenceTest` and `Phase11ContentPipelineTest` check the seed itself (counts, a valid load, idempotence), and the golden replay (`Phase12GoldenReplayTest`, `golden/blank_world_session.replay.json`) runs a scripted session in it.
