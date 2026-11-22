# Wiki Guide (Operating Manual for Contributors and Agents)

The project knowledge base lives in [`wiki/`](../wiki/index.md) as an **Open Knowledge Format (OKF) v0.2** bundle (Google's open
specification for markdown + YAML-frontmatter knowledge; the same idea as the "LLM wiki" pattern). It holds what you need to maintain
Morgana ORPG over a long period with many human and AI contributors. `AGENTS.md` holds the rules; the wiki holds the knowledge.

> OKF's canonical spec repository is `GoogleCloudPlatform/open-knowledge-format`; this bundle targets v0.2 (`okf_version: "0.2"` in `wiki/index.md`).
> When a newer version appears, review it, update `WikiIndex.OKF_VERSION`, and record the decision in the wiki log.

## 1. How to use it

**Reading (query)**: start at [`wiki/index.md`](../wiki/index.md), open the page(s) matching your task, and follow links. Playbooks give step lists for common changes;
Known Issues list verified gaps. Prefer the wiki over re-deriving facts from code; if a page contradicts the code, the code is right and the page needs fixing.

**Writing (ingest)**: after any change that alters behavior, structure, commands, formats, or decisions:
1. `.\gradlew.bat :dev-tools:wikiImpact -PwikiArgs="--git HEAD~1"` (or list changed files) to see which pages cover the changed code.
2. Update those pages. Put new knowledge in the closest existing page; add a page only when the topic has no home.
3. Add a dated bullet at the top of `wiki/log.md` (newest first, `## YYYY-MM-DD`).
4. `.\gradlew.bat :dev-tools:wikiIndex` to regenerate `wiki/index.md`.
5. `.\gradlew.bat :dev-tools:wikiLint` (also runs inside `.\gradlew.bat check`).

**Maintaining (lint)**: `wikiLint` fails the build on broken links, missing/invalid frontmatter, `code:` paths that no longer exist, `symbols:` that no longer appear in
the referenced code (the drift detector), a stale `index.md`, or a malformed `log.md`. It warns about pages past `stale_after`, orphans, unknown types, duplicate titles, and very long pages.
Periodically (e.g. each release) re-verify pages whose `stale_after` has passed.

## 2. Page format

```markdown
---
type: Simulation System
title: "Combat"
description: "One sentence used in the index."
tags: [combat]
status: stable                        # optional: draft | stable | deprecated (absent = stable)
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/CombatSystem.kt"]
symbols: ["playerAttackImpl"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
verified: [{ by: "human:alice", at: 2026-11-08T09:00:00Z }]
stale_after: 2027-05-06T00:00:00Z
---
Body in markdown. Link with bundle-absolute paths: [movement](/systems/movement.md).
```

- `type` is the only field OKF requires. Morgana additionally requires `title`, `description`, and `generated`. Known types: Overview, Glossary, Architecture, Module, Concept,
  Simulation System, Content Type, Protocol, Decision, Playbook, Reference, Known Issue.
- `code`/`symbols` are Morgana extensions (OKF allows extra keys). `code` entries are repo-relative files or directories; each `symbols` entry must appear in them.
- **Provenance and trust** follow OKF: `generated.by` names who wrote it (`<tool>/<version>`, `human:<id>`, `process:<id>`). Never add a `human:` entry to `verified` unless a person actually checked the page.
  Unverified, agent-written pages are acceptable but are treated as lower trust; mark guesses with `status: draft`.
- Timestamps are ISO 8601 with an explicit offset. Reserved files: `index.md` (generated) and `log.md` (hand-maintained). All other `.md` files are concepts.
- File names: lowercase snake_case. Directories: `architecture`, `domain`, `systems`, `server`, `client`, `protocol`, `content`, `decisions`, `playbooks`, `reference`, `issues`.

## 3. Writing guidance

- State facts that can be checked in the code; name the symbol or file. Avoid duplicating `docs/DESIGN.md` (the roadmap) or generated artifacts (`docs/schemas/`); link instead.
- Record decisions as ADRs under `decisions/` (context, decision, consequences) and mark reconstructed rationale as `draft` until the original author confirms.
- Record bugs, risks, and doc/code mismatches under `issues/` with evidence and a suggested fix. When one is fixed, delete the page (or mark `status: deprecated` for one release) and note it in the log.
- Keep pages focused (under ~400 lines). Cross-link generously so no page is an orphan.
- Do not paste secrets, credentials, or personal data.

## 4. Division of labor

| Source of truth | Holds |
| :--- | :--- |
| `AGENTS.md` / `CLAUDE.md` | Short rules and commands loaded into every agent session |
| `docs/DESIGN.md`, `docs/ARCHITECTURE.md` | Product pillars, roadmap, high-level architecture |
| `wiki/` | Detailed, linked, code-anchored maintenance knowledge |
| `docs/schemas/` | Generated JSON Schemas for content files |
| `docs/AGENT_GUIDE.md` | Agent working style and the content-authoring loop |
