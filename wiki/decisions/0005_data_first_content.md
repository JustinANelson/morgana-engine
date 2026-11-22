---
type: Decision
title: "ADR 0005: Data First, GUI Optional"
description: "Content is plain JSON files authored equally by humans, scripts, agents, and the F1 GUI through one pipeline."
tags: [adr, content]
code: ["core-domain/src/main/kotlin/com/morgana/domain/validation/ContentValidation.kt", "AGENTS.md"]
symbols: ["ContentValidator", "Data First, GUI Optional"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0005: Data First, GUI Optional

**Status**: Accepted (implemented in roadmap Phase 11).

**Context**: Mirage's strength is live in-game editing, but a GUI-only workflow locks out scripts and AI agents and makes review/diff impossible.

**Decision**: Every content type is a schema-versioned JSON document; every authoring route passes the same validation, persistence, hot reload, and broadcast ([content pipeline](/content/content_pipeline.md)). Nothing may be creatable only through the GUI (also mandate 6 in `AGENTS.md`).

**Consequences**: generated JSON Schemas stay in sync via a test; validator rules are the single source of truth ([validation rules](/content/validation_rules.md)); editors lag behind file support for some types ([F1 editor](/client/f1_editor.md)).
