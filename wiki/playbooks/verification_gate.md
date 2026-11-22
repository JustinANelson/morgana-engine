---
type: Playbook
title: "Verification Gate"
description: "What .\\gradlew.bat check runs, how to force tests to really execute, and how to fix common failures."
tags: [playbook, quality]
code: ["dev-tools/build.gradle.kts", "AGENTS.md"]
symbols: ["wikiLint", "dependsOn"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Verification Gate

`.\gradlew.bat check` is the completion criterion for every change. It runs: unit/integration tests of all modules, Android unit tests and lint, and **`wikiLint`** ([dev tools](/architecture/dev_tools.md)).
Content files are validated separately with `validateContent` ([gradle tasks](/reference/gradle_tasks.md)).

- Build cache is enabled: a cached module can show "up to date" with no test output. Force execution: `.\gradlew.bat cleanTest test --no-build-cache` ([testing strategy](/reference/testing_strategy.md)).
- `wikiLint` failure: a link, `code:` path, or `symbols:` entry is stale, or `index.md` is out of date ([update the wiki](/playbooks/update_wiki.md)).
- `exportContentSchemas` stale-schema test failure: regenerate `docs/schemas` and commit ([add a content type](/playbooks/add_content_type.md)).
- A rule/phase/bug fix is not done without tests (mandate 4 in `AGENTS.md`).
