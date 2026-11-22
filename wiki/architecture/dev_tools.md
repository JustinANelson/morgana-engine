---
type: Module
title: "Module: dev-tools"
description: "Repository tooling that keeps the wiki healthy: lint, index generation, and change-impact lookup."
tags: [module, tooling, wiki]
code: ["dev-tools/src/main/kotlin/com/morgana/devtools/wiki", "dev-tools/build.gradle.kts"]
symbols: ["WikiLint", "WikiIndex", "pagesAffectedBy"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Module: `:dev-tools`

Standalone JVM module (only `snakeyaml`). Package `com.morgana.devtools.wiki`.

| Class | Role |
| :--- | :--- |
| `Okf` | Parses concepts (frontmatter + body) |
| `WikiLint` | OKF conformance + Morgana rules + code-drift detection ([wiki conventions](/reference/wiki_conventions.md)) |
| `WikiIndex` | Deterministic `wiki/index.md` generator |
| `WikiTool` | CLI behind the Gradle tasks `wikiLint`, `wikiIndex`, `wikiImpact` ([gradle tasks](/reference/gradle_tasks.md)) |

`check` depends on `wikiLint`, so a stale wiki fails the [verification gate](/playbooks/verification_gate.md).
Workflow: [update the wiki](/playbooks/update_wiki.md).
