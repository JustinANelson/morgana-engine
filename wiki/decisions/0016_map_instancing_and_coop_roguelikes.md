---
type: Decision
title: "ADR 0016: Map Instancing for Co-op Roguelike Runs"
description: "Instanced maps become private per-party copies with reserved map ids, as the first step toward hub-and-dungeon co-op roguelikes."
tags: [adr, instancing, roguelike, maps, roadmap]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/InstanceSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt"]
symbols: ["MapInstances", "MapInstance", "InstanceSettings", "warpedPlayer", "contentMapId", "contentMaps", "instanceReturn"]
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-13T12:00:00Z }
stale_after: 2027-05-12T00:00:00Z
---

# ADR 0016: Map Instancing for Co-op Roguelike Runs

**Status**: Accepted 2026-11-13. Step 1 (instancing) is implemented; see [instanced maps](/systems/instancing.md). Step 2 (procedural floors) followed the same day: a `generator` recipe on the instanced map itself rather than a new content collection, so floors stay ordinary map content; see [procedural floors](/systems/procedural_floors.md). The rest is on the [roadmap](/reference/roadmap_status.md).

**Context**: the owner chose to support co-op roguelikes as a **hub plus dungeon runs** game: a persistent town where parties enter generated, private dungeon runs and come back with permanent progress. A "whole realm is one run" game is then a realm whose hub is a small lobby. Before this, the map `instanced` flag was only a label: everyone shared one copy of every map, so two parties in the same dungeon collided. Instancing is also the foundation of real dungeons and owned homes ([ADR 0015](/decisions/0015_plots_crops_and_housing_plan.md) step 3).

**Decision**:
1. **A copy is an ordinary map with a reserved id.** Instances live in `WorldSimulation.maps` under ids from `MapInstances.FIRST_ID` (1,000,000) up, never reused within a run. Every per-map system (NPCs, ground items, projectiles, doors, chat, snapshots, the client map cache) works on them unchanged. The alternative, a separate instance-keyed world layer, would have touched every system for no gain at this scale.
2. **Copies are never content.** `contentMaps()` leaves them out of the content catalogue, validation, the editor catalogue and the replay content fingerprint; content may not use ids in the reserved range (`reserved_id`). Anything content names a map by (events, visit-map quest objectives) resolves through `contentMapId()` to the template.
3. **Ownership is the party, or the player alone.** The owner key is `party:<id>` or `solo:<characterId>`. Everyone who enters is recorded as a member; only members are put back into an open instance at login.
4. **All player warps go through one function** (`warpedPlayer`): warp tiles, the event warp step, warp spells and the script warp effect. Admin `/warpto` deliberately visits the template itself, so builders can edit it.
5. **Lifetime is "empty for a while"** (`server.json` `instances.emptySeconds`, default 60), with a cap on open instances (`instances.max`, default 200) that turns players away with a message. Instances are not saved; a restart closes them all.
6. **Coming back** uses `CharacterState.instanceReturn`, the position before entering: a character that logs in to a closed or foreign instance is put there (or at their class start).
7. **Switchable** as the optional module `instancing`; off, instanced maps are shared as before.

**Consequences**: deterministic (ids come from a counter, closing runs in the tick, instance state is in the state hash when present); characters gained a field, so the golden replay was re-recorded. Not yet handled: per-instance content edits (editing a template does not change open copies), a recording that starts while instances are open, and party changes mid-run (a player who leaves the party keeps their instance until they leave it).

**Next steps for co-op roguelikes** (in order): procedural floors from JSON room templates on a seeded generator; the run lifecycle (enter, floors, stairs, boss, end and return, empty-run rules); run-only state wiped at the end; co-op death rules (downed and revive, spectate, wipe ends the run, optional permadeath); persistent progress between runs (unlocks, a meta currency, run history and leaderboards, shareable seeds).
