---
type: Decision
title: "ADR 0008: Maintain the Wiki as an OKF Bundle with Lint"
description: "Why the project knowledge base uses Open Knowledge Format v0.2 and a code-drift linter."
tags: [adr, wiki]
code: ["dev-tools/src/main/kotlin/com/morgana/devtools/wiki/WikiLint.kt", "dev-tools/src/main/kotlin/com/morgana/devtools/wiki/WikiIndex.kt"]
symbols: ["WikiLint", "okf_version"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0008: Maintain the Wiki as an OKF Bundle with Lint

**Status**: Accepted.

**Context**: Multiple human and AI contributors need shared, trustworthy context that does not rot. `AGENTS.md`/`CLAUDE.md` are short rules, not a knowledge base.

**Decision**: Keep `wiki/` as an [OKF](/reference/wiki_conventions.md) v0.2 bundle (markdown + YAML frontmatter, plain links, reserved `index.md`/`log.md`). Extend with `code:` and `symbols:` frontmatter keys that `WikiLint` checks against the source tree so renames and deletions fail `check` ([dev tools](/architecture/dev_tools.md)).
OKF was chosen because it is tiny, tool-agnostic, git-native, and carries provenance/trust fields (`generated`, `verified`, `stale_after`) that suit mixed human/agent authorship.

**Consequences**: every page needs provenance; stale pages produce warnings after `stale_after`; the index is generated, not hand-written. Workflow: [update the wiki](/playbooks/update_wiki.md).
