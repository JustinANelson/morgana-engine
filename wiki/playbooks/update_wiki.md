---
type: Playbook
title: "Update the Wiki"
description: "Ingest, query, and lint workflows for keeping the OKF wiki accurate as the code changes."
tags: [playbook, wiki]
code: ["dev-tools/src/main/kotlin/com/morgana/devtools/wiki/WikiTool.kt", "docs/WIKI_GUIDE.md"]
symbols: ["pagesAffectedBy", "wikiImpact"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Update the Wiki

Operating manual: `docs/WIKI_GUIDE.md`. Per change:
1. **Find affected pages**: `.\gradlew.bat :dev-tools:wikiImpact -PwikiArgs="--git HEAD~1"` (or pass file paths). It lists pages whose `code:` covers the changed files.
2. **Edit** those pages: fix facts, `code:`/`symbols:` entries, and links. New knowledge goes in the closest existing page; create a page only when the topic has no home (type from the known list in [wiki conventions](/reference/wiki_conventions.md)).
3. **Provenance**: set `generated: { by: <you>, at: <now> }` when you rewrite a page; add `verified: [{ by: human:<id>, at: ... }]` only when a human actually checked it; push `stale_after` out if you re-verified.
4. **Log**: add a dated bullet at the top of `wiki/log.md` (`## YYYY-MM-DD`, newest first).
5. **Index**: `.\gradlew.bat :dev-tools:wikiIndex` (regenerates `index.md` from frontmatter).
6. **Lint**: `.\gradlew.bat :dev-tools:wikiLint` (also part of `check`, [gate](/playbooks/verification_gate.md)).

When you discover a bug or gap while reading code, add or update a page under Known Issues instead of leaving it in chat.
