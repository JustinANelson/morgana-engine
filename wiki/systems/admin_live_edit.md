---
type: Simulation System
title: "Admin Commands and Live Content Edits"
description: "Slash commands, permission checks, and the applyLive*Edit hot-swap methods."
tags: [admin, live-edit]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/AdminSystem.kt"]
symbols: ["executeAdminSlashCommandImpl", "applyLiveMapEditImpl", "applyLiveItemEditImpl", "applyLiveAnimationEditImpl", "EDIT_CONTENT", "warpmeto"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Admin Commands and Live Content Edits

**Slash commands** (`executeAdminSlashCommand`; unknown commands do nothing; each checks a permission flag):
`/warpto <map> [x y]` (WARP_TO_MAP), `/warpmeto <name>` (WARP_TO_PLAYER), `/warptome <name>` (SUMMON_PLAYER), `/item|/spawnitem <id> [n]` (SPAWN_ITEM),
`/setaccess <name> <rank>` (SET_ACCESS), `/heal`. Kick, mute, ban, reports and the audit log are handled by the coordinator ([moderation](/server/moderation.md)), so the older simulation-level `/kick` is no longer reached from chat. `/reload` is intercepted by the coordinator ([hot reload](/content/hot_reload.md)).

**Live edits** (`applyLiveMapEdit`, `applyLiveItemEdit`, ... one per content type): require `EDIT_MAP` (maps) or `EDIT_CONTENT` (everything else), replace the definition in the live
simulation, and return `MapHotSwapped` (map revision becomes `max(given, old + 1)`) or `ContentCatalogHotSwapped`. They do **not** persist or validate; the coordinator validates first
and persists afterwards ([coordinator](/server/coordinator.md), [content pipeline](/content/content_pipeline.md)). Permission model: [access levels](/domain/access_levels.md).
