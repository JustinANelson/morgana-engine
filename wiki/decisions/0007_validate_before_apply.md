---
type: Decision
title: "ADR 0007: Validate Before Apply (Strict F1 Saves, All-or-Nothing Reload)"
description: "Invalid content never reaches the live world, with one deliberate relaxation: links between maps may dangle."
tags: [adr, validation]
code: ["server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "core-domain/src/main/kotlin/com/morgana/domain/validation/ContentValidation.kt"]
symbols: ["acceptEdit", "reloadContent", "missing_map_link"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0007: Validate Before Apply

**Status**: Accepted (amended after Phase 11).

**Decision**: `acceptEdit` validates a candidate `F1` document against the live catalog and rejects it with a chat message if *that document* has errors (pre-existing problems elsewhere do not block it).
`reloadContent()` validates the **entire** file set and applies nothing if any error exists ([hot reload](/content/hot_reload.md)).

**Amendment: map links are warnings.** Maps legitimately reference each other in cycles (A warps to B and B to A), so a missing *map* destination (warp tiles, neighbor links, warp commands, warp spells) is the WARNING `missing_map_link`, not an error. This is safe because the simulation ignores a warp to a missing map (the player just stands on the tile) and border crossings simply do nothing. Every other missing reference stays an ERROR (`missing_reference`) and must be created first - items before shops, NPCs before the map that spawns them ([validation rules](/content/validation_rules.md)).

**Trade-off**: authoring order matters for non-map references; reloads are eventually consistent because each reload re-reads every file, so a multi-file batch that is momentarily inconsistent succeeds on the reload after the last file lands.
