---
type: Protocol
title: "Session Lifecycle"
description: "What happens from connect to disconnect, including first-account rules and saves."
tags: [protocol, session]
code: ["server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt", "shared-protocol/src/main/kotlin/com/morgana/protocol/Packets.kt"]
symbols: ["onSessionConnected", "onSessionDisconnected", "PublicPlayerSnapshot", "dispatchLogin"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Session Lifecycle

1. **Connect**: Netty channel opens; the coordinator tracks a `ClientSession` ([coordinator](/server/coordinator.md)).
2. **Authenticate** (`c_auth`): protocol version checked first, then credentials; created if `registerIfNew`; reply `s_auth_res`. Five consecutive failures close the connection ([security model](/reference/security_model.md)). Other packets are ignored until a character is bound to the session. A returning account whose client advertised `character_select` is first sent `s_character_list` and picks, creates or deletes a character before entering ([characters per account](/server/characters.md)); everyone else goes straight into the account's first character. A third opt-in capability, `dialogue_flow`, says the client reports a closed dialogue line (`c_dialogue_choice` with `-1`), so [events](/systems/events_quests.md) can show one line at a time; the game client sends it on login and on resume.
3. **Enter world**: character spawns in the simulation; `s_map_snapshot` (active + neighbor maps), login hook dispatch ([hook API](/server/hook_api.md)), and other players on the map are told via `PublicPlayerSnapshot`.
4. **Play**: client sends inputs; server ticks at 20 TPS and pushes deltas ([wire format](/protocol/wire_format.md)).
5. **Autosave**: every 200 ticks (10 s).
6. **Disconnect**: character saved, logout hook, removed from simulation, `s_player_left` to the map; active trades cancelled ([trading](/systems/trading.md)). **Exception, leaving a PvP fight**: a player who dealt or took player-versus-player damage within `session.combatLogSeconds` (default 15) before dropping stays in the world as a *ghost* with no session (they can still be hit and killed, with the usual death rules) until that time is up, then is saved and removed as above; the server also removes any ghosts at shutdown. Logging back in during that time re-attaches to the same live character instead of spawning it again from the saved copy, and the player is told so. Never applies in a realm or on a map without PvP. Tests: `CombatLogTest`.

Client-side view: [state and prediction](/client/client_state_and_prediction.md).
