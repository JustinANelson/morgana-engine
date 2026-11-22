---
type: Playbook
title: "Change a Balance Formula"
description: "Where combat and stat math lives and how to change it safely via GameDefinition."
tags: [playbook, balance, game-definition]
code: ["core-domain/src/main/kotlin/com/morgana/domain/game/GameDefinition.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/BlankGame.kt"]
symbols: ["GameDefinition", "BlankGame"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Change a Balance Formula

1. Formulas live in `GameDefinition` (`game.json` in the server data directory, or `BlankGame.kt` for the engine default; see [stats and formulas](/domain/stats_and_formulas.md)).
2. All formulas use the engine's deterministic integer expression language. Expressions are validated strictly on load by `GameDefinitionValidator`.
3. Run unit tests (`.\gradlew.bat test`) and validate content files (`.\gradlew.bat :server-app:validateContent`).
4. Update the formula table in the wiki and add a log entry.
