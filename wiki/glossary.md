---
type: Glossary
title: "Glossary"
description: "Definitions of the terms used across the Morgana codebase and documentation."
tags: [glossary]
code: ["core-domain/src/main/kotlin"]
symbols: ["WorldSimulation"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Glossary

| Term | Meaning |
| :--- | :--- |
| **Tick / TPS** | One simulation step. The server runs 20 ticks per second (50 ms). See [tick loop](/systems/tick_loop.md). |
| **Basis points (bp)** | Probability/percentage unit: 10000 = 100%. Used for drops and crit/dodge chances. |
| **Domain** | The pure `:core-domain` module. No I/O. See [core domain](/architecture/core_domain.md). |
| **WorldSimulation** | The facade class owning all simulation state and the public API. |
| **Simulation event** | A `WorldSimulationEvent` returned by simulation calls; the server turns events into packets. See [simulation events](/domain/simulation_events.md). |
| **Coordinator** | `MorganaWorldCoordinator`: the synchronized server object connecting sessions, simulation, and persistence. See [coordinator](/server/coordinator.md). |
| **Content collection** | A directory of JSON documents of one type (items, maps, ...). See [content collections](/content/content_collections.md). |
| **Document** | One content or player record stored as one JSON file. |
| **Hot swap / hot reload** | Replacing live definitions without restarting. See [hot reload](/content/hot_reload.md). |
| **F1** | The key that opens the in-game live editor panel. See [F1 editor](/client/f1_editor.md). |
| **Access level** | 0 Player, 1 Monitor, 2 Mapper, 3 Developer, 4 Owner. See [access levels](/domain/access_levels.md). |
| **Moral** | Per-map ruleset (safe zone, PvP, arena, dungeon). See [maps and tiles](/domain/maps_and_tiles.md). |
| **Tile attribute** | Behavior attached to a map cell (warp, blocked, key door, ...). |
| **Neighbor stitching** | Seamless N/S/E/W map borders; the client renders adjacent maps. |
| **Page event** | RMXP/Eclipse-style event with condition-gated pages. See [events and quests](/systems/events_quests.md). |
| **Core** | The base engine every game runs on (simulation kernel, maps, persistence, networking, editors). Not switchable. See [ADR 0018](/decisions/0018_core_systems_plugins_templates_terminology.md). |
| **System** | A switchable feature built into the engine (code type `OptionalModule`), tagged by genre and listing the systems it requires. A genre is a tag on systems, not a layer. |
| **Template** | A starting game a realm is made from (a folder under `games/` or a built-in). It declares which systems are on; after creation the realm is an ordinary game. |
| **Plugin** | Sandboxed Lua in a game's `scripts/` that overrides or extends how a system works in that game. Trusted Kotlin jars in `plugins/` are *add-on systems* (server-layer hooks, windows and storage). |
| **Seam** | An interface the kernel calls instead of naming a system (`RequirementGate`, `TickParticipant`, `WarpRouter`...); a system implements the seams it needs and is registered with `WorldSimulation.install`. Twelve exist. See [system seams](/systems/system_seams.md). |
| **Genre starter** | A wizard button, F1 button or `world-new --genre` that switches on the systems tagged for a genre and off the rest. A starting point, not a rule. See [optional modules](/server/optional_modules.md). |
| **Hook** | Kotlin add-on system interface (`MorganaServerHook`). See [hook API](/server/hook_api.md). |
| **Paperdoll** | Layered equipment sprites drawn over a character. |
| **Autotile** | Bitmask-selected tile variants for seamless terrain edges. |
| **Crafting anchor** | Transient record of where a player opened a crafting station; station recipes need it. See [economy](/systems/economy.md). |
| **Owner policy** | The rule deciding who becomes Owner. See [owner policy](/server/owner_policy.md). |
| **Protocol version** | `PROTOCOL_VERSION`, checked at login. See [wire format](/protocol/wire_format.md). |
| **Mirage Source / Player Worlds** | Classic 2D ORPG engines that inspire the simplicity goals. See [competitive landscape](/reference/competitive_landscape.md). |
| **Intersect** | A C#/MonoGame engine in the Mirage lineage, used as a comparison point. |
| **OKF** | Open Knowledge Format (v0.2): markdown + YAML frontmatter bundles, the format of this wiki. See [wiki conventions](/reference/wiki_conventions.md). |
| **Concept / bundle** | OKF terms: one wiki page; the whole `wiki/` directory. |
