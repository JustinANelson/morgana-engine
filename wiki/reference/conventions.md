---
type: Reference
title: "Code and Repository Conventions"
description: "Naming, style, testing, IDs, units, and commit rules used throughout the project."
tags: [conventions]
code: ["AGENTS.md", "core-domain/src/main/kotlin/com/morgana/domain/validation/ContentValidation.kt"]
symbols: ["Architectural Mandates", "CURRENT_VERSION"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Code and Repository Conventions

- Kotlin official style; JDK 17 toolchain ([build](/architecture/build_toolchain.md)). Preserve existing comments and KDoc unrelated to your edit (mandate in `AGENTS.md`).
- Units: time in **ticks**, probabilities in **basis points**, positions as tile + pixel; ids are `Int` for content and `String` for runtime/player entities.
- Content ids start at 1; `0` means "none" in optional references ([content collections](/content/content_collections.md)).
- Serialization names: polymorphic subtypes use `@SerialName("snake_case")`; packets `c_*`/`s_*` ([wire format](/protocol/wire_format.md)).
- Tests are named per phase (`PhaseNXxxTest`) with backticked sentences; each roadmap phase and bug fix needs tests ([testing strategy](/reference/testing_strategy.md)).
- Documents carry `schemaVersion`; bump `ContentSchema.CURRENT_VERSION` only with a migration plan.
- Shell: PowerShell/Git Bash on Windows; CLI helpers (`rg`, `fd`, `jq`, `lsof`) are on PATH (see `AGENTS.md`).
- Keep `docs/DESIGN.md` and `docs/ARCHITECTURE.md` in sync with completed phases; update this wiki alongside ([update the wiki](/playbooks/update_wiki.md)).
