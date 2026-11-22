---
type: Reference
title: "Wiki Conventions (OKF)"
description: "How this OKF v0.2 bundle is structured, the required frontmatter, and what the linter enforces."
tags: [wiki, okf]
code: ["dev-tools/src/main/kotlin/com/morgana/devtools/wiki/WikiLint.kt", "dev-tools/src/main/kotlin/com/morgana/devtools/wiki/Okf.kt", "dev-tools/src/main/kotlin/com/morgana/devtools/wiki/WikiIndex.kt"]
symbols: ["KNOWN_TYPES", "stale_after", "okf_version", "RESERVED_FILES"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Wiki Conventions

The wiki (`wiki/`) is an **Open Knowledge Format v0.2** bundle: markdown files with YAML frontmatter; links are plain markdown links; `index.md` and `log.md` are reserved. Full workflow: `docs/WIKI_GUIDE.md`.

## Frontmatter
```yaml
---
type: Simulation System          # required by OKF; use a known type
title: "Combat"                  # required (Morgana)
description: "One sentence."     # required (Morgana); used in the index
tags: [combat]
status: stable                   # optional: draft | stable | deprecated
code: ["core-domain/src/main/kotlin/..."]   # repo-relative files/dirs this page describes
symbols: ["playerAttackImpl"]               # identifiers that must still appear under `code`
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }   # required
verified: [{ by: "human:alice", at: 2026-11-08T09:00:00Z }]                  # only after a human checks it
stale_after: 2027-05-06T00:00:00Z
---
```
Known types: Overview, Glossary, Architecture, Module, Concept, Simulation System, Content Type, Protocol, Decision, Playbook, Reference, Known Issue.
Actors: `human:<id>`, `process:<id>`, or `<tool>/<version>` for agents. Timestamps are ISO 8601 with an explicit offset.

## Linter ([dev tools](/architecture/dev_tools.md))
Errors: missing/invalid frontmatter, `type`, `title`, `description`, `generated`; bad timestamps/status; broken links; missing `code` paths; vanished `symbols`; stale `index.md`; bad `log.md`.
Warnings: past `stale_after`, orphans, unknown type, duplicate titles, very long pages. Workflow: [update the wiki](/playbooks/update_wiki.md). Rationale: [ADR 0008](/decisions/0008_okf_wiki.md).

Links: prefer bundle-absolute `/dir/page.md`. Put examples with links in code fences if they are not meant to resolve.
