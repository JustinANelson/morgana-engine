---
type: Concept
title: "World Coordinator"
description: "The single synchronized authority: sessions, authentication, packet handling, persistence triggers, and event dispatch."
tags: [server, coordinator]
code: ["server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt"]
symbols: ["handleClientPacket", "stepServerTick", "acceptEdit", "reloadContent", "dispatchEvents", "handleAuthentication", "ClientSession", "rejectLogin", "MAX_AUTH_FAILURES", "ownerPolicy"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# World Coordinator (`MorganaWorldCoordinator`)

**Concurrency model**: every public entry point (`handleClientPacket`, `onSessionConnected/Disconnected`, `stepServerTick`, `reloadContent`) is `@Synchronized`
on one lock. Netty I/O threads, the 20 TPS scheduler thread, and the file-watcher thread all serialize through it. Simplest-possible consistency; see [ADR 0004](/decisions/0004_single_synchronized_coordinator.md).

**Assembly**: built by [`MorganaServerRuntime`](/server/server_runtime.md). Startup (`init`) seeds the blank game's content into an empty legacy data directory ([blank game](/content/blank_game.md)), loads all content into the simulation, loads guilds.

**Authentication** (`Authenticate`), in order:
1. **Protocol version** must equal `PROTOCOL_VERSION`, else a rejecting `AuthResult` ("update your client"); nothing is created ([wire format](/protocol/wire_format.md)).
2. Username 2-20 characters.
3. Unknown account: reject unless `registerIfNew`; otherwise create account + character from the requested class (first class if unknown) with its starting items. Access level comes from the [owner policy](/server/owner_policy.md).
4. Known account: `verifyPassword` (salted PBKDF2; legacy hashes accepted then upgraded), banned check, then promotion if the name is a configured owner.
5. Failed logins are counted per connection; the 5th closes the connection ([security model](/reference/security_model.md)).
Success sends `AuthResult` (with the character and catalog), a `MapStateSnapshot` (active map + neighbor maps), and a welcome message ([session lifecycle](/protocol/session_lifecycle.md)); then login hooks and spawn events (including map-entry events) are dispatched.

**Per tick** (`stepServerTick`): `simulation.stepTick`, `dispatchEvents`, and every 200 ticks (10 s) queue an off-lock autosave of all online characters and server state via `AsyncPersistenceWriter` (exploration updates every 10 ticks are offloaded the same way).
**On disconnect**: save the character synchronously (superseding any older queued autosave), call logout hooks, remove from simulation, notify the map.

**F1 saves** (`Save*Definition`): `acceptEdit` validates the candidate against the live catalog (rejecting with a system chat message when the edited document has errors), then
`applyLive*Edit`, persist to `server-data/world`, dispatch events ([content pipeline](/content/content_pipeline.md)).
**Event dispatch** (`dispatchEvents`): maps each [simulation event](/domain/simulation_events.md) to packets to the right sessions (map-wide, targeted, or global); `CustomHookRequested` goes to [plugins](/server/hook_api.md).
**`reloadContent()`** and `/reload`: [hot reload](/content/hot_reload.md).
Every client packet is admitted by a `PacketLimiter` first ([packet limits](/server/packet_limits.md)); dropped packets never reach the simulation or a replay recording.

Password hashing/verification runs outside the lock: `handleClientPacketOffLock` (called from the Netty I/O thread) does a brief locked hash lookup, runs PBKDF2 unlocked, then applies the result under the lock via `CredentialCheck`; a result is discarded if the stored hash changed meanwhile.
