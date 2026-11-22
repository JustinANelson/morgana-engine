---
type: Concept
title: "Simulation Events"
description: "How the pure simulation reports what happened, and how the server maps events to packets."
tags: [events, simulation]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/WorldEvents.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt"]
symbols: ["WorldSimulationEvent", "MapHotSwapped", "ContentCatalogHotSwapped", "dispatchEvents"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Simulation Events

Every mutating `WorldSimulation` call returns `List<WorldSimulationEvent>`. The simulation never sends anything itself; the
[coordinator](/server/coordinator.md) turns events into packets in `dispatchEvents`.

Events: `CharacterUpdated`, `PlayerMoved`, `NpcUpdated`, `GroundItemsChanged`, `ProjectilesChanged`, `ResourceNodeChanged`, `FloatingTextEmitted`,
`AnimationTriggered`, `ChatEmitted`, `OpenUiRequested`, `TradeUpdated`, `PartyUpdated`, `GuildUpdated`, `PlayerModerated`, `CustomHookRequested`, `ItemUsed`, `SpellCast`, `NpcKilled`, `PlayerDied` (the last four feed [plugin hooks](/server/hook_api.md)), `MapHotSwapped`, `ContentCatalogHotSwapped`.

Adding an event: add the subtype in `WorldEvents.kt`, emit it from a [system](/systems/tick_loop.md), handle it in `dispatchEvents`, and apply the resulting packet in `ClientWorldState` ([add a packet](/playbooks/add_packet.md)).
