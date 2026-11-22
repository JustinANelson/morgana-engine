---
type: Playbook
title: "Large Rename or Package Refactor"
description: "A proven procedure for renaming packages and types across all modules without breaking determinism."
tags: [playbook, refactor]
code: ["settings.gradle.kts"]
symbols: ["rootProject.name"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Large Rename or Package Refactor

1. Move source directories first (`com/old` -> `com/new` in every source set, including tests and Android).
2. Rename files that embed the old name, then apply regex with word boundaries (`\bOld(?=[A-Z])`) so library calls and unrelated words are untouched.
3. Grep case-insensitively afterwards and review every leftover; keep hex literals and persisted keys deliberately ([rename ADR](/decisions/0010_morgana_rename.md)).
4. Persisted names (JSON keys, Tiled properties, file names) are API: decide compatibility explicitly.
5. Run the full [gate](/playbooks/verification_gate.md) with `--no-build-cache` once.
6. Update `AGENTS.md`, docs, skills, and the wiki (`wikiLint` will flag broken `code:` paths).
