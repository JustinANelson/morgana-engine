---
name: wiki-maintenance
description: How to read, update, and lint the Morgana ORPG knowledge wiki (OKF bundle in wiki/). Use before non-trivial work to gather context, and after any change that alters behavior, structure, commands, formats, or decisions.
---

# Wiki Maintenance Skill

The wiki in `wiki/` is the long-term, code-anchored knowledge base. Full manual: `docs/WIKI_GUIDE.md`.

## Before working (query)
1. Open `wiki/index.md`; read the pages for your area (systems, protocol, content, playbooks).
2. Check `wiki/issues/` for known gaps in the area you are touching.
3. If a page disagrees with the code, trust the code and fix the page.

## After changing code (ingest)
```powershell
.\gradlew.bat :dev-tools:wikiImpact -PwikiArgs="--git HEAD~1"   # pages whose `code:` covers your change
# edit those pages; add a dated bullet to the top of wiki/log.md
.\gradlew.bat :dev-tools:wikiIndex
.\gradlew.bat :dev-tools:wikiLint
```

## Rules
- Required frontmatter: `type`, `title`, `description`, `generated {by, at}`; add `code:` and `symbols:` so drift is detected.
- Never add a `human:` entry under `verified` unless a person actually checked the page.
- Mark guesses `status: draft`. Record bugs/mismatches as Known Issue pages with evidence and a suggested fix; delete the page when fixed and log it.
- Links are plain markdown, bundle-absolute (`/systems/combat.md`). `index.md` is generated; never hand-edit it.
- `wikiLint` runs in `.\gradlew.bat check`; warnings (stale pages, orphans) should be cleared opportunistically.
